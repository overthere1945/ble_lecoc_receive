package com.example.qc_ble_receive;

/*
 * 파일명: QcBleReceiveManager.java
 * 목적 및 기능:
 * - Mobile Device에서 Target Device "QC_BLE_T"를 BLE scan한다.
 * - Target Device의 GATT service(0000ff01)를 discovery하고 PSM characteristic(0000ff02)을 read한다.
 * - 읽은 PSM으로 LE L2CAP CoC client socket을 생성하고 Target Device에 connect한다.
 * - LE CoC socket InputStream에서 wm_proc이 보낸 "Hello World\n" 데이터를 수신하여 UI/logcat에 출력한다.
 * - Android 12 이상에서도 vendor Bluetooth stack이 ACCESS_FINE_LOCATION을 요구하는 경우를 대응한다.
 *
 * change(add)-hyungchul-20260513-1705: hasScanPermission()에서 ACCESS_FINE_LOCATION 필수 확인 추가.
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
import android.os.ParcelUuid;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class QcBleReceiveManager {
    public interface Logger {
        void log(String message);
    }

    private static final String TAG = "QC_BLE_RECEIVE";
    private static final String TARGET_NAME = "QC_BLE_T";

    private static final UUID SERVICE_UUID =
            UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb");

    private static final UUID PSM_CHARACTERISTIC_UUID =
            UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private final BluetoothAdapter adapter;
    private final Logger logger;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private BluetoothLeScanner scanner;
    private BluetoothDevice lastTargetDevice;
    private BluetoothGatt gatt;
    private BluetoothSocket l2capSocket;
    private volatile boolean scanning;
    private volatile boolean l2capConnected;

    public QcBleReceiveManager(Context context, BluetoothAdapter adapter, Logger logger) {
        this.context = context.getApplicationContext();
        this.adapter = adapter;
        this.logger = logger;
    }

    /*
     * 함수명: startScan
     * 목적 및 기능:
     * - QC_BLE_T 또는 SERVICE_UUID를 advertising하는 Target Device를 scan한다.
     * - ACCESS_FINE_LOCATION, BLUETOOTH_SCAN, BLUETOOTH_CONNECT, Location service 상태를 확인한다.
     * - 발견하면 scan을 중지하고 GATT 연결을 시작한다.
     *
     * 입력 변수:
     * - 없음
     *
     * 출력 변수:
     * - 없음
     *
     * 리턴 값:
     * - 없음
     */
    @SuppressLint("MissingPermission")
    public void startScan() {
        log("startScan() entered.");

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

        stopAll();

        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            log("BluetoothLeScanner is null.");
            return;
        }

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        scanning = true;

        try {
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
     *
     * 입력 변수:
     * - 없음
     *
     * 출력 변수:
     * - 없음
     *
     * 리턴 값:
     * - 없음
     */
    @SuppressLint("MissingPermission")
    public void stopAll() {
        stopScanOnly();

        l2capConnected = false;

        try {
            if (l2capSocket != null) {
                l2capSocket.close();
            }
        } catch (Throwable ignored) {
        }
        l2capSocket = null;

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

        log("All BLE/GATT/L2CAP resources stopped.");
    }

    /*
     * 함수명: connectLastDeviceWithPsm
     * 목적 및 기능:
     * - 마지막으로 발견한 Target Device에 manual PSM으로 LE CoC client 연결을 시도한다.
     *
     * 입력 변수:
     * - psm: Target Device server socket의 dynamic PSM
     *
     * 출력 변수:
     * - 없음
     *
     * 리턴 값:
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
     * 함수명: sendTestMessage
     * 목적 및 기능:
     * - 연결된 LE CoC socket으로 test payload를 송신한다.
     *
     * 입력 변수:
     * - 없음
     *
     * 출력 변수:
     * - 없음
     *
     * 리턴 값:
     * - 없음
     */
    public void sendTestMessage() {
        worker.execute(() -> {
            try {
                if (l2capSocket == null || !l2capConnected) {
                    log("sendTestMessage skipped: L2CAP socket is not connected.");
                    return;
                }

                String message = "Hello from qc_ble_receive\n";
                OutputStream outputStream = l2capSocket.getOutputStream();
                outputStream.write(message.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
                log("TX to Target over LE CoC: " + printable(message));
            } catch (Throwable t) {
                log("sendTestMessage failed: " + t);
            }
        });
    }

    /*
     * 함수명: stopScanOnly
     * 목적 및 기능:
     * - BLE scan만 중지한다.
     *
     * 입력 변수:
     * - 없음
     *
     * 출력 변수:
     * - 없음
     *
     * 리턴 값:
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
     * - result: BLE scan result
     *
     * 출력 변수:
     * - 없음
     *
     * 리턴 값:
     * - 없음
     */
    @SuppressLint("MissingPermission")
    private void handleScanResult(ScanResult result) {
        if (result == null || result.getDevice() == null) {
            return;
        }

        BluetoothDevice device = result.getDevice();
        ScanRecord record = result.getScanRecord();

        String recordName = record != null ? record.getDeviceName() : null;
        String deviceName = safeDeviceName(device);
        boolean hasServiceUuid = hasTargetServiceUuid(record);
        boolean nameMatched = TARGET_NAME.equals(recordName) || TARGET_NAME.equals(deviceName);

        if (!nameMatched && !hasServiceUuid) {
            return;
        }

        log("Target found. address=" + safeAddress(device)
                + ", recordName=" + recordName
                + ", deviceName=" + deviceName
                + ", serviceMatched=" + hasServiceUuid
                + ", rssi=" + result.getRssi());

        lastTargetDevice = device;
        stopScanOnly();
        connectGatt(device);
    }

    /*
     * 함수명: hasTargetServiceUuid
     * 목적 및 기능:
     * - scan record에 SERVICE_UUID가 포함되어 있는지 확인한다.
     *
     * 입력 변수:
     * - record: BLE scan record
     *
     * 출력 변수:
     * - 없음
     *
     * 리턴 값:
     * - true: SERVICE_UUID 포함
     * - false: SERVICE_UUID 없음
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
     * - device: Target BluetoothDevice
     *
     * 출력 변수:
     * - 없음
     *
     * 리턴 값:
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } else {
                gatt = device.connectGatt(context, false, gattCallback);
            }
        } catch (SecurityException e) {
            log("connectGatt SecurityException: " + e);
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            log("GATT state changed. status=" + status + ", newState=" + newState);

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try {
                    boolean started = gatt.discoverServices();
                    log("GATT discoverServices started=" + started);
                } catch (SecurityException e) {
                    log("discoverServices SecurityException: " + e);
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("GATT disconnected.");
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            log("GATT services discovered. status=" + status);

            if (status != BluetoothGatt.GATT_SUCCESS) {
                return;
            }

            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            if (service == null) {
                log("Target GATT service not found: " + SERVICE_UUID);
                return;
            }

            BluetoothGattCharacteristic psmCharacteristic =
                    service.getCharacteristic(PSM_CHARACTERISTIC_UUID);

            if (psmCharacteristic == null) {
                log("PSM characteristic not found: " + PSM_CHARACTERISTIC_UUID);
                return;
            }

            try {
                boolean readStarted = gatt.readCharacteristic(psmCharacteristic);
                log("Read PSM characteristic started=" + readStarted);
            } catch (SecurityException e) {
                log("readCharacteristic SecurityException: " + e);
            }
        }

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
     * 함수명: handlePsmCharacteristicValue
     * 목적 및 기능:
     * - little-endian 2 byte PSM 값을 parsing하고 LE CoC client connect를 시작한다.
     *
     * 입력 변수:
     * - uuid: characteristic UUID
     * - value: characteristic value
     * - status: GATT read status
     *
     * 출력 변수:
     * - 없음
     *
     * 리턴 값:
     * - 없음
     */
    private void handlePsmCharacteristicValue(UUID uuid, byte[] value, int status) {
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

        int psm = ByteBuffer.wrap(value)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getShort() & 0xFFFF;

        log("PSM read success. PSM=" + psm);
        connectL2capClient(lastTargetDevice, psm);
    }

    /*
     * 함수명: connectL2capClient
     * 목적 및 기능:
     * - Target Device의 dynamic PSM으로 LE L2CAP CoC client socket을 생성하고 connect한다.
     * - minSdk 26에서 API 29 lint error를 피하기 위해 reflection으로 createInsecureL2capChannel()을 호출한다.
     *
     * 입력 변수:
     * - device: Target BluetoothDevice
     * - psm: Target LE CoC server PSM
     *
     * 출력 변수:
     * - 없음
     *
     * 리턴 값:
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

        worker.execute(() -> {
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    log("LE L2CAP CoC requires Android Q/API 29+.");
                    return;
                }

                if (!hasConnectPermission()) {
                    log("connectL2capClient skipped: BLUETOOTH_CONNECT permission is not granted.");
                    return;
                }

                log("Creating LE CoC client socket by reflection. target="
                        + safeAddress(device) + ", psm=" + psm);

                Method method = BluetoothDevice.class.getMethod("createInsecureL2capChannel", int.class);
                Object socketObject = method.invoke(device, psm);

                if (!(socketObject instanceof BluetoothSocket)) {
                    log("createInsecureL2capChannel did not return BluetoothSocket.");
                    return;
                }

                l2capSocket = (BluetoothSocket) socketObject;

                log("Connecting LE CoC client socket...");
                l2capSocket.connect();

                l2capConnected = true;
                log("LE CoC client socket connected. Waiting RX data...");

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
     * - LE CoC socket InputStream에서 Target Device가 송신하는 데이터를 읽는다.
     * - UTF-8 문자열과 hex dump를 함께 출력한다.
     *
     * 입력 변수:
     * - socket: 연결된 BluetoothSocket
     *
     * 출력 변수:
     * - 없음
     *
     * 리턴 값:
     * - 없음
     */
    private void readL2capLoop(BluetoothSocket socket) {
        try {
            InputStream inputStream = socket.getInputStream();
            byte[] buffer = new byte[1024];

            while (l2capConnected) {
                int read = inputStream.read(buffer);

                if (read < 0) {
                    log("LE CoC socket EOF.");
                    break;
                }

                String text = new String(buffer, 0, read, StandardCharsets.UTF_8);
                log("RX from Target over LE CoC. len=" + read
                        + ", text=\"" + printable(text) + "\""
                        + ", hex=" + hex(buffer, read));
            }
        } catch (Throwable t) {
            log("readL2capLoop stopped: " + t);
        } finally {
            l2capConnected = false;
        }
    }

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
     * 입력 변수:
     * - 없음
     *
     * 출력 변수:
     * - 없음
     *
     * 리턴 값:
     * - true: scan 가능 permission 있음
     * - false: scan 가능 permission 없음
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
     * 함수명: hasFineLocationPermission
     * 목적 및 기능:
     * - ACCESS_FINE_LOCATION 권한이 있는지 확인한다.
     *
     * 입력 변수:
     * - 없음
     *
     * 출력 변수:
     * - 없음
     *
     * 리턴 값:
     * - true: FINE location permission 있음
     * - false: FINE location permission 없음
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
     * 입력 변수:
     * - 없음
     *
     * 출력 변수:
     * - 없음
     *
     * 리턴 값:
     * - true: Location ON
     * - false: Location OFF
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
     * 입력 변수:
     * - 없음
     *
     * 출력 변수:
     * - 없음
     *
     * 리턴 값:
     * - true: connect permission 있음
     * - false: connect permission 없음
     */
    private boolean hasConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }

        return true;
    }

    private String printable(String text) {
        if (text == null) {
            return "null";
        }

        return text.replace("\r", "\\r").replace("\n", "\\n");
    }

    private String hex(byte[] data, int len) {
        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < len; i++) {
            if (i > 0) {
                builder.append(' ');
            }

            builder.append(String.format("%02X", data[i]));
        }

        return builder.toString();
    }

    private void log(String message) {
        Log.i(TAG, message);

        if (logger != null) {
            logger.log(message);
        }
    }
}
