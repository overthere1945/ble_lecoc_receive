package com.example.qc_ble_receive;

/*
 * 파일명: QcBleReceiveManager.java
 * 목적 및 기능:
 * - Mobile Device에서 Target Device "QC_BLE_T"를 BLE scan한다.
 * - Target Device의 GATT service(0000ff01)를 discovery하고 PSM characteristic(0000ff02)을 read한다.
 * - 읽은 PSM으로 LE L2CAP CoC client socket을 생성하고 Target Device에 connect한다.
 * - LE CoC socket InputStream에서 target device가 송신하는 CJPG fragment를 수신한다.
 * - CJPG header를 parsing하여 a_car_jpg/b_car_jpg JPEG chunk를 재조립하고, 완성된 JPEG를 UI callback으로 전달한다.
 * - Android 12 이상에서도 vendor Bluetooth stack이 ACCESS_FINE_LOCATION을 요구하는 경우를 대응한다.
 *
 * CJPG header format, little-endian, total 24 bytes:
 * - uint32 magic      : 0x47504A43, wire bytes 'C' 'J' 'P' 'G'
 * - uint32 image_id   : image sequence id
 * - uint32 total_len  : complete JPEG byte length
 * - uint32 offset     : chunk offset in JPEG
 * - uint16 chunk_len  : chunk byte length after this header
 * - uint16 frag_idx   : zero-based fragment index
 * - uint16 frag_count : total fragment count
 * - uint16 flags      : bit0 FIRST, bit1 LAST, bit2 IMAGE_B
 *
 * change(add)-hyungchul-20260521-0001: Hello World 문자열 수신을 CJPG/JPEG 재조립 수신으로 변경.
 * change(add)-hyungchul-20260525-0010: GATT 연결 직후 high priority connection parameter와 2M PHY 요청 추가.
 */

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class QcBleReceiveManager {
    // 로그를 UI 혹은 외부로 전달하기 위한 인터페이스
    public interface Logger {
        void log(String message);
    }

    // JPEG 이미지 수신이 완료되었을 때 호출되는 콜백 인터페이스
    public interface JpegImageListener {
        void onJpegImageReceived(byte[] jpegData, long imageId, boolean imageB, int totalLen, int fragCount);
    }

    // change(add)-hyungchul-20260522-1103: 클래스 상수에 대한 주석 추가
    private static final String TAG = "QC_BLE_RECEIVE"; // Logcat 출력을 위한 태그명
    private static final String TARGET_NAME = "QC_BLE_T"; // 탐색할 대상 기기의 이름

    // 타겟 기기의 통신 전용 GATT 서비스 UUID
    private static final UUID SERVICE_UUID =
            UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb");

    // L2CAP PSM(Protocol/Service Multiplexer) 정보를 담고 있는 캐릭터리스틱 UUID
    private static final UUID PSM_CHARACTERISTIC_UUID =
            UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb");

    // CJPG 프로토콜 관련 상수 정의
    private static final int CJPG_HEADER_LEN = 24; // 헤더의 바이트 크기
    private static final int CJPG_MAGIC = 0x47504A43; // Little-endian 'C', 'J', 'P', 'G' 매직 넘버
    private static final int CJPG_MAGIC_BYTE_0 = 'C';
    private static final int CJPG_MAGIC_BYTE_1 = 'J';
    private static final int CJPG_MAGIC_BYTE_2 = 'P';
    private static final int CJPG_MAGIC_BYTE_3 = 'G';
    
    // CJPG flags 비트 마스크 정의
    private static final int CJPG_FLAG_FIRST = 0x0001;
    private static final int CJPG_FLAG_LAST = 0x0002;
    private static final int CJPG_FLAG_IMAGE_B = 0x0004;

    // 수신 및 버퍼링 정책 관련 상수
    private static final int MAX_JPEG_BYTES = 4 * 1024 * 1024; // 수신 허용 최대 JPEG 크기 (4MB)
    private static final int MAX_STREAM_PENDING_BYTES = 64 * 1024; // L2CAP 수신 임시 버퍼 제한 (64KB)
    private static final int MAX_CJPG_CHUNK_BYTES = 4096; // 1개 단편화 청크의 최대 허용 길이
    private static final int FRAGMENT_LOG_INTERVAL = 32; // 진행 상황 로그 출력을 위한 청크 간격

    /*
     * Mobile이 GATT client 역할을 하므로, LE CoC socket 연결 전에 GATT 연결의
     * connection parameter를 high-priority/low-latency 쪽으로 요청한다.
     * 일부 Android stack은 연결 직후 요청을 바로 반영하지 않는 경우가 있어
     * 짧은 delay 후 한 번 더 요청한다.
     */
    private static final boolean REQUEST_HIGH_THROUGHPUT_PARAMS = true;
    private static final boolean REQUEST_2M_PHY = true;

    /*
     * Optional hidden API request. Android public API는 CONNECTION_PRIORITY_HIGH만 제공하고
     * 정확한 interval 값을 직접 지정하지 못한다. 이 reflection 요청은 실패할 수 있으므로
     * 실패해도 기존 public requestConnectionPriority(HIGH) 경로는 그대로 유지된다.
     * 단위: connection interval = N * 1.25ms, supervision timeout = N * 10ms.
     */
    private static final boolean REQUEST_EXACT_LE_CONNECTION_UPDATE = true;
    private static final int EXACT_CONN_MIN_INTERVAL_UNITS = 6;     // 7.5 ms
    private static final int EXACT_CONN_MAX_INTERVAL_UNITS = 8;     // 10.0 ms
    private static final int EXACT_CONN_LATENCY = 0;
    private static final int EXACT_CONN_SUPERVISION_TIMEOUT_UNITS = 500; // 5 s
    private static final int EXACT_CONN_MIN_CE_LEN = 0;
    private static final int EXACT_CONN_MAX_CE_LEN = 0;
    private static final long HIGH_THROUGHPUT_RETRY_DELAY_MS = 750L;

    private final Context context; // 애플리케이션 컨텍스트
    private final BluetoothAdapter adapter; // 안드로이드 시스템 블루투스 어댑터
    private final Logger logger; // 로그 출력 위임 객체
    private final JpegImageListener jpegImageListener; // 수신 완료 통지 리스너
    private final ExecutorService worker = Executors.newSingleThreadExecutor(); // 소켓 통신을 위한 백그라운드 단일 스레드
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean shutdown = new AtomicBoolean(false); // 매니저 종료 상태(Thread-safe) 플래그
    private final Object rxLock = new Object(); // 수신 데이터 처리 동기화를 위한 락 객체

    private BluetoothLeScanner scanner; // BLE 스캐너 객체
    private BluetoothDevice lastTargetDevice; // 스캔 중 발견한 마지막 타겟 기기
    private BluetoothGatt gatt; // GATT 통신 연결 객체
    private BluetoothSocket l2capSocket; // L2CAP CoC 소켓 객체
    private volatile boolean scanning; // 현재 스캔 진행 여부 플래그
    private volatile boolean l2capConnected; // L2CAP 소켓 연결 상태 플래그

    // 수신 및 조립(Assembly) 상태를 추적하기 위한 변수들
    private byte[] streamPending = new byte[0]; // 불완전 수신된 남은 L2CAP 스트림 데이터 버퍼
    private long assemblingImageId = -1L; // 조립 중인 이미지의 고유 ID
    private int assemblingTotalLen; // 조립 중인 이미지의 전체 바이트 길이
    private int assemblingFragCount; // 조립 중인 이미지의 총 청크(조각) 개수
    private byte[] assemblingImage; // 실제 조립 중인 전체 이미지 바이트 배열
    private boolean[] assemblingFragReceived; // 특정 인덱스의 청크가 수신되었는지 체크하는 배열
    private int assemblingReceivedFragCount; // 현재까지 수신 완료된 청크 개수
    private int assemblingReceivedBytes; // 현재까지 수신 완료된 총 바이트 수
    private boolean assemblingImageB; // 수신 중인 이미지가 b_car_jpg 인지 여부 플래그

    /*
     * change(add)-hyungchul-20260522-1103: 누락된 함수 주석 추가
     * 함수명: QcBleReceiveManager (생성자)
     * 목적 및 기능:
     * - 매니저 인스턴스를 초기화하고 시스템 자원 및 콜백 리스너를 매핑한다.
     *
     * 입력 변수:
     * - context: Application Context
     * - adapter: BluetoothAdapter 인스턴스
     * - logger: 로그 전달 인터페이스
     * - jpegImageListener: 최종 이미지 수신 콜백 인터페이스
     *
     * 출력 변수/리턴 값:
     * - QcBleReceiveManager 인스턴스
     */
    public QcBleReceiveManager(
            Context context,
            BluetoothAdapter adapter,
            Logger logger,
            JpegImageListener jpegImageListener) {
        this.context = context.getApplicationContext();
        this.adapter = adapter;
        this.logger = logger;
        this.jpegImageListener = jpegImageListener;
    }

    /*
     * 함수명: startScan
     * 목적 및 기능:
     * - QC_BLE_T 또는 SERVICE_UUID를 advertising하는 Target Device를 scan한다.
     * - ACCESS_FINE_LOCATION, BLUETOOTH_SCAN, BLUETOOTH_CONNECT, Location service 상태를 확인한다.
     * - 발견하면 scan을 중지하고 GATT 연결을 시작한다.
     *
     * 입력 변수/출력 변수/리턴 값:
     * - 없음
     */
    @SuppressLint("MissingPermission")
    public void startScan() {
        log("startScan() entered.");

        if (shutdown.get()) {
            log("startScan skipped: manager is already shutdown.");
            return;
        }

        if (adapter == null) {
            log("BluetoothAdapter is null.");
            return;
        }

        if (!hasFineLocationPermission()) {
            log("ACCESS_FINE_LOCATION is not granted. Scan is blocked by Bluetooth stack.");
            return;
        }

        if (!isLocationServiceEnabled()) {
            log("Location service is OFF. Turn on Location service before BLE scan.");
            return;
        }

        if (!hasScanPermission()) {
            log("Scan permission is not granted.");
            return;
        }

        if (!hasConnectPermission()) {
            log("Connect permission is not granted.");
            return;
        }

        if (!adapter.isEnabled()) {
            log("Bluetooth is disabled.");
            return;
        }

        // 기존 연결을 깨끗하게 정리한 후 스캔 시작
        stopAll();

        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            log("BluetoothLeScanner is null.");
            return;
        }

        // Low Latency 모드를 적용하여 스캔 세팅을 구성
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        scanning = true;

        try {
            // BLE 스캔 요청
            scanner.startScan(null, settings, scanCallback);
            log("BLE scan started. Looking for name=" + TARGET_NAME + ", service=" + SERVICE_UUID);
        } catch (SecurityException e) {
            scanning = false;
            log("scanner.startScan SecurityException: " + e);
        }
    }

    /*
     * 함수명: stopAll
     * 목적 및 기능:
     * - scan, GATT, LE CoC socket을 모두 정리한다.
     * - 진행 중인 CJPG/JPEG 수신 buffer를 초기화한다.
     *
     * 입력 변수/출력 변수/리턴 값:
     * - 없음
     */
    @SuppressLint("MissingPermission")
    public void stopAll() {
        // 스캔 동작 중지
        stopScanOnly();
        mainHandler.removeCallbacksAndMessages(null);

        l2capConnected = false;

        // L2CAP 소켓 연결 종료
        try {
            if (l2capSocket != null) {
                l2capSocket.close();
            }
        } catch (Throwable ignored) {
        }
        l2capSocket = null;

        // GATT 서비스 및 연결 종료
        try {
            if (gatt != null && hasConnectPermission()) {
                gatt.disconnect();
                gatt.close();
            }
        } catch (SecurityException e) {
            log("gatt close SecurityException: " + e);
        } catch (Throwable ignored) {
        }
        gatt = null;

        // 진행 중이던 수신 버퍼 락 획득 후 초기화
        synchronized (rxLock) {
            resetReceiveStateLocked();
        }

        log("All BLE/GATT/L2CAP resources stopped.");
    }

    /*
     * 함수명: shutdown
     * 목적 및 기능:
     * - Activity 종료 시 BLE resource를 정리하고 worker thread를 종료한다.
     *
     * 입력 변수/출력 변수/리턴 값:
     * - 없음
     */
    public void shutdown() {
        // 원자적으로 플래그를 확인 및 설정하여 중복 실행을 방지
        if (shutdown.compareAndSet(false, true)) {
            stopAll();
            worker.shutdownNow(); // 워커 스레드풀 강제 종료
        }
    }

    /*
     * 함수명: clearReceivedImageBuffers
     * 목적 및 기능:
     * - CLEAR 버튼에서 호출되어 수신 중인 CJPG stream buffer와 JPEG 조립 buffer를 비운다.
     * - BLE 연결은 유지한다.
     *
     * 입력 변수/출력 변수/리턴 값:
     * - 없음
     */
    public void clearReceivedImageBuffers() {
        synchronized (rxLock) {
            resetReceiveStateLocked();
        }

        log("CJPG/JPEG RX buffers cleared.");
    }

    /*
     * 함수명: connectLastDeviceWithPsm
     * 목적 및 기능:
     * - 마지막으로 발견한 Target Device에 manual PSM으로 LE CoC client 연결을 시도한다.
     * - 현재 UI에서는 manual PSM 버튼이 없지만 debug 재사용을 위해 유지한다.
     *
     * 입력 변수:
     * - psm: Target Device server socket의 dynamic PSM (정수형 포트 번호)
     *
     * 출력 변수/리턴 값:
     * - 없음
     */
    public void connectLastDeviceWithPsm(int psm) {
        if (lastTargetDevice == null) {
            log("No target device found yet. Start scan first.");
            return;
        }

        connectL2capClient(lastTargetDevice, psm);
    }

    /*
     * 함수명: stopScanOnly
     * 목적 및 기능:
     * - BLE scan만 중지한다.
     *
     * 입력 변수/출력 변수/리턴 값:
     * - 없음
     */
    @SuppressLint("MissingPermission")
    private void stopScanOnly() {
        if (!scanning) {
            return;
        }

        scanning = false;

        try {
            if (scanner != null && hasScanPermission()) {
                scanner.stopScan(scanCallback);
            }
        } catch (SecurityException e) {
            log("scanner.stopScan SecurityException: " + e);
        } catch (Throwable ignored) {
        }

        scanner = null;
        log("BLE scan stopped.");
    }

    // 블루투스 스캔 결과를 처리하는 시스템 콜백 객체
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            handleScanResult(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) {
                handleScanResult(result);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            log("BLE scan failed. errorCode=" + errorCode);
        }
    };

    /*
     * 함수명: handleScanResult
     * 목적 및 기능:
     * - ScanResult에서 device name과 service uuid를 확인하여 QC_BLE_T Target인지 판단한다.
     *
     * 입력 변수:
     * - result: BLE scan result (디바이스 및 패킷 정보를 담고 있음)
     *
     * 출력 변수/리턴 값:
     * - 없음
     */
    @SuppressLint("MissingPermission")
    private void handleScanResult(ScanResult result) {
        if (result == null || result.getDevice() == null) {
            return;
        }

        BluetoothDevice device = result.getDevice();
        ScanRecord record = result.getScanRecord();

        // Advertising 레코드 및 장치 속성에서 이름과 서비스 UUID 추출
        String recordName = record != null ? record.getDeviceName() : null;
        String deviceName = safeDeviceName(device);
        boolean hasServiceUuid = hasTargetServiceUuid(record);
        // 디바이스 이름이 목적 이름(QC_BLE_T)과 일치하는지 확인
        boolean nameMatched = TARGET_NAME.equals(recordName) || TARGET_NAME.equals(deviceName);

        // 타겟의 이름과 서비스 UUID 중 하나라도 일치하지 않으면 무시
        if (!nameMatched && !hasServiceUuid) {
            return;
        }

        log("Target found. address=" + safeAddress(device)
                + ", recordName=" + recordName
                + ", deviceName=" + deviceName
                + ", serviceMatched=" + hasServiceUuid
                + ", rssi=" + result.getRssi());

        lastTargetDevice = device;
        // 타겟을 찾았으므로 스캔을 중단하고 GATT 연결로 전환
        stopScanOnly();
        connectGatt(device);
    }

    /*
     * change(add)-hyungchul-20260522-1103: 누락된 함수 주석 추가
     * 함수명: hasTargetServiceUuid
     * 목적 및 기능:
     * - ScanRecord에 포함된 Service UUID 목록 중 타겟의 SERVICE_UUID가 존재하는지 검사한다.
     *
     * 입력 변수:
     * - record: 스캔 시 수신한 Advertising ScanRecord 객체
     *
     * 출력 변수/리턴 값:
     * - boolean: true(존재함), false(존재하지 않음)
     */
    private boolean hasTargetServiceUuid(ScanRecord record) {
        if (record == null || record.getServiceUuids() == null) {
            return false;
        }

        for (ParcelUuid uuid : record.getServiceUuids()) {
            if (uuid != null && SERVICE_UUID.equals(uuid.getUuid())) {
                return true;
            }
        }

        return false;
    }

    /*
     * 함수명: connectGatt
     * 목적 및 기능:
     * - Target Device에 GATT 연결을 수행한다.
     * - service discovery 후 PSM characteristic을 read한다.
     *
     * 입력 변수:
     * - device: Target BluetoothDevice (연결을 시도할 블루투스 객체)
     *
     * 출력 변수/리턴 값:
     * - 없음
     */
    @SuppressLint("MissingPermission")
    private void connectGatt(BluetoothDevice device) {
        if (!hasConnectPermission()) {
            log("connectGatt skipped: BLUETOOTH_CONNECT permission is not granted.");
            return;
        }

        try {
            log("Connecting GATT to target=" + safeAddress(device));

            // 안드로이드 마시멜로(M, API 23) 이상에서는 TRANSPORT_LE 명시
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } else {
                gatt = device.connectGatt(context, false, gattCallback);
            }
        } catch (SecurityException e) {
            log("connectGatt SecurityException: " + e);
        }
    }

    // GATT 연결 및 서비스 탐색, 특징값 읽기를 처리하는 시스템 콜백 객체
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            log("GATT state changed. status=" + status + ", newState=" + newState);

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                requestHighThroughputConnectionParams(gatt, "gatt-connected");
                scheduleHighThroughputConnectionParamsRetry(gatt);

                try {
                    // GATT 연결 성공 시, 해당 디바이스가 지원하는 서비스 탐색 시작
                    boolean started = gatt.discoverServices();
                    log("GATT discoverServices started=" + started);
                } catch (SecurityException e) {
                    log("discoverServices SecurityException: " + e);
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mainHandler.removeCallbacksAndMessages(null);
                log("GATT disconnected.");
            }
        }

        @Override
        public void onPhyUpdate(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {
            log("GATT PHY updated. status=" + status
                    + ", txPhy=" + phyToString(txPhy)
                    + ", rxPhy=" + phyToString(rxPhy));
        }

        @Override
        public void onPhyRead(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {
            log("GATT PHY read. status=" + status
                    + ", txPhy=" + phyToString(txPhy)
                    + ", rxPhy=" + phyToString(rxPhy));
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            log("GATT services discovered. status=" + status);

            if (status != BluetoothGatt.GATT_SUCCESS) {
                return;
            }

            // 의도한 통신 서비스 객체를 획득
            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            if (service == null) {
                log("Target GATT service not found: " + SERVICE_UUID);
                return;
            }

            // PSM 값이 기록되어 있는 특정 캐릭터리스틱을 획득
            BluetoothGattCharacteristic psmCharacteristic =
                    service.getCharacteristic(PSM_CHARACTERISTIC_UUID);

            if (psmCharacteristic == null) {
                log("PSM characteristic not found: " + PSM_CHARACTERISTIC_UUID);
                return;
            }

            try {
                // 캐릭터리스틱 읽기 요청 수행 (L2CAP 연결을 위한 PSM 획득 용도)
                boolean readStarted = gatt.readCharacteristic(psmCharacteristic);
                log("Read PSM characteristic started=" + readStarted);
            } catch (SecurityException e) {
                log("readCharacteristic SecurityException: " + e);
            }
        }

        // 구 버전 안드로이드 호환을 위한 read 콜백
        @Override
        public void onCharacteristicRead(
                BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic,
                int status) {
            if (characteristic == null) {
                return;
            }

            handlePsmCharacteristicValue(characteristic.getUuid(), characteristic.getValue(), status);
        }

        // 신 버전 안드로이드(TIRAMISU 이상 권장)를 위한 바이트 배열 직결 read 콜백
        @Override
        public void onCharacteristicRead(
                BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic,
                byte[] value,
                int status) {
            if (characteristic == null) {
                return;
            }

            handlePsmCharacteristicValue(characteristic.getUuid(), value, status);
        }
    };

    /*
     * 함수명: requestHighThroughputConnectionParams
     * 목적 및 기능:
     * - Mobile Receive App이 GATT client로 연결된 직후, BLE link를 큰 데이터 수신에 유리한
     *   high-priority/low-latency connection parameter로 요청한다.
     * - 가능하면 LE 2M PHY도 요청한다. 실제 적용 여부는 peer/controller capability와
     *   Android Bluetooth stack 정책에 따라 결정되며, onPhyUpdate()/onPhyRead() log로 확인한다.
     *
     * 입력 변수:
     * - activeGatt: 연결된 BluetoothGatt
     * - reason: log 구분용 문자열
     *
     * 출력 변수/리턴 값:
     * - 없음
     */
    @SuppressLint("MissingPermission")
    private void requestHighThroughputConnectionParams(BluetoothGatt activeGatt, String reason) {
        if (!REQUEST_HIGH_THROUGHPUT_PARAMS) {
            return;
        }

        if (activeGatt == null) {
            log("requestHighThroughputConnectionParams skipped: gatt is null. reason=" + reason);
            return;
        }

        if (!hasConnectPermission()) {
            log("requestHighThroughputConnectionParams skipped: BLUETOOTH_CONNECT permission missing. reason="
                    + reason);
            return;
        }

        try {
            boolean priorityStarted = activeGatt.requestConnectionPriority(
                    BluetoothGatt.CONNECTION_PRIORITY_HIGH);
            log("requestConnectionPriority(HIGH) reason=" + reason + ", started=" + priorityStarted);
        } catch (SecurityException e) {
            log("requestConnectionPriority(HIGH) SecurityException. reason=" + reason + ", error=" + e);
        } catch (Throwable t) {
            log("requestConnectionPriority(HIGH) failed. reason=" + reason + ", error=" + t);
        }

        requestExactLeConnectionUpdateIfPossible(activeGatt, reason);

        if (REQUEST_2M_PHY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                activeGatt.setPreferredPhy(
                        BluetoothDevice.PHY_LE_2M_MASK,
                        BluetoothDevice.PHY_LE_2M_MASK,
                        BluetoothDevice.PHY_OPTION_NO_PREFERRED);
                log("setPreferredPhy(LE_2M) requested. reason=" + reason);

                activeGatt.readPhy();
                log("readPhy() requested. reason=" + reason);
            } catch (SecurityException e) {
                log("setPreferredPhy/readPhy SecurityException. reason=" + reason + ", error=" + e);
            } catch (Throwable t) {
                log("setPreferredPhy/readPhy failed. reason=" + reason + ", error=" + t);
            }
        }
    }

    /*
     * 함수명: requestExactLeConnectionUpdateIfPossible
     * 목적 및 기능:
     * - Android hidden API requestLeConnectionUpdate()를 reflection으로 호출하여
     *   connection interval을 7.5~10ms 범위로 더 강하게 요청한다.
     * - hidden API 접근이 차단되거나 vendor framework에서 제공하지 않는 경우가 있으므로,
     *   실패해도 예외를 밖으로 던지지 않고 log만 남긴다.
     */
    @SuppressLint("MissingPermission")
    private void requestExactLeConnectionUpdateIfPossible(BluetoothGatt activeGatt, String reason) {
        if (!REQUEST_EXACT_LE_CONNECTION_UPDATE) {
            return;
        }

        if (activeGatt == null || !hasConnectPermission()) {
            return;
        }

        try {
            Method method = BluetoothGatt.class.getMethod(
                    "requestLeConnectionUpdate",
                    int.class, int.class, int.class,
                    int.class, int.class, int.class);

            Object result = method.invoke(
                    activeGatt,
                    EXACT_CONN_MIN_INTERVAL_UNITS,
                    EXACT_CONN_MAX_INTERVAL_UNITS,
                    EXACT_CONN_LATENCY,
                    EXACT_CONN_SUPERVISION_TIMEOUT_UNITS,
                    EXACT_CONN_MIN_CE_LEN,
                    EXACT_CONN_MAX_CE_LEN);

            log("requestLeConnectionUpdate(hidden) reason=" + reason
                    + ", result=" + result
                    + ", min=" + String.format(Locale.US, "%.1f", EXACT_CONN_MIN_INTERVAL_UNITS * 1.25f) + "ms"
                    + ", max=" + String.format(Locale.US, "%.1f", EXACT_CONN_MAX_INTERVAL_UNITS * 1.25f) + "ms"
                    + ", latency=" + EXACT_CONN_LATENCY
                    + ", timeout=" + (EXACT_CONN_SUPERVISION_TIMEOUT_UNITS * 10) + "ms"
                    + ", ceLen=" + EXACT_CONN_MIN_CE_LEN + "/" + EXACT_CONN_MAX_CE_LEN);
        } catch (NoSuchMethodException e) {
            log("requestLeConnectionUpdate(hidden) not available. reason=" + reason + ", error=" + e);
        } catch (SecurityException e) {
            log("requestLeConnectionUpdate(hidden) SecurityException. reason=" + reason + ", error=" + e);
        } catch (Throwable t) {
            log("requestLeConnectionUpdate(hidden) failed. reason=" + reason + ", error=" + t);
        }
    }

    private void scheduleHighThroughputConnectionParamsRetry(BluetoothGatt activeGatt) {
        if (!REQUEST_HIGH_THROUGHPUT_PARAMS || HIGH_THROUGHPUT_RETRY_DELAY_MS <= 0) {
            return;
        }

        mainHandler.postDelayed(() -> {
            if (shutdown.get()) {
                return;
            }

            if (activeGatt != null && activeGatt == QcBleReceiveManager.this.gatt) {
                requestHighThroughputConnectionParams(activeGatt, "gatt-connected-retry");
            }
        }, HIGH_THROUGHPUT_RETRY_DELAY_MS);
    }

    private String phyToString(int phy) {
        switch (phy) {
            case BluetoothDevice.PHY_LE_1M:
                return "LE_1M(" + phy + ")";
            case BluetoothDevice.PHY_LE_2M:
                return "LE_2M(" + phy + ")";
            case BluetoothDevice.PHY_LE_CODED:
                return "LE_CODED(" + phy + ")";
            default:
                return "UNKNOWN(" + phy + ")";
        }
    }

    /*
     * 함수명: handlePsmCharacteristicValue
     * 목적 및 기능:
     * - little-endian 2 byte PSM 값을 parsing하고 LE CoC client connect를 시작한다.
     *
     * 입력 변수:
     * - uuid: characteristic UUID (읽어온 값이 우리가 의도한 PSM 특징값인지 확인하기 위함)
     * - value: characteristic value (읽어들인 바이트 데이터)
     * - status: GATT read status (성공 여부 상태 값)
     *
     * 출력 변수/리턴 값:
     * - 없음
     */
    private void handlePsmCharacteristicValue(UUID uuid, byte[] value, int status) {
        // PSM을 제공하는 캐릭터리스틱이 아니면 무시
        if (!PSM_CHARACTERISTIC_UUID.equals(uuid)) {
            return;
        }

        log("PSM characteristic read callback. status=" + status + ", len=" + (value == null ? 0 : value.length));

        if (status != BluetoothGatt.GATT_SUCCESS) {
            log("PSM read failed. status=" + status);
            return;
        }

        if (value == null || value.length < 2) {
            log("Invalid PSM value.");
            return;
        }

        // 수신된 2바이트 배열을 Little-Endian 형식의 부호없는 16비트 정수형으로 변환
        int psm = ByteBuffer.wrap(value)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getShort() & 0xFFFF;

        log("PSM read success. PSM=" + psm);
        // 정상적으로 획득된 PSM 값을 기반으로 L2CAP CoC 소켓 연결 개시
        connectL2capClient(lastTargetDevice, psm);
    }

    /*
     * 함수명: connectL2capClient
     * 목적 및 기능:
     * - Target Device의 dynamic PSM으로 LE L2CAP CoC client socket을 생성하고 connect한다.
     * - minSdk 26에서 API 29 lint error를 피하기 위해 reflection으로 createInsecureL2capChannel()을 호출한다.
     *
     * 입력 변수:
     * - device: Target BluetoothDevice (L2CAP 연결 대상 기기)
     * - psm: Target LE CoC server PSM (접속할 L2CAP 포트 번호)
     *
     * 출력 변수/리턴 값:
     * - 없음
     */
    private void connectL2capClient(BluetoothDevice device, int psm) {
        if (device == null) {
            log("connectL2capClient skipped: target device is null.");
            return;
        }

        if (psm <= 0 || psm > 0xFFFF) {
            log("connectL2capClient skipped: invalid PSM=" + psm);
            return;
        }

        // 소켓 생성 및 통신은 블로킹 동작이므로 백그라운드 워커 스레드에서 수행
        worker.execute(() -> {
            try {
                if (shutdown.get()) {
                    log("connectL2capClient skipped: manager is shutdown.");
                    return;
                }

                // L2CAP CoC는 Android Q (API 29)부터 지원됨을 명시적 제한
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    log("LE L2CAP CoC requires Android Q/API 29+.");
                    return;
                }

                if (!hasConnectPermission()) {
                    log("connectL2capClient skipped: BLUETOOTH_CONNECT permission is not granted.");
                    return;
                }

                synchronized (rxLock) {
                    resetReceiveStateLocked();
                }

                log("Creating LE CoC client socket by reflection. target="
                        + safeAddress(device) + ", psm=" + psm);

                // Reflection 기법을 활용하여 히든 API에 우회 접근하여 보안성 없는 L2CAP 채널 생성
                Method method = BluetoothDevice.class.getMethod("createInsecureL2capChannel", int.class);
                Object socketObject = method.invoke(device, psm);

                if (!(socketObject instanceof BluetoothSocket)) {
                    log("createInsecureL2capChannel did not return BluetoothSocket.");
                    return;
                }

                l2capSocket = (BluetoothSocket) socketObject;

                log("Connecting LE CoC client socket...");
                l2capSocket.connect(); // 실제 소켓 연결 수행 (Blocking)

                l2capConnected = true;
                requestHighThroughputConnectionParams(gatt, "l2cap-connected");
                log("LE CoC client socket connected. Waiting CJPG/JPEG fragments...");

                // 연결이 완료되면 수신 대기 루프 실행
                readL2capLoop(l2capSocket);
            } catch (Throwable t) {
                l2capConnected = false;
                log("LE CoC client connect/read failed: " + t);
            }
        });
    }

    /*
     * 함수명: readL2capLoop
     * 목적 및 기능:
     * - LE CoC socket InputStream에서 Target Device가 송신하는 CJPG frame stream을 읽는다.
     * - InputStream read boundary가 CJPG frame boundary와 다를 수 있으므로 pending buffer에서 CJPG magic/header를 기준으로 재조립한다.
     *
     * 입력 변수:
     * - socket: 연결된 BluetoothSocket (데이터를 수신할 채널)
     *
     * 출력 변수/리턴 값:
     * - 없음
     */
    private void readL2capLoop(BluetoothSocket socket) {
        try {
            InputStream inputStream = socket.getInputStream();
            int socketMaxRx = socket.getMaxReceivePacketSize();
            int socketMaxTx = socket.getMaxTransmitPacketSize();
            int readBufferLen = Math.max(2048, socketMaxRx > 0 ? socketMaxRx : 0);
            readBufferLen = Math.min(readBufferLen, MAX_STREAM_PENDING_BYTES);

            log("LE CoC socket packet size. socketMaxRx=" + socketMaxRx
                    + ", socketMaxTx=" + socketMaxTx
                    + ", readBufferLen=" + readBufferLen);

            byte[] buffer = new byte[readBufferLen];

            while (l2capConnected && !shutdown.get()) {
                // 스트림으로부터 데이터를 블로킹 방식으로 읽음
                int read = inputStream.read(buffer);

                // 스트림 종료(소켓 끊어짐) 시 루프 탈출
                if (read < 0) {
                    log("LE CoC socket EOF.");
                    break;
                }

                if (read == 0) {
                    continue;
                }

                // 읽어들인 바이트 데이터를 통합 처리 함수로 인계
                handleL2capRxBytes(buffer, read);
            }
        } catch (Throwable t) {
            log("readL2capLoop stopped: " + t);
        } finally {
            l2capConnected = false;
        }
    }

    /*
     * 함수명: handleL2capRxBytes
     * 목적 및 기능:
     * - LE CoC InputStream에서 읽은 byte를 streamPending에 누적한다.
     * - CJPG complete frame이 생길 때마다 parsing하여 JPEG 조립 함수로 넘긴다.
     *
     * 입력 변수:
     * - data: read buffer (스트림에서 막 읽어들인 원본 바이트 배열)
     * - len: 유효 byte 길이 (data 배열 안에서 실제로 의미 있는 데이터의 길이)
     *
     * 출력 변수/리턴 값:
     * - 없음
     */
    private void handleL2capRxBytes(byte[] data, int len) {
        if (data == null || len <= 0) {
            return;
        }

        // 공유 버퍼인 streamPending 조작을 위한 동기화 블록
        synchronized (rxLock) {
            int oldLen = streamPending.length;
            // 허용된 최대 펜딩 사이즈를 초과하면 시스템 보호를 위해 버퍼를 강제 초기화
            if (oldLen + len > MAX_STREAM_PENDING_BYTES) {
                log("CJPG stream pending overflow. Reset RX state. old=" + oldLen + ", add=" + len);
                resetReceiveStateLocked();
                oldLen = 0;
            }

            // 기존 스트림 버퍼 크기를 늘리고 새로운 데이터를 꼬리에 덧붙임
            streamPending = Arrays.copyOf(streamPending, oldLen + len);
            System.arraycopy(data, 0, streamPending, oldLen, len);

            // 누적된 스트림 내에서 CJPG 프레임 단위를 추출하여 파싱
            parsePendingCjpgFramesLocked();
        }
    }

    /*
     * 함수명: parsePendingCjpgFramesLocked
     * 목적 및 기능:
     * - streamPending에서 CJPG magic을 찾고, complete frame이 준비되면 header와 payload를 처리한다.
     * - 부분 header/frame은 streamPending에 남겨 다음 InputStream read와 이어 붙인다.
     *
     * 입력 변수/출력 변수/리턴 값:
     * - 없음
     */
    private void parsePendingCjpgFramesLocked() {
        int consumed = 0; // 처리 완료되어 버퍼에서 지워야 할 바이트 누적 수치

        // 스트림에 헤더를 식별하기 위한 최소 크기(Magic 넘버 4바이트)가 남아있는 동안 루프
        while (streamPending.length - consumed >= 4) {
            // 현재 탐색 위치(consumed)부터 Magic 넘버 시작점 탐색
            int magicPos = findCjpgMagic(streamPending, consumed, streamPending.length);
            if (magicPos < 0) {
                // Magic 넘버가 발견되지 않으면 불완전한 상태일 수 있으므로 마지막 3바이트만 남기고 소모 처리
                int keep = Math.min(3, streamPending.length - consumed);
                consumed = streamPending.length - keep;
                break;
            }

            // Magic 넘버 이전에 발생한 알 수 없는 쓰레기 데이터 폐기 로그
            if (magicPos > consumed) {
                log("Dropped non-CJPG bytes before magic. bytes=" + (magicPos - consumed));
            }

            // 스트림에 전체 CJPG 헤더(24바이트)를 구성할 만큼의 데이터가 아직 없다면 다음 수신 대기
            if (streamPending.length - magicPos < CJPG_HEADER_LEN) {
                consumed = magicPos;
                break;
            }

            // 헤더 정보 파싱 수행
            CjpgHeader header = parseCjpgHeader(streamPending, magicPos);
            // 헤더 검증 실패 시, 해당 Magic 패턴은 우연이거나 오류이므로 포인터를 1칸 미루고 재탐색
            if (!isValidCjpgHeader(header)) {
                log("Invalid CJPG header near offset=" + magicPos + ": " + header.summary());
                consumed = magicPos + 1;
                continue;
            }

            // CJPG 프레임 전체 크기 산출 (헤더 + 청크 페이로드)
            int frameLen = CJPG_HEADER_LEN + header.chunkLen;
            // 아직 페이로드 데이터가 전부 도착하지 않았다면 다음 수신 대기
            if (streamPending.length - magicPos < frameLen) {
                consumed = magicPos;
                break;
            }

            // 완전한 1개 단위 프레임이 구성되었으므로, 페이로드를 JPEG 이미지 버퍼에 조립
            processCjpgFragmentLocked(header, streamPending, magicPos + CJPG_HEADER_LEN);
            // 처리 완료된 바이트 포인터 갱신 (다음 프레임 탐색을 위함)
            consumed = magicPos + frameLen;
        }

        // 완전히 처리(소모)된 바이트들을 streamPending 버퍼에서 제거
        if (consumed > 0) {
            streamPending = Arrays.copyOfRange(streamPending, consumed, streamPending.length);
        }
    }

    /*
     * change(add)-hyungchul-20260522-1103: 누락된 함수 주석 추가
     * 함수명: findCjpgMagic
     * 목적 및 기능:
     * - 버퍼 내 주어진 구간(start ~ end)에서 CJPG 프레임 식별용 Magic 문자열('C','J','P','G')이 시작하는 인덱스를 찾는다.
     *
     * 입력 변수:
     * - buffer: 바이트 배열 버퍼
     * - start: 탐색 시작 인덱스
     * - end: 탐색 종료 인덱스 한계
     *
     * 출력 변수/리턴 값:
     * - int: 발견 시 해당 패턴의 시작 인덱스 반환, 미발견 시 -1 반환
     */
    private int findCjpgMagic(byte[] buffer, int start, int end) {
        int limit = end - 4; // 4바이트 패턴이 들어갈 수 있는 최대 인덱스
        for (int i = start; i <= limit; i++) {
            if ((buffer[i] & 0xFF) == CJPG_MAGIC_BYTE_0
                    && (buffer[i + 1] & 0xFF) == CJPG_MAGIC_BYTE_1
                    && (buffer[i + 2] & 0xFF) == CJPG_MAGIC_BYTE_2
                    && (buffer[i + 3] & 0xFF) == CJPG_MAGIC_BYTE_3) {
                return i;
            }
        }

        return -1;
    }

    /*
     * change(add)-hyungchul-20260522-1103: 누락된 함수 주석 추가
     * 함수명: parseCjpgHeader
     * 목적 및 기능:
     * - 버퍼 내 특정 위치에서 Little-endian 방식의 24바이트 CJPG 헤더를 파싱하여 객체 구조체로 생성한다.
     *
     * 입력 변수:
     * - buffer: 데이터 스트림이 담긴 바이트 배열
     * - offset: 헤더 구조가 시작되는 인덱스 (매직 문자열의 위치)
     *
     * 출력 변수/리턴 값:
     * - CjpgHeader: 추출된 정보가 맵핑된 내부 헤더 클래스 객체
     */
    private CjpgHeader parseCjpgHeader(byte[] buffer, int offset) {
        ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, offset, CJPG_HEADER_LEN)
                .order(ByteOrder.LITTLE_ENDIAN);

        CjpgHeader header = new CjpgHeader();
        header.magic = byteBuffer.getInt();
        // Java에서는 unsigned 자료형이 없으므로 long(혹은 int & mask) 타입으로 보존
        header.imageId = byteBuffer.getInt() & 0xFFFFFFFFL;
        header.totalLen = byteBuffer.getInt() & 0xFFFFFFFFL;
        header.offset = byteBuffer.getInt() & 0xFFFFFFFFL;
        header.chunkLen = byteBuffer.getShort() & 0xFFFF;
        header.fragIdx = byteBuffer.getShort() & 0xFFFF;
        header.fragCount = byteBuffer.getShort() & 0xFFFF;
        header.flags = byteBuffer.getShort() & 0xFFFF;
        return header;
    }

    /*
     * change(add)-hyungchul-20260522-1103: 누락된 함수 주석 추가
     * 함수명: isValidCjpgHeader
     * 목적 및 기능:
     * - 파싱된 CJPG 헤더 정보들이 논리적인 범위 내에 존재하는지 산술적으로 검증(Sanity check)한다.
     *
     * 입력 변수:
     * - header: 파싱이 완료된 CjpgHeader 객체
     *
     * 출력 변수/리턴 값:
     * - boolean: true(정상 헤더로 판단), false(비정상 값 존재)
     */
    private boolean isValidCjpgHeader(CjpgHeader header) {
        if (header == null || header.magic != CJPG_MAGIC) {
            return false;
        }

        // 전체 이미지가 0바이트 이하 이거나 시스템 허용치를 초과하는 경우 차단
        if (header.totalLen == 0 || header.totalLen > MAX_JPEG_BYTES || header.totalLen > Integer.MAX_VALUE) {
            return false;
        }

        // 기록될 데이터 오프셋 위치가 전체 파일 크기보다 클 수 없음
        if (header.offset >= header.totalLen || header.offset > Integer.MAX_VALUE) {
            return false;
        }

        // 개별 청크 사이즈 유효성 (최대 허용 크기 초과 여부 점검)
        if (header.chunkLen == 0 || header.chunkLen > MAX_CJPG_CHUNK_BYTES) {
            return false;
        }

        // 현재 조각이 파일 전체 크기 버퍼 범위를 뚫고 나가는지 확인
        if (header.offset + header.chunkLen > header.totalLen) {
            return false;
        }

        // 분할 횟수 및 인덱스의 논리적 오류 검사
        if (header.fragCount == 0 || header.fragIdx >= header.fragCount) {
            return false;
        }

        return true;
    }

    /*
     * 함수명: processCjpgFragmentLocked
     * 목적 및 기능:
     * - CJPG header의 image_id/offset/frag_idx 정보로 JPEG image buffer에 payload를 복사한다.
     * - 모든 fragment가 수신되면 JPEG sanity check 후 UI callback으로 완성된 JPEG를 전달한다.
     *
     * 입력 변수:
     * - header: CJPG header (파싱 및 검증이 완료된 정보 객체)
     * - source: header 뒤 payload가 들어 있는 pending buffer (원본 스트림 버퍼)
     * - payloadOffset: source 내부 payload 시작 위치 (인덱스)
     *
     * 출력 변수/리턴 값:
     * - 없음
     */
    private void processCjpgFragmentLocked(CjpgHeader header, byte[] source, int payloadOffset) {
        // 이전에 조립 중이던 이미지 데이터가 없거나, 새로운 ID의 이미지가 수신되기 시작한 경우 조립 버퍼 초기화
        if (assemblingImage == null || assemblingImageId != header.imageId) {
            startJpegAssemblyLocked(header);
        } else if (assemblingTotalLen != (int) header.totalLen || assemblingFragCount != header.fragCount) {
            log("CJPG image parameter changed for same imageId. Reset assembly. " + header.summary());
            startJpegAssemblyLocked(header);
        }

        // 방어 로직: 초기화가 정상적으로 이루어지지 않았을 시 진입 방지
        if (assemblingImage == null || assemblingFragReceived == null) {
            return;
        }

        int offset = (int) header.offset;
        // 다시 한번 청크 범위를 이중 체크
        if (offset + header.chunkLen > assemblingImage.length) {
            log("CJPG fragment out of range. " + header.summary());
            return;
        }

        // 페이로드 데이터를 조립용 최종 JPEG 버퍼의 정확한 위치에 복사 (재조립 수행)
        System.arraycopy(source, payloadOffset, assemblingImage, offset, header.chunkLen);

        // 해당 인덱스 번호의 청크가 중복 수신된 것이 아니라면 완료 개수 및 바이트 통계 업데이트
        if (!assemblingFragReceived[header.fragIdx]) {
            assemblingFragReceived[header.fragIdx] = true;
            assemblingReceivedFragCount++;
            assemblingReceivedBytes += header.chunkLen;
        }

        // 첫 번째, 마지막 프레임이거나, 로그 출력 간격 조건에 부합할 경우 조립 진행 상황을 로그로 출력
        if (assemblingReceivedFragCount == 1
                || (header.flags & CJPG_FLAG_LAST) != 0
                || assemblingReceivedFragCount == assemblingFragCount
                || (assemblingReceivedFragCount % FRAGMENT_LOG_INTERVAL) == 0) {
            log(String.format(Locale.US,
                    "RX CJPG imageId=%d %s frag=%d/%d offset=%d chunk=%d receivedBytes=%d/%d",
                    header.imageId,
                    assemblingImageB ? "b_car_jpg" : "a_car_jpg",
                    header.fragIdx + 1,
                    header.fragCount,
                    offset,
                    header.chunkLen,
                    assemblingReceivedBytes,
                    assemblingTotalLen));
        }

        // 목표 수신 조각 수와 완료된 조각 수가 동일해지면 완성 루틴 호출
        if (assemblingReceivedFragCount == assemblingFragCount) {
            completeJpegAssemblyLocked();
        }
    }

    /*
     * change(add)-hyungchul-20260522-1103: 누락된 함수 주석 추가
     * 함수명: startJpegAssemblyLocked
     * 목적 및 기능:
     * - 새로운 이미지가 전송될 때, 이를 담을 적합한 크기의 메모리 버퍼 및 상태 추적 배열들을 초기 할당한다.
     *
     * 입력 변수:
     * - header: 새롭게 조립을 개시할 이미지의 정보를 담은 CJPG 헤더
     *
     * 출력 변수/리턴 값:
     * - 없음
     */
    private void startJpegAssemblyLocked(CjpgHeader header) {
        int totalLen = (int) header.totalLen;
        int fragCount = header.fragCount;

        // 혹시 남아있을 수 있는 이전 데이터를 먼저 클리어
        resetJpegAssemblyOnlyLocked();

        assemblingImageId = header.imageId;
        assemblingTotalLen = totalLen;
        assemblingFragCount = fragCount;
        assemblingImage = new byte[totalLen]; // 실제 파일 크기만큼의 바이트 배열 할당
        assemblingFragReceived = new boolean[fragCount]; // 조각 유실 및 중복 수신 체크용 배열
        assemblingReceivedFragCount = 0;
        assemblingReceivedBytes = 0;
        assemblingImageB = (header.flags & CJPG_FLAG_IMAGE_B) != 0; // 이미지 유형(A/B) 플래그 저장

        log("Start CJPG image assembly. imageId=" + assemblingImageId
                + ", image=" + (assemblingImageB ? "b_car_jpg" : "a_car_jpg")
                + ", totalLen=" + assemblingTotalLen
                + ", fragCount=" + assemblingFragCount
                + ", firstFlag=" + ((header.flags & CJPG_FLAG_FIRST) != 0));
    }

    /*
     * change(add)-hyungchul-20260522-1103: 누락된 함수 주석 추가
     * 함수명: completeJpegAssemblyLocked
     * 목적 및 기능:
     * - 모든 조각의 수신이 완료되었을 때 호출되어, 해당 데이터가 실제 JPEG 형식인지 마커(SOI/EOI)를 검사하고
     * 최종적으로 리스너를 통해 UI 측에 완성된 데이터를 넘겨준다.
     *
     * 입력 변수: 없음
     * 출력 변수/리턴 값: 없음
     */
    private void completeJpegAssemblyLocked() {
        if (assemblingImage == null || assemblingTotalLen <= 0) {
            resetJpegAssemblyOnlyLocked();
            return;
        }

        // 원본 조립 버퍼의 데이터를 잘라내어 독립적인 변수에 복사 할당 (버퍼 비움을 위함)
        byte[] jpegData = Arrays.copyOf(assemblingImage, assemblingTotalLen);
        long imageId = assemblingImageId;
        boolean imageB = assemblingImageB;
        int totalLen = assemblingTotalLen;
        int fragCount = assemblingFragCount;
        int receivedBytes = assemblingReceivedBytes;

        // 실제 JPEG 포맷의 특징(시작/끝 마커)을 갖고 있는지 검증
        boolean likelyJpeg = isLikelyJpeg(jpegData);
        log("Complete CJPG image. imageId=" + imageId
                + ", image=" + (imageB ? "b_car_jpg" : "a_car_jpg")
                + ", totalLen=" + totalLen
                + ", receivedBytes=" + receivedBytes
                + ", fragCount=" + fragCount
                + ", likelyJpeg=" + likelyJpeg);

        // 다음 이미지 수신을 위해 상태 변수 초기화
        resetJpegAssemblyOnlyLocked();

        // JPEG 형식이 아니면 버리고 리스너 콜백 호출 생략
        if (!likelyJpeg) {
            log("Drop completed data because JPEG SOI/EOI marker check failed.");
            return;
        }

        // 최종 완성된 데이터를 콜백 전달
        if (jpegImageListener != null) {
            jpegImageListener.onJpegImageReceived(jpegData, imageId, imageB, totalLen, fragCount);
        }
    }

    /*
     * change(add)-hyungchul-20260522-1103: 누락된 함수 주석 추가
     * 함수명: isLikelyJpeg
     * 목적 및 기능:
     * - 전달받은 바이트 배열이 표준 JPEG 파일 구조를 띄는지, 시작 마커(SOI: 0xFFD8)와 종료 마커(EOI: 0xFFD9)로 확인한다.
     *
     * 입력 변수:
     * - data: 검사할 파일 바이트 배열
     *
     * 출력 변수/리턴 값:
     * - boolean: true(JPEG 파일로 유력함), false(형식 불일치)
     */
    private boolean isLikelyJpeg(byte[] data) {
        if (data == null || data.length < 4) {
            return false;
        }

        // 파일의 앞 2바이트가 0xFF, 0xD8 이고 끝 2바이트가 0xFF, 0xD9 인지 비트 연산으로 체크
        return (data[0] & 0xFF) == 0xFF
                && (data[1] & 0xFF) == 0xD8
                && (data[data.length - 2] & 0xFF) == 0xFF
                && (data[data.length - 1] & 0xFF) == 0xD9;
    }

    /*
     * change(add)-hyungchul-20260522-1103: 누락된 함수 주석 추가
     * 함수명: resetReceiveStateLocked
     * 목적 및 기능:
     * - L2CAP 스트림 버퍼와 JPEG 이미지 조립 관련 메모리를 모두 초기화하여 수신 상태를 원점으로 되돌린다.
     *
     * 입력 변수: 없음
     * 출력 변수/리턴 값: 없음
     */
    private void resetReceiveStateLocked() {
        streamPending = new byte[0]; // 스트림 대기 버퍼 비우기
        resetJpegAssemblyOnlyLocked();
    }

    /*
     * change(add)-hyungchul-20260522-1103: 누락된 함수 주석 추가
     * 함수명: resetJpegAssemblyOnlyLocked
     * 목적 및 기능:
     * - 현재 진행 중이던 단일 이미지 조립 상태 관련 변수 및 버퍼만을 초기화한다.
     *
     * 입력 변수: 없음
     * 출력 변수/리턴 값: 없음
     */
    private void resetJpegAssemblyOnlyLocked() {
        assemblingImageId = -1L;
        assemblingTotalLen = 0;
        assemblingFragCount = 0;
        assemblingImage = null;
        assemblingFragReceived = null;
        assemblingReceivedFragCount = 0;
        assemblingReceivedBytes = 0;
        assemblingImageB = false;
    }

    /*
     * change(add)-hyungchul-20260522-1103: 누락된 함수 주석 추가
     * 함수명: safeDeviceName
     * 목적 및 기능:
     * - 안드로이드 12 이상의 디바이스 이름 획득 시 발생할 수 있는 권한 문제(SecurityException)를 방지하며 안전하게 이름을 읽어온다.
     *
     * 입력 변수:
     * - device: 조회 대상 기기
     *
     * 출력 변수/리턴 값:
     * - String: 획득한 기기 이름 혹은 실패 시 null 반환
     */
    @SuppressLint("MissingPermission")
    private String safeDeviceName(BluetoothDevice device) {
        if (device == null) {
            return null;
        }

        if (!hasConnectPermission()) {
            return null;
        }

        try {
            return device.getName();
        } catch (SecurityException e) {
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /*
     * change(add)-hyungchul-20260522-1103: 누락된 함수 주석 추가
     * 함수명: safeAddress
     * 목적 및 기능:
     * - 안드로이드 12 이상의 기기 MAC 주소 획득 시 SecurityException을 피하며 안전하게 읽어온다.
     *
     * 입력 변수:
     * - device: 조회 대상 기기
     *
     * 출력 변수/리턴 값:
     * - String: 획득한 MAC 주소 문자열 혹은 권한/에러 상태를 표현하는 문자열 반환
     */
    @SuppressLint("MissingPermission")
    private String safeAddress(BluetoothDevice device) {
        if (device == null) {
            return "null";
        }

        if (!hasConnectPermission()) {
            return "permission-denied";
        }

        try {
            return device.getAddress();
        } catch (SecurityException e) {
            return "security-exception";
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    /*
     * 함수명: hasScanPermission
     * 목적 및 기능:
     * - BLE scan permission이 있는지 확인한다.
     * - 현재 vendor Bluetooth stack은 Android 12 이상에서도 ACCESS_FINE_LOCATION을 요구하므로 FINE location을 필수로 확인한다.
     *
     * 입력 변수/출력 변수:
     * - 없음
     *
     * 리턴 값:
     * - boolean: true(scan 가능 permission 있음), false(scan 가능 permission 없음)
     */
    private boolean hasScanPermission() {
        if (!hasFineLocationPermission()) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
        }

        return true;
    }

    /*
     * change(add)-hyungchul-20260522-1103: 누락된 함수 주석 추가
     * 함수명: hasFineLocationPermission
     * 목적 및 기능:
     * - 앱이 ACCESS_FINE_LOCATION(정밀 위치) 권한을 가지고 있는지 검사한다.
     *
     * 입력 변수: 없음
     * 출력 변수/리턴 값:
     * - boolean: true(권한 있음), false(권한 없음)
     */
    private boolean hasFineLocationPermission() {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /*
     * 함수명: isLocationServiceEnabled
     * 목적 및 기능:
     * - Android Location service가 켜져 있는지 확인한다.
     *
     * 입력 변수/출력 변수:
     * - 없음
     *
     * 리턴 값:
     * - boolean: true(Location ON), false(Location OFF)
     */
    private boolean isLocationServiceEnabled() {
        LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return locationManager.isLocationEnabled();
        }

        boolean gpsEnabled = false;
        boolean networkEnabled = false;

        try {
            gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Throwable ignored) {
        }

        try {
            networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Throwable ignored) {
        }

        return gpsEnabled || networkEnabled;
    }

    /*
     * 함수명: hasConnectPermission
     * 목적 및 기능:
     * - Android version별 connect permission이 있는지 확인한다.
     *
     * 입력 변수/출력 변수:
     * - 없음
     *
     * 리턴 값:
     * - boolean: true(connect permission 있음), false(connect permission 없음)
     */
    private boolean hasConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }

        return true;
    }

    /*
     * change(add)-hyungchul-20260522-1103: 누락된 함수 주석 추가
     * 함수명: log
     * 목적 및 기능:
     * - Android 시스템의 Logcat에 로그를 남기고, UI 출력을 위해 콜백 인터페이스인 Logger를 호출하여 로그 메시지를 전달한다.
     *
     * 입력 변수:
     * - message: 출력할 내용이 담긴 문자열
     *
     * 출력 변수/리턴 값: 없음
     */
    private void log(String message) {
        Log.i(TAG, message);

        if (logger != null) {
            logger.log(message);
        }
    }

    /*
     * change(add)-hyungchul-20260522-1103: 클래스 및 메서드 주석 추가
     * 구조체 클래스명: CjpgHeader
     * 목적 및 기능: CJPG 프로토콜의 24바이트 헤더 속성을 자바 객체 형태로 매핑하기 위한 정적 내부 클래스
     */
    private static final class CjpgHeader {
        private int magic;        // 'C' 'J' 'P' 'G' 매직 시그니처 (4바이트)
        private long imageId;     // 이미지 고유 식별 번호 (4바이트, unsigned)
        private long totalLen;    // 완성될 이미지 전체의 바이트 길이 (4바이트, unsigned)
        private long offset;      // 현재 전송되는 청크 데이터의 원본 이미지 내 시작 오프셋 (4바이트, unsigned)
        private int chunkLen;     // 현재 헤더에 이어서 전송된 청크 페이로드의 바이트 길이 (2바이트, unsigned)
        private int fragIdx;      // 전체 분할 프레임 중 현재 프레임의 인덱스 번호 (2바이트, unsigned)
        private int fragCount;    // 하나의 이미지를 구성하기 위한 전체 프레임(조각) 개수 (2바이트, unsigned)
        private int flags;        // 비트 단위 플래그(첫 패킷, 마지막 패킷, 이미지 A/B 여부 등) (2바이트, unsigned)

        /*
         * 함수명: summary
         * 목적 및 기능:
         * - 디버깅 및 로그 출력을 위해 파싱된 헤더의 주요 정보들을 단일 문자열로 포맷팅한다.
         *
         * 입력 변수: 없음
         * 출력 변수/리턴 값:
         * - String: 헤더 정보가 요약된 문자열
         */
        private String summary() {
            return "magic=0x" + Integer.toHexString(magic)
                    + ", imageId=" + imageId
                    + ", totalLen=" + totalLen
                    + ", offset=" + offset
                    + ", chunkLen=" + chunkLen
                    + ", fragIdx=" + fragIdx
                    + ", fragCount=" + fragCount
                    + ", flags=0x" + Integer.toHexString(flags);
        }
    }
}