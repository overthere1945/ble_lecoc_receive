package com.example.qc_ble_receive;

/*
 * 파일명: MainActivity.java
 * 목적 및 기능:
 * - qc_ble_receive Mobile Client Application의 UI와 runtime permission을 처리한다.
 * - QC_BLE_T scan, GATT PSM read, LE CoC client connect는 QcBleReceiveManager가 수행한다.
 * - 수신/출력 log는 가장 최근 메시지가 화면 최상단에 표시되도록 prepend 방식으로 출력한다.
 * - CLEAR 버튼으로 화면의 모든 log text를 삭제한다.
 * - START SCAN 버튼으로 QC_BLE_T Target Device를 scan한다.
 * - Android 12 이상에서도 ACCESS_FINE_LOCATION 권한을 명시적으로 확인하고 없으면 재요청한다.
 * - 사용자가 approximate location만 허용한 경우 FINE location 권한이 없으므로 재요청 또는 App Settings 이동을 안내한다.
 *
 * change(add)-hyungchul-20260513-1705: ACCESS_FINE_LOCATION runtime check/re-request 및 location service ON 확인 추가.
 * change(add)-hyungchul-20260515-0001: 최신 수신 메시지 최상단 표시 및 CLEAR 버튼 기능 추가.
 */

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 1001;

    private TextView logView;
    private EditText manualPsmEditText;
    private QcBleReceiveManager manager;
    private boolean pendingStartScanAfterPermission;

    private final QcBleReceiveManager.Logger logger =
            message -> runOnUiThread(() -> appendLog(message));

    /*
     * 함수명: onCreate
     * 목적 및 기능:
     * - Activity UI를 programmatic하게 구성한다.
     * - BluetoothAdapter를 얻고 QcBleReceiveManager를 생성한다.
     * - runtime permission을 요청한다.
     *
     * 입력 변수:
     * - savedInstanceState: Android Activity restore state
     *
     * 출력 변수:
     * - 없음
     *
     * 리턴 값:
     * - 없음
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        logView = new TextView(this);
        logView.setTextSize(12.0f);
        logView.setTextIsSelectable(true);

        manualPsmEditText = new EditText(this);
        manualPsmEditText.setHint("Manual PSM, example: 128");
        manualPsmEditText.setSingleLine(true);
        manualPsmEditText.setText("");

        Button startButton = new Button(this);
        startButton.setText("START SCAN");
        startButton.setOnClickListener(v -> startScan());

        Button stopButton = new Button(this);
        stopButton.setText("STOP");
        stopButton.setOnClickListener(v -> stopAll());

        Button clearButton = new Button(this);
        clearButton.setText("CLEAR");
        clearButton.setOnClickListener(v -> clearLogs());

        Button checkPermissionButton = new Button(this);
        checkPermissionButton.setText("CHECK PERMISSION");
        checkPermissionButton.setOnClickListener(v -> {
            appendLog("CHECK PERMISSION clicked.");
            logPermissionState();
            if (!hasNeededPermissions()) {
                requestNeededPermissions();
            }
            if (!isLocationServiceEnabled()) {
                openLocationSettings();
            }
        });

        Button appSettingsButton = new Button(this);
        appSettingsButton.setText("APP SETTINGS");
        appSettingsButton.setOnClickListener(v -> openAppSettings());

        Button manualConnectButton = new Button(this);
        manualConnectButton.setText("CONNECT MANUAL PSM");
        manualConnectButton.setOnClickListener(v -> connectManualPsm());

        Button sendTestButton = new Button(this);
        sendTestButton.setText("SEND TEST");
        sendTestButton.setOnClickListener(v -> sendTest());

        LinearLayout firstButtonLine = new LinearLayout(this);
        firstButtonLine.setOrientation(LinearLayout.HORIZONTAL);
        firstButtonLine.addView(startButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        firstButtonLine.addView(stopButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        firstButtonLine.addView(clearButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        LinearLayout secondButtonLine = new LinearLayout(this);
        secondButtonLine.setOrientation(LinearLayout.HORIZONTAL);
        secondButtonLine.addView(checkPermissionButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        secondButtonLine.addView(appSettingsButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        LinearLayout thirdButtonLine = new LinearLayout(this);
        thirdButtonLine.setOrientation(LinearLayout.HORIZONTAL);
        thirdButtonLine.addView(manualConnectButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        thirdButtonLine.addView(sendTestButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(logView);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 40, 24, 24);
        root.addView(firstButtonLine);
        root.addView(secondButtonLine);
        root.addView(manualPsmEditText);
        root.addView(thirdButtonLine);
        root.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f));

        setContentView(root);

        BluetoothManager bluetoothManager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;

        manager = new QcBleReceiveManager(this, adapter, logger);

        appendLog("qc_ble_receive started.");
        appendLog("Target: QC_BLE_T, service=0000ff01, PSM char=0000ff02.");
        logPermissionState();
        requestNeededPermissions();
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

        if (manager != null) {
            logPermissionState();
        }

        if (pendingStartScanAfterPermission && hasNeededPermissions() && isLocationServiceEnabled()) {
            pendingStartScanAfterPermission = false;
            appendLog("Permissions/location are ready. Restart scan automatically.");
            manager.startScan();
        }
    }

    /*
     * 함수명: onDestroy
     * 목적 및 기능:
     * - Activity 종료 시 BLE/GATT/L2CAP resource를 정리한다.
     *
     * 입력 변수/출력 변수/리턴 값:
     * - 없음
     */
    @Override
    protected void onDestroy() {
        stopAll();
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
     * - requestCode: permission request code
     * - permissions: 요청한 permission 배열
     * - grantResults: grant 결과 배열
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

        if (requestCode != REQ_PERMISSIONS) {
            return;
        }

        appendLog("onRequestPermissionsResult()");
        for (int i = 0; i < permissions.length; i++) {
            int result = (grantResults != null && i < grantResults.length) ? grantResults[i] : PackageManager.PERMISSION_DENIED;
            appendLog("  " + permissions[i] + " granted=" + (result == PackageManager.PERMISSION_GRANTED));
        }

        logPermissionState();

        if (!hasFineLocationPermission()) {
            appendLog("ACCESS_FINE_LOCATION is still denied.");
            appendLog("Choose Precise location, not Approximate. Use APP SETTINGS if dialog does not appear.");
            return;
        }

        if (!hasNeededPermissions()) {
            appendLog("Some Bluetooth permissions are still denied.");
            return;
        }

        if (!isLocationServiceEnabled()) {
            appendLog("Location service is OFF. Open location settings.");
            openLocationSettings();
            return;
        }

        if (pendingStartScanAfterPermission) {
            pendingStartScanAfterPermission = false;
            appendLog("All permissions granted. Start scan automatically.");
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
        appendLog("START SCAN clicked.");
        logPermissionState();

        if (!hasNeededPermissions()) {
            appendLog("Permission is not granted. Request permissions again.");
            pendingStartScanAfterPermission = true;
            requestNeededPermissions();
            return;
        }

        if (!isLocationServiceEnabled()) {
            appendLog("Location service is OFF. BLE scan result may be blocked.");
            pendingStartScanAfterPermission = true;
            openLocationSettings();
            return;
        }

        pendingStartScanAfterPermission = false;
        manager.startScan();
    }

    /*
     * 함수명: stopAll
     * 목적 및 기능:
     * - scan, GATT, LE CoC socket을 모두 정리한다.
     *
     * 입력 변수/출력 변수/리턴 값:
     * - 없음
     */
    private void stopAll() {
        appendLog("STOP clicked.");

        if (manager != null) {
            manager.stopAll();
        }
    }

    /*
     * 함수명: clearLogs
     * 목적 및 기능:
     * - 화면에 표시된 모든 log text를 삭제한다.
     * - BLE 연결은 끊지 않는다.
     *
     * 입력 변수/출력 변수/리턴 값:
     * - 없음
     */
    private void clearLogs() {
        logView.setText("");
    }

    /*
     * 함수명: connectManualPsm
     * 목적 및 기능:
     * - manualPsmEditText에 입력된 PSM으로 마지막 발견 device에 LE CoC client 연결을 시도한다.
     *
     * 입력 변수/출력 변수/리턴 값:
     * - 없음
     */
    private void connectManualPsm() {
        if (!hasNeededPermissions()) {
            appendLog("Permission is not granted. Request permissions again.");
            pendingStartScanAfterPermission = false;
            requestNeededPermissions();
            return;
        }

        try {
            int psm = Integer.parseInt(manualPsmEditText.getText().toString().trim());
            appendLog("CONNECT MANUAL PSM clicked. psm=" + psm);
            manager.connectLastDeviceWithPsm(psm);
        } catch (NumberFormatException e) {
            appendLog("Invalid manual PSM. Please enter decimal PSM, example: 128.");
        }
    }

    /*
     * 함수명: sendTest
     * 목적 및 기능:
     * - 연결된 LE CoC socket으로 test payload를 전송한다.
     *
     * 입력 변수/출력 변수/리턴 값:
     * - 없음
     */
    private void sendTest() {
        appendLog("SEND TEST clicked.");
        manager.sendTestMessage();
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
     * - true: 필요한 permission이 모두 있음
     * - false: 필요한 permission이 부족함
     */
    private boolean hasNeededPermissions() {
        if (!hasFineLocationPermission()) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }

        return true;
    }

    /*
     * 함수명: hasFineLocationPermission
     * 목적 및 기능:
     * - ACCESS_FINE_LOCATION이 grant되었는지 확인한다.
     * - Android 12 이상에서도 Bluetooth stack이 FINE location을 요구하는 vendor path를 대응한다.
     *
     * 입력 변수/출력 변수:
     * - 없음
     *
     * 리턴 값:
     * - true: ACCESS_FINE_LOCATION grant됨
     * - false: ACCESS_FINE_LOCATION grant 안 됨
     */
    private boolean hasFineLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /*
     * 함수명: hasCoarseLocationPermission
     * 목적 및 기능:
     * - ACCESS_COARSE_LOCATION이 grant되었는지 확인한다.
     * - FINE이 필요하지만 permission 상태 debug를 위해 별도 확인한다.
     *
     * 입력 변수/출력 변수:
     * - 없음
     *
     * 리턴 값:
     * - true: ACCESS_COARSE_LOCATION grant됨
     * - false: ACCESS_COARSE_LOCATION grant 안 됨
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
        ArrayList<String> permissions = new ArrayList<>();

        if (!hasFineLocationPermission()) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (!hasCoarseLocationPermission()) {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            }

            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }

        if (!permissions.isEmpty()) {
            appendLog("Request permissions: " + permissions);
            requestPermissions(permissions.toArray(new String[0]), REQ_PERMISSIONS);
        } else {
            appendLog("All required permissions already granted.");
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
     * - true: Location service ON
     * - false: Location service OFF
     */
    private boolean isLocationServiceEnabled() {
        LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
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
     * 함수명: openLocationSettings
     * 목적 및 기능:
     * - Location service가 꺼져 있을 때 사용자가 켤 수 있도록 설정 화면을 연다.
     *
     * 입력 변수/출력 변수/리턴 값:
     * - 없음
     */
    private void openLocationSettings() {
        appendLog("Open Location Settings.");
        Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        startActivity(intent);
    }

    /*
     * 함수명: openAppSettings
     * 목적 및 기능:
     * - permission dialog가 다시 뜨지 않는 경우 사용자가 직접 권한을 바꿀 수 있도록 App settings를 연다.
     *
     * 입력 변수/출력 변수/리턴 값:
     * - 없음
     */
    private void openAppSettings() {
        appendLog("Open App Settings.");
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

        appendLog("Permission state:"
                + " FINE=" + fine
                + ", COARSE=" + coarse
                + ", LocationON=" + locationOn);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean scan = checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
            boolean connect = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
            appendLog("Bluetooth permission state: SCAN=" + scan + ", CONNECT=" + connect);
        }
    }

    /*
     * 함수명: appendLog
     * 목적 및 기능:
     * - 화면 logView에 timestamp가 포함된 log를 출력한다.
     *
     * 입력 변수:
     * - message: 출력할 log message
     *
     * 출력 변수/리턴 값:
     * - 없음
     */
    private void appendLog(String message) {
        String now = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String line = "[" + now + "] " + message;
        CharSequence oldText = logView.getText();

        if (oldText == null || oldText.length() == 0) {
            logView.setText(line);
        } else {
            logView.setText(line + "\n" + oldText);
        }
    }
}
