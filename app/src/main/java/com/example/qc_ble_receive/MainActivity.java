package com.example.qc_ble_receive;

/*
 * 파일명: MainActivity.java
 * 목적 및 기능:
 * - qc_ble_receive Mobile Client Application의 UI와 runtime permission을 처리한다.
 * - START SCAN 버튼으로 QC_BLE_T Target Device를 scan하고 LE CoC 수신을 시작한다.
 * - STOP 버튼으로 BLE scan/GATT/LE CoC 연결을 모두 끊는다.
 * - CLEAR 버튼으로 JPG viewer 화면과 수신 중인 JPG 조립 buffer를 비운다.
 * - Target Device에서 CJPG fragment header와 함께 송신하는 a_car_jpg/b_car_jpg를
 *   QcBleReceiveManager가 재조립하면 ImageView에 표시한다.
 *
 * change(add)-hyungchul-20260521-0001: Hello World 수신 UI를 JPG viewer UI로 변경.
 */

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public final class MainActivity extends Activity {
    // change(add)-hyungchul-20260522-1103: 클래스 멤버 변수 설명 주석 추가
    // 런타임 권한 요청 시 결과를 식별하기 위한 Request Code 상수
    private static final int REQ_PERMISSIONS = 1001;
    // UI의 텍스트 뷰에 표시할 최대 로그 라인 수 상수
    private static final int MAX_STATUS_LINES = 8;

    // 프로그램 상태 및 로그를 화면에 텍스트 형태로 출력하기 위한 뷰
    private TextView statusView;
    // 수신 및 조립이 완료된 JPEG 이미지를 화면에 렌더링하기 위한 이미지 뷰
    private ImageView jpgImageView;
    // BLE 스캔, GATT 연결 및 LE CoC 통신을 총괄하는 매니저 객체
    private QcBleReceiveManager manager;
    // 권한 요청 후, 권한이 승인되면 자동으로 스캔을 다시 시작할지 여부를 저장하는 플래그
    private boolean pendingStartScanAfterPermission;

    // 현재 텍스트 뷰에 표시 중인 로그 라인들을 저장하는 리스트 (최대 MAX_STATUS_LINES 유지)
    private final ArrayList<String> statusLines = new ArrayList<>();

    // QcBleReceiveManager에서 발생하는 로그 메시지를 UI 스레드에서 statusView에 추가하기 위한 콜백 리스너
    private final QcBleReceiveManager.Logger logger =
            message -> runOnUiThread(() -> appendStatus(message));

    // 완성된 JPEG 데이터를 QcBleReceiveManager로부터 전달받아 UI 스레드에서 화면에 표시하기 위한 콜백 리스너
    private final QcBleReceiveManager.JpegImageListener jpegImageListener =
            (jpegData, imageId, imageB, totalLen, fragCount) ->
                    runOnUiThread(() -> showJpegImage(jpegData, imageId, imageB, totalLen, fragCount));

    /*
     * 함수명: onCreate
     * 목적 및 기능:
     * - Activity UI를 programmatic하게 구성한다.
     * - BluetoothAdapter를 얻고 QcBleReceiveManager를 생성한다.
     * - runtime permission을 요청한다.
     *
     * 입력 변수:
     * - savedInstanceState: Android Activity restore state (이전 액티비티 상태 정보)
     *
     * 출력 변수/리턴 값:
     * - 없음
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // START SCAN 버튼 생성 및 클릭 이벤트 매핑
        Button startButton = new Button(this);
        startButton.setText("START SCAN");
        startButton.setOnClickListener(v -> startScan());

        // STOP 버튼 생성 및 클릭 이벤트 매핑 (BLE 연결 종료)
        Button stopButton = new Button(this);
        stopButton.setText("STOP");
        stopButton.setOnClickListener(v -> stopBleConnection());

        // CLEAR 버튼 생성 및 클릭 이벤트 매핑 (이미지 및 버퍼 초기화)
        Button clearButton = new Button(this);
        clearButton.setText("CLEAR");
        clearButton.setOnClickListener(v -> clearJpgViewer());

        // 상단 버튼 3개를 가로로 배치하기 위한 레이아웃 구성
        LinearLayout buttonLine = new LinearLayout(this);
        buttonLine.setOrientation(LinearLayout.HORIZONTAL);
        buttonLine.addView(startButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        buttonLine.addView(stopButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        buttonLine.addView(clearButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        // 상태 로그를 출력할 텍스트 뷰 속성 설정
        statusView = new TextView(this);
        statusView.setTextSize(12.0f);
        statusView.setTextIsSelectable(true);
        statusView.setPadding(0, 12, 0, 12);

        // 수신된 이미지를 표시할 이미지 뷰 속성 설정 (비율 유지, 배경색 지정)
        jpgImageView = new ImageView(this);
        jpgImageView.setAdjustViewBounds(true);
        jpgImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        jpgImageView.setBackgroundColor(0xFF202020);
        jpgImageView.setContentDescription("Received JPG image viewer");

        // 전체 화면을 구성하는 최상단 수직 레이아웃 조립
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 40, 24, 24);
        root.addView(buttonLine);
        root.addView(statusView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(jpgImageView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f));

        // 조립된 UI 레이아웃을 Activity 화면으로 설정
        setContentView(root);

        // 시스템 서비스로부터 BluetoothManager 및 Adapter 획득
        BluetoothManager bluetoothManager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;

        // BLE 수신 관리를 담당할 QcBleReceiveManager 인스턴스 초기화
        manager = new QcBleReceiveManager(this, adapter, logger, jpegImageListener);

        // 초기 앱 시작 로그 출력
        appendStatus("qc_ble_receive JPG viewer started.");
        appendStatus("Target: QC_BLE_T, service=0000ff01, PSM char=0000ff02.");
        appendStatus("RX protocol: CJPG header + JPG chunk, a_car_jpg/b_car_jpg alternate display.");
        logPermissionState();
        requestNeededPermissions(); // 필수 권한 요청 수행
    }

    /*
     * 함수명: onResume
     * 목적 및 기능:
     * - App Settings 또는 Location Settings에서 돌아왔을 때 permission/location 상태를 다시 확인한다.
     * - pendingStartScanAfterPermission이 true이고 모든 조건이 만족되면 scan을 자동 재시도한다.
     *
     * 입력 변수/출력 변수/리턴 값:
     * - 없음
     */
    @Override
    protected void onResume() {
        super.onResume();

        // 화면이 다시 활성화될 때 현재 권한 상태를 로그로 출력
        if (manager != null) {
            logPermissionState();
        }

        // 권한 획득 대기 상태였고, 권한과 위치 서비스가 모두 켜져있다면 자동 스캔 재개
        if (pendingStartScanAfterPermission && hasNeededPermissions() && isLocationServiceEnabled()) {
            pendingStartScanAfterPermission = false;
            appendStatus("Permissions/location are ready. Restart scan automatically.");
            manager.startScan();
        }
    }

    /*
     * 함수명: onDestroy
     * 목적 및 기능:
     * - Activity 종료 시 BLE/GATT/L2CAP resource와 worker thread를 정리한다.
     *
     * 입력 변수/출력 변수/리턴 값:
     * - 없음
     */
    @Override
    protected void onDestroy() {
        // 매니저 객체가 존재할 경우 할당된 모든 시스템 자원을 해제
        if (manager != null) {
            manager.shutdown();
            manager = null;
        }

        super.onDestroy();
    }

    /*
     * 함수명: onRequestPermissionsResult
     * 목적 및 기능:
     * - runtime permission 요청 결과를 받아 각 permission grant 상태를 출력한다.
     * - FINE location이 거부된 경우 precise location 허용 또는 App Settings 이동을 안내한다.
     * - 모든 조건이 만족되면 pending scan을 자동 시작한다.
     *
     * 입력 변수:
     * - requestCode: permission request code (요청 시 전달한 식별 코드)
     * - permissions: 요청한 permission 배열
     * - grantResults: grant 결과 배열 (PERMISSION_GRANTED 또는 PERMISSION_DENIED)
     *
     * 출력 변수/리턴 값:
     * - 없음
     */
    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // 본 앱에서 요청한 권한 응답이 아니면 무시
        if (requestCode != REQ_PERMISSIONS) {
            return;
        }

        appendStatus("onRequestPermissionsResult()");
        // 요청된 각 권한에 대한 승인/거절 여부를 로그로 기록
        for (int i = 0; i < permissions.length; i++) {
            int result = (grantResults != null && i < grantResults.length) ? grantResults[i] : PackageManager.PERMISSION_DENIED;
            appendStatus("  " + permissions[i] + " granted=" + (result == PackageManager.PERMISSION_GRANTED));
        }

        logPermissionState();

        // 정밀 위치 권한이 필수이므로 거부되었을 경우 설정 화면으로 유도
        if (!hasFineLocationPermission()) {
            appendStatus("ACCESS_FINE_LOCATION is still denied. Choose Precise location in App Settings.");
            openAppSettings();
            return;
        }

        // 안드로이드 버전에 따른 블루투스 스캔/연결 권한 미충족 시 안내
        if (!hasNeededPermissions()) {
            appendStatus("Some Bluetooth permissions are still denied.");
            return;
        }

        // 위치 서비스 자체가 꺼져 있는 경우 위치 설정 화면으로 유도
        if (!isLocationServiceEnabled()) {
            appendStatus("Location service is OFF. Open location settings.");
            openLocationSettings();
            return;
        }

        // 스캔을 대기 중이었고 매니저가 활성화 상태라면 스캔 자동 시작
        if (pendingStartScanAfterPermission && manager != null) {
            pendingStartScanAfterPermission = false;
            appendStatus("All permissions granted. Start scan automatically.");
            manager.startScan();
        }
    }

    /*
     * 함수명: startScan
     * 목적 및 기능:
     * - 필요한 Bluetooth/location permission과 Location service 상태를 확인한다.
     * - 조건이 부족하면 재요청 또는 설정 화면으로 이동한다.
     * - 조건이 충족되면 QC_BLE_T scan을 시작한다.
     *
     * 입력 변수/출력 변수/리턴 값:
     * - 없음
     */
    private void startScan() {
        appendStatus("START SCAN clicked.");
        logPermissionState();

        // 필요 권한이 없는 경우 다시 권한을 요청하고 대기 상태로 전환
        if (!hasNeededPermissions()) {
            appendStatus("Permission is not granted. Request permissions again.");
            pendingStartScanAfterPermission = true;
            requestNeededPermissions();
            return;
        }

        // 권한은 있으나 기기의 위치 서비스 기능 자체가 꺼져 있는 경우
        if (!isLocationServiceEnabled()) {
            appendStatus("Location service is OFF. BLE scan result may be blocked.");
            pendingStartScanAfterPermission = true;
            openLocationSettings();
            return;
        }

        pendingStartScanAfterPermission = false;

        // 모든 조건이 만족되면 매니저를 통해 실제 BLE 스캔 호출
        if (manager != null) {
            manager.startScan();
        }
    }

    /*
     * 함수명: stopBleConnection
     * 목적 및 기능:
     * - STOP 버튼 동작으로 scan, GATT, LE CoC socket을 모두 정리한다.
     * - 화면에 표시된 마지막 JPG 이미지는 유지한다.
     *
     * 입력 변수/출력 변수/리턴 값:
     * - 없음
     */
    private void stopBleConnection() {
        appendStatus("STOP clicked. Disconnect BLE.");
        pendingStartScanAfterPermission = false;

        // 매니저를 통해 활성화된 모든 BLE 관련 연결 및 자원을 해제
        if (manager != null) {
            manager.stopAll();
        }
    }

    /*
     * 함수명: clearJpgViewer
     * 목적 및 기능:
     * - CLEAR 버튼 동작으로 JPG viewer 이미지를 비운다.
     * - 수신 중이던 incomplete JPG 조립 buffer도 함께 초기화한다.
     * - BLE 연결은 끊지 않는다.
     *
     * 입력 변수/출력 변수/리턴 값:
     * - 없음
     */
    private void clearJpgViewer() {
        // 화면에 출력된 이미지 제거
        jpgImageView.setImageDrawable(null);

        // 백그라운드에서 수신 및 조립 중이던 내부 버퍼 데이터 폐기
        if (manager != null) {
            manager.clearReceivedImageBuffers();
        }

        appendStatus("CLEAR clicked. JPG viewer and RX buffers cleared.");
    }

    /*
     * 함수명: showJpegImage
     * 목적 및 기능:
     * - QcBleReceiveManager가 재조립한 JPEG byte array를 Bitmap으로 decode한다.
     * - decode가 성공하면 ImageView에 표시한다.
     *
     * 입력 변수:
     * - jpegData: 완성된 JPEG byte array (파일 전체 데이터)
     * - imageId: target device가 붙인 image sequence id
     * - imageB: true이면 b_car_jpg, false이면 a_car_jpg (이미지 종류 식별)
     * - totalLen: JPEG 전체 길이 (바이트 수)
     * - fragCount: 수신 fragment(청크) 수
     *
     * 출력 변수/리턴 값:
     * - 없음
     */
    private void showJpegImage(byte[] jpegData, long imageId, boolean imageB, int totalLen, int fragCount) {
        if (jpegData == null || jpegData.length == 0) {
            appendStatus("JPG decode skipped: empty data.");
            return;
        }

        // 바이트 배열을 안드로이드 Bitmap 객체로 디코딩
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
        if (bitmap == null) {
            appendStatus("JPG decode failed. imageId=" + imageId + ", len=" + jpegData.length);
            return;
        }

        // 성공적으로 디코딩된 이미지를 UI 뷰에 세팅
        jpgImageView.setImageBitmap(bitmap);

        // 표시 완료된 이미지 정보를 로그로 출력
        String imageName = imageB ? "b_car_jpg" : "a_car_jpg";
        appendStatus("Displayed " + imageName
                + ", imageId=" + imageId
                + ", bytes=" + totalLen
                + ", fragments=" + fragCount
                + ", bitmap=" + bitmap.getWidth() + "x" + bitmap.getHeight());
    }

    /*
     * 함수명: hasNeededPermissions
     * 목적 및 기능:
     * - Android version별 필요한 Bluetooth/location permission이 grant되었는지 확인한다.
     * - 현재 Bluetooth stack log가 ACCESS_FINE_LOCATION을 요구하므로 Android 12 이상에서도 FINE location을 필수로 본다.
     *
     * 입력 변수/출력 변수:
     * - 없음
     *
     * 리턴 값:
     * - boolean: true(필요한 permission이 모두 있음), false(부족함)
     */
    private boolean hasNeededPermissions() {
        // 정밀 위치 권한 검사
        if (!hasFineLocationPermission()) {
            return false;
        }

        // 안드로이드 12(API 31) 이상일 경우, 분리된 블루투스 스캔 및 연결 권한을 추가 검사
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }

        return true;
    }

    /*
     * change(add)-hyungchul-20260522-1103: 누락된 함수 주석 추가
     * 함수명: hasFineLocationPermission
     * 목적 및 기능:
     * - 앱이 ACCESS_FINE_LOCATION(정밀 위치) 권한을 획득했는지 확인한다.
     *
     * 입력 변수: 없음
     * 출력 변수/리턴 값:
     * - boolean: true(권한 있음), false(권한 없음)
     */
    private boolean hasFineLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /*
     * change(add)-hyungchul-20260522-1103: 누락된 함수 주석 추가
     * 함수명: hasCoarseLocationPermission
     * 목적 및 기능:
     * - 앱이 ACCESS_COARSE_LOCATION(대략적 위치) 권한을 획득했는지 확인한다.
     *
     * 입력 변수: 없음
     * 출력 변수/리턴 값:
     * - boolean: true(권한 있음), false(권한 없음)
     */
    private boolean hasCoarseLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /*
     * 함수명: requestNeededPermissions
     * 목적 및 기능:
     * - Android version별 필요한 runtime permission을 요청한다.
     * - Android 12 이상에서도 ACCESS_FINE_LOCATION/COARSE_LOCATION을 함께 요청한다.
     *
     * 입력 변수/출력 변수/리턴 값:
     * - 없음
     */
    private void requestNeededPermissions() {
        // 요청해야 할 권한을 담을 리스트 구성
        ArrayList<String> permissions = new ArrayList<>();

        if (!hasFineLocationPermission()) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (!hasCoarseLocationPermission()) {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }

        // 안드로이드 12 이상 기기를 위한 추가 블루투스 권한 확인 및 리스트 추가
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            }

            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }

        // 부족한 권한이 있다면 시스템 다이얼로그를 통해 런타임 요청 수행
        if (!permissions.isEmpty()) {
            appendStatus("Request permissions: " + permissions);
            requestPermissions(permissions.toArray(new String[0]), REQ_PERMISSIONS);
        } else {
            appendStatus("All required permissions already granted.");
        }
    }

    /*
     * 함수명: isLocationServiceEnabled
     * 목적 및 기능:
     * - Android device의 Location service가 켜져 있는지 확인한다.
     * - BLE scan result는 permission이 있어도 Location service OFF 상태에서 제한될 수 있다.
     *
     * 입력 변수/출력 변수:
     * - 없음
     *
     * 리턴 값:
     * - boolean: true(Location service ON), false(Location service OFF)
     */
    private boolean isLocationServiceEnabled() {
        LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null) {
            return false;
        }

        // 안드로이드 9(API 28) 이상에서는 제공된 통합 API 사용
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return locationManager.isLocationEnabled();
        }

        // 하위 버전 호환: GPS 또는 Network 프로바이더가 활성화되어 있는지 확인
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
     * change(add)-hyungchul-20260522-1103: 누락된 함수 주석 추가
     * 함수명: openLocationSettings
     * 목적 및 기능:
     * - 위치 서비스가 비활성화된 경우 사용자가 직접 켤 수 있도록 시스템 위치 설정 화면을 호출한다.
     *
     * 입력 변수: 없음
     * 출력 변수/리턴 값: 없음
     */
    private void openLocationSettings() {
        appendStatus("Open Location Settings.");
        Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        startActivity(intent);
    }

    /*
     * change(add)-hyungchul-20260522-1103: 누락된 함수 주석 추가
     * 함수명: openAppSettings
     * 목적 및 기능:
     * - 앱 권한이 영구 거절되었을 때 사용자가 직접 부여할 수 있도록 애플리케이션 상세 설정 화면을 호출한다.
     *
     * 입력 변수: 없음
     * 출력 변수/리턴 값: 없음
     */
    private void openAppSettings() {
        appendStatus("Open App Settings.");
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    /*
     * 함수명: logPermissionState
     * 목적 및 기능:
     * - 현재 runtime permission과 Location service 상태를 화면에 출력한다.
     *
     * 입력 변수/출력 변수/리턴 값:
     * - 없음
     */
    private void logPermissionState() {
        boolean fine = hasFineLocationPermission();
        boolean coarse = hasCoarseLocationPermission();
        boolean locationOn = isLocationServiceEnabled();

        appendStatus("Permission state:"
                + " FINE=" + fine
                + ", COARSE=" + coarse
                + ", LocationON=" + locationOn);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean scan = checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
            boolean connect = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
            appendStatus("Bluetooth permission state: SCAN=" + scan + ", CONNECT=" + connect);
        }
    }

    /*
     * 함수명: appendStatus
     * 목적 및 기능:
     * - 화면 statusView에 timestamp가 포함된 최근 상태를 출력한다.
     * - 너무 많은 text가 쌓이지 않도록 최근 MAX_STATUS_LINES개만 유지한다.
     *
     * 입력 변수:
     * - message: 출력할 status message (문자열)
     *
     * 출력 변수/리턴 값:
     * - 없음
     */
    private void appendStatus(String message) {
        // 현재 시간을 밀리초 단위까지 포함하여 포맷팅
        String now = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String line = "[" + now + "] " + message;

        // 최신 로그를 리스트의 맨 앞에 추가
        statusLines.add(0, line);
        // 최대 라인 수를 초과하면 가장 오래된 로그(맨 뒤)를 제거
        while (statusLines.size() > MAX_STATUS_LINES) {
            statusLines.remove(statusLines.size() - 1);
        }

        // 전체 로그를 하나의 문자열로 결합
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < statusLines.size(); i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(statusLines.get(i));
        }

        // 텍스트 뷰 갱신
        statusView.setText(builder.toString());
    }
}
