package com.hapticasensorics.phonecapturekiosk;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ActivityNotFoundException;
import android.content.IntentFilter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;
import android.provider.Settings;
import android.util.Log;
import android.util.Size;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String TAG = "PhonecaptureKiosk";
    private static final int REQUEST_CAMERA_PERMISSION = 1001;
    private static final String EXTRA_COMMAND = "command";
    private static final String EXTRA_FRAMES = "frames";
    private static final String COMMAND_REFRESH = "refresh";
    private static final String COMMAND_CAPTURE_FRAME = "capture_frame";
    private static final String COMMAND_CAPTURE_BURST = "capture_burst";
    private static final String COMMAND_OPEN_UVC_PROBE = "open_uvc_probe";
    private static final String COMMAND_START_UVC_SERVICE = "start_uvc_service";
    private static final String COMMAND_STOP_UVC_SERVICE = "stop_uvc_service";
    private static final String COMMAND_REFRESH_UVC_SERVICE = "refresh_uvc_service";
    private static final String COMMAND_CAPTURE_UVC_IMAGE = "capture_uvc_image";
    private static final String COMMAND_CAPTURE_UVC_VIDEO = "capture_uvc_video";
    private static final String COMMAND_REQUEST_BATTERY_EXEMPTION = "request_battery_exemption";
    private static final long UI_REFRESH_INTERVAL_MS = 1500L;
    private static final long HEALTH_PREVIEW_STALE_MS = 5000L;

    private TextView summaryView;
    private TextView detailView;
    private TextView footerView;
    private TextView previewOverlayView;
    private ImageView previewImageView;
    private Button networkButton;
    private Button airplaneModeButton;
    private DevicePolicyManager devicePolicyManager;
    private ComponentName adminComponent;
    private ExternalCaptureController captureController;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean applianceLockTaskDesired = true;
    private boolean relockAfterExternalSettings;
    private final Runnable operatorRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshStatusAndPersist();
            uiHandler.postDelayed(this, UI_REFRESH_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        devicePolicyManager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, PhonecaptureDeviceAdminReceiver.class);
        captureController = new ExternalCaptureController(this);

        summaryView = findViewById(R.id.summary_text);
        detailView = findViewById(R.id.detail_text);
        footerView = findViewById(R.id.footer_text);
        previewOverlayView = findViewById(R.id.preview_overlay_text);
        previewImageView = findViewById(R.id.preview_image);
        networkButton = findViewById(R.id.network_button);
        airplaneModeButton = findViewById(R.id.airplane_mode_button);

        networkButton.setOnClickListener(v -> openNetworkSettings());
        airplaneModeButton.setOnClickListener(v -> openAirplaneModeSettings());

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        } else {
            refreshStatusAndPersist();
        }

        handleCommandIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleCommandIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (relockAfterExternalSettings) {
            applianceLockTaskDesired = true;
            relockAfterExternalSettings = false;
        }
        uiHandler.removeCallbacks(operatorRefreshRunnable);
        refreshStatusAndPersist();
        uiHandler.postDelayed(operatorRefreshRunnable, UI_REFRESH_INTERVAL_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(operatorRefreshRunnable);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            refreshStatusAndPersist();
            handleCommandIntent(getIntent());
        }
    }

    private void handleCommandIntent(Intent intent) {
        if (intent == null) {
            return;
        }

        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            handleUsbAttachIntent(intent);
        }

        String command = intent.getStringExtra(EXTRA_COMMAND);
        if (command == null || command.isEmpty()) {
            return;
        }

        Log.i(TAG, "Received command: " + command);
        if (COMMAND_REFRESH.equals(command)) {
            refreshStatusAndPersist();
        } else if (COMMAND_START_UVC_SERVICE.equals(command)) {
            startUvcService();
            refreshStatusAndPersist();
        } else if (COMMAND_STOP_UVC_SERVICE.equals(command)) {
            stopUvcService();
            refreshStatusAndPersist();
        } else if (COMMAND_REFRESH_UVC_SERVICE.equals(command)) {
            sendUvcServiceAction(UvcCaptureService.ACTION_REFRESH);
            refreshStatusAndPersist();
        } else if (COMMAND_CAPTURE_UVC_IMAGE.equals(command)) {
            sendUvcServiceAction(UvcCaptureService.ACTION_CAPTURE_IMAGE);
            refreshStatusAndPersist();
        } else if (COMMAND_CAPTURE_UVC_VIDEO.equals(command)) {
            sendUvcServiceAction(UvcCaptureService.ACTION_CAPTURE_VIDEO);
            refreshStatusAndPersist();
        } else if (COMMAND_REQUEST_BATTERY_EXEMPTION.equals(command)) {
            requestBatteryOptimizationExemption();
            refreshStatusAndPersist();
        } else if (COMMAND_OPEN_UVC_PROBE.equals(command)) {
            openUvcProbe();
        } else if (COMMAND_CAPTURE_FRAME.equals(command)) {
            runCaptureCommand(1);
        } else if (COMMAND_CAPTURE_BURST.equals(command)) {
            int frames = intent.getIntExtra(EXTRA_FRAMES, 30);
            runCaptureCommand(Math.max(frames, 1));
        }

        intent.removeExtra(EXTRA_COMMAND);
        intent.removeExtra(EXTRA_FRAMES);
    }

    private void handleUsbAttachIntent(Intent intent) {
        UsbDevice device;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        } else {
            device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        }
        if (!isSupportedCaptureDevice(device)) {
            return;
        }
        Log.i(TAG, "Handling USB_DEVICE_ATTACHED in MainActivity for " + device.getDeviceName());
        startUvcService();
        sendUvcServiceAction(UvcCaptureService.ACTION_REFRESH);
        refreshStatusAndPersist();
    }

    private void openUvcProbe() {
        Intent intent = new Intent(this, UvcProbeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private void requestDeviceAdmin() {
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
        intent.putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.device_admin_explanation)
        );
        startActivity(intent);
    }

    private void applyApplianceModeIfPossible() {
        try {
            if (devicePolicyManager == null || adminComponent == null) {
                return;
            }
            if (!devicePolicyManager.isDeviceOwnerApp(getPackageName())) {
                return;
            }
            devicePolicyManager.setLockTaskPackages(adminComponent, new String[]{getPackageName()});

            IntentFilter homeFilter = new IntentFilter(Intent.ACTION_MAIN);
            homeFilter.addCategory(Intent.CATEGORY_HOME);
            homeFilter.addCategory(Intent.CATEGORY_DEFAULT);
            devicePolicyManager.addPersistentPreferredActivity(
                    adminComponent,
                    homeFilter,
                    new ComponentName(this, MainActivity.class)
            );

            if (!devicePolicyManager.isLockTaskPermitted(getPackageName())) {
                return;
            }
            if (applianceLockTaskDesired) {
                if (!isInLockTaskMode()) {
                    startLockTask();
                }
            } else if (isInLockTaskMode()) {
                stopLockTask();
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Appliance mode setup denied", e);
        } catch (Exception e) {
            Log.w(TAG, "Appliance mode setup failed", e);
        }
    }

    private boolean isInLockTaskMode() {
        ActivityManager activityManager = getSystemService(ActivityManager.class);
        if (activityManager == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return activityManager.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
        }
        return activityManager.isInLockTaskMode();
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || isIgnoringBatteryOptimizations()) {
            return;
        }
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void openNetworkSettings() {
        prepareTransferMode();
        relockAfterExternalSettings = true;
        applianceLockTaskDesired = false;
        applyApplianceModeIfPossible();

        Intent intent = new Intent(isAirplaneModeOn()
                ? Settings.ACTION_AIRPLANE_MODE_SETTINGS
                : Settings.ACTION_WIFI_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Intent fallback = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(fallback);
        }
    }

    private void prepareTransferMode() {
        startUvcService();
        sendUvcServiceAction(UvcCaptureService.ACTION_REFRESH);
        refreshStatusAndPersist();
    }

    private void openAirplaneModeSettings() {
        relockAfterExternalSettings = true;
        applianceLockTaskDesired = false;
        applyApplianceModeIfPossible();

        Intent intent = new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Intent fallback = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(fallback);
        }
    }

    private void startUvcService() {
        Intent intent = UvcCaptureService.buildServiceIntent(this, UvcCaptureService.ACTION_START);
        startUvcServiceIntent(intent);
    }

    private void sendUvcServiceAction(String action) {
        Intent intent = UvcCaptureService.buildServiceIntent(this, action);
        startUvcServiceIntent(intent);
    }

    private void startUvcServiceIntent(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void stopUvcService() {
        startService(UvcCaptureService.buildServiceIntent(this, UvcCaptureService.ACTION_STOP));
    }

    private void runCaptureCommand(int requestedFrames) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            summaryView.setText("Needs attention");
            if (detailView != null) {
                detailView.setText("Camera permission required");
            }
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
            return;
        }

        summaryView.setText("Working");
        if (detailView != null) {
            detailView.setText("Capturing " + requestedFrames + " frame(s)");
        }
        captureController.captureFrames(requestedFrames, 4, new ExternalCaptureController.Callback() {
            @Override
            public void onSuccess(ExternalCaptureController.CaptureReport report) {
                runOnUiThread(() -> {
                    summaryView.setText("Healthy");
                    if (detailView != null) {
                        detailView.setText("Saved " + report.savedFrames + " frame(s)");
                    }
                    if (footerView != null) {
                        footerView.setText("Latest capture: " + report.captureDirectory.getName());
                    }
                    writeLastCapturePointer(report);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    summaryView.setText("Needs attention");
                    if (detailView != null) {
                        detailView.setText("Capture failed");
                    }
                    if (footerView != null) {
                        footerView.setText(message);
                    }
                    Log.e(TAG, message);
                });
            }
        });
    }

    private void refreshStatusAndPersist() {
        try {
            requestOperatorPreview();
            JSONObject status = buildStatusJson();
            writeJson(new File(getDiagnosticsDirectory(), "status.json"), status);
            renderOperatorStatus(status);
        } catch (JSONException | IOException e) {
            summaryView.setText("Needs attention");
            if (detailView != null) {
                detailView.setText("Status refresh failed");
            }
            if (footerView != null) {
                footerView.setText(e.getMessage());
            }
            Log.e(TAG, "Failed to refresh status", e);
        }
    }

    private JSONObject buildStatusJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("timestamp", isoTimestamp());
        root.put("packageName", getPackageName());
        root.put("sdkInt", Build.VERSION.SDK_INT);
        root.put("deviceAdminActive", devicePolicyManager.isAdminActive(adminComponent));
        root.put("deviceOwner", devicePolicyManager.isDeviceOwnerApp(getPackageName()));
        root.put("lockTaskPermitted", devicePolicyManager.isLockTaskPermitted(getPackageName()));
        root.put(
                "cameraPermissionGranted",
                checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        );
        root.put("ignoringBatteryOptimizations", isIgnoringBatteryOptimizations());
        root.put("battery", buildBatteryJson());
        root.put("storage", buildStorageJson());
        root.put("saveLocations", buildSaveLocationsJson());
        root.put("mediaRoot", getMediaRoot().getAbsolutePath());
        root.put("diagnosticsDir", getDiagnosticsDirectory().getAbsolutePath());
        root.put("usbDiagnostics", buildUsbDiagnostics());
        root.put("uvcServiceStatusPath", new File(getDiagnosticsDirectory(), "uvc-status.json").getAbsolutePath());
        root.put("uvcServiceStatus", readUvcServiceStatus());

        JSONArray cameras = new JSONArray();
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                JSONObject camera = new JSONObject();
                Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                int hardwareLevel = readHardwareLevel(characteristics);
                camera.put("cameraId", cameraId);
                camera.put("external", lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL);
                camera.put("lensFacing", readLensFacing(lensFacing));
                camera.put("hardwareLevel", readHardwareLevelName(hardwareLevel));

                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                camera.put("yuvSizes", sizeArray(map == null ? null : map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)));
                camera.put("jpegSizes", sizeArray(map == null ? null : map.getOutputSizes(android.graphics.ImageFormat.JPEG)));
                cameras.put(camera);
            }
        } catch (CameraAccessException e) {
            root.put("cameraAccessError", e.getMessage());
        } catch (SecurityException e) {
            root.put("cameraAccessError", "permission denied");
        }

        root.put("cameras", cameras);
        return root;
    }

    private JSONObject buildBatteryJson() throws JSONException {
        JSONObject root = new JSONObject();
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) {
            return root;
        }
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        root.put("levelPercent", level >= 0 && scale > 0 ? Math.round((level * 100f) / scale) : JSONObject.NULL);
        root.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL);
        return root;
    }

    private JSONObject buildStorageJson() throws JSONException {
        JSONObject root = new JSONObject();
        StatFs statFs = new StatFs(getMediaRoot().getAbsolutePath());
        root.put("totalBytes", statFs.getTotalBytes());
        root.put("freeBytes", statFs.getAvailableBytes());
        return root;
    }

    private JSONObject buildSaveLocationsJson() throws JSONException {
        JSONObject root = new JSONObject();
        File mediaRoot = getMediaRoot();
        root.put("mediaRoot", mediaRoot.getAbsolutePath());
        root.put("capturesDir", new File(mediaRoot, "uvc-captures").getAbsolutePath());
        root.put("videosDir", new File(mediaRoot, "uvc-videos").getAbsolutePath());
        root.put("diagnosticsDir", getDiagnosticsDirectory().getAbsolutePath());
        root.put("healthPreviewPath", new File(getDiagnosticsDirectory(), "health-preview.jpg").getAbsolutePath());
        return root;
    }

    private void renderOperatorStatus(JSONObject status) throws JSONException {
        JSONObject service = status.optJSONObject("uvcServiceStatus");
        JSONObject storage = status.optJSONObject("storage");
        JSONObject battery = status.optJSONObject("battery");
        JSONObject saveLocations = status.optJSONObject("saveLocations");

        String overall = "Starting";
        String sourceState = "unknown";
        String detailState = "Waiting for service";
        String latestCapture = "";
        String latestVideo = "";

        if (service != null) {
            int deviceCount = service.optInt("deviceCount", 0);
            boolean cameraOpened = service.optBoolean("cameraOpened", false);
            boolean recording = service.optBoolean("recordingInFlight", false);
            boolean hasUsbPermission = service.optBoolean("hasPermission", false);
            boolean permissionRequestInFlight = service.optBoolean("permissionRequestInFlight", false);
            boolean openInFlight = service.optBoolean("openInFlight", false);
            boolean policyWantsRecording = service.optBoolean("policyWantsRecording", false);
            String runtimeMode = service.optString("runtimeMode", "unknown");
            String lastStatusMessage = service.optString("lastStatusMessage", "");
            sourceState = service.optJSONObject("signalClassification") == null
                    ? "unknown"
                    : service.optJSONObject("signalClassification").optString("label", "unknown");
            latestCapture = service.optString("lastCapturePath", "");
            latestVideo = service.optString("lastVideoPath", "");

            boolean shouldUnlockForUsbPrompt = deviceCount > 0 && !hasUsbPermission;
            if (applianceLockTaskDesired == shouldUnlockForUsbPrompt) {
                applianceLockTaskDesired = !shouldUnlockForUsbPrompt;
            }

            if (deviceCount == 0) {
                overall = "Needs attention";
                detailState = "Replug capture dongle";
            } else if (permissionRequestInFlight || !hasUsbPermission) {
                overall = "Waiting";
                detailState = "Approve USB access";
            } else if ("REOPENING".equals(runtimeMode) || openInFlight) {
                overall = "Healthy";
                detailState = "Waking capture";
            } else if ("DEEP_IDLE".equals(runtimeMode) && !cameraOpened) {
                overall = "Healthy";
                detailState = "Waking capture";
            } else if (!cameraOpened) {
                overall = "Waiting";
                detailState = lastStatusMessage.isEmpty() ? "Opening capture" : lastStatusMessage;
            } else if (recording) {
                overall = "Healthy";
                detailState = "Recording now";
            } else if (policyWantsRecording && "live".equals(sourceState)) {
                overall = "Healthy";
                detailState = "Live source - preparing clip";
            } else if ("no_signal_colorbars".equals(sourceState)) {
                overall = "Waiting";
                detailState = "No source video";
            } else if ("blank_dark".equals(sourceState)) {
                overall = "Waiting";
                detailState = "Source sleeping/black";
            } else if ("stale_static".equals(sourceState)) {
                overall = "Healthy";
                detailState = "Ready";
            } else {
                overall = "Healthy";
                detailState = "Source active";
            }
        }

        String batteryText = battery == null ? "Battery unknown"
                : battery.optInt("levelPercent", -1) + "%"
                + (battery.optBoolean("charging", false) ? " charging" : "");
        String storageText = storage == null ? "Storage unknown"
                : humanBytes(storage.optLong("freeBytes", 0L))
                + " free";

        summaryView.setText(overall);
        if (detailView != null) {
            detailView.setText(detailState + "  •  " + batteryText + "  •  " + storageText);
        }

        if (previewOverlayView != null) {
            previewOverlayView.setVisibility(View.VISIBLE);
        }

        if (footerView != null) {
            StringBuilder footer = new StringBuilder();
            footer.append("Files via USB: Android/media/.../phonecapturekiosk");
            String latestName = !latestVideo.isEmpty()
                    ? basenameOrFallback(latestVideo, "")
                    : basenameOrFallback(latestCapture, "");
            String transferUrl = extractTransferBaseUrl(service);
            if (!transferUrl.isEmpty()) {
                footer.append("\nTransfer: ").append(transferUrl);
                footer.append("\nTurn Wi-Fi off when done");
            }
            if (!latestName.isEmpty()) {
                footer.append("\nLatest: ").append(latestName);
            }
            footerView.setText(footer.toString().trim());
        }

        updateActionButtons();

        applyApplianceModeIfPossible();
        loadHealthPreviewImage(
                saveLocations == null ? null : saveLocations.optString("healthPreviewPath", null),
                service
        );
    }

    private void requestOperatorPreview() {
        sendUvcServiceAction(UvcCaptureService.ACTION_CAPTURE_HEALTH_PREVIEW);
    }

    private void loadHealthPreviewImage(String path, JSONObject serviceStatus) {
        if (previewImageView == null) {
            return;
        }
        String overlayMessage = buildPreviewOverlayMessage(serviceStatus);
        if (path == null || path.isEmpty()) {
            previewImageView.setImageDrawable(null);
            previewImageView.setAlpha(1.0f);
            applyPreviewOverlay(overlayMessage);
            return;
        }
        File file = new File(path);
        if (!file.exists()) {
            previewImageView.setImageDrawable(null);
            previewImageView.setAlpha(1.0f);
            applyPreviewOverlay(overlayMessage);
            return;
        }
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (bitmap != null) {
            previewImageView.setImageBitmap(bitmap);
        }
        long ageMs = Math.max(0L, System.currentTimeMillis() - file.lastModified());
        boolean stale = ageMs > HEALTH_PREVIEW_STALE_MS;
        previewImageView.setAlpha(stale ? 0.55f : 1.0f);
        String ageText = "Preview " + humanAge(ageMs);
        if (stale) {
            ageText += " old";
        }
        applyPreviewOverlay(overlayMessage + "\n" + ageText);
    }

    private String buildPreviewOverlayMessage(JSONObject serviceStatus) {
        if (serviceStatus == null) {
            return "Waiting for capture service";
        }
        boolean hasUsbPermission = serviceStatus.optBoolean("hasPermission", false);
        boolean permissionRequestInFlight = serviceStatus.optBoolean("permissionRequestInFlight", false);
        boolean openInFlight = serviceStatus.optBoolean("openInFlight", false);
        boolean cameraOpened = serviceStatus.optBoolean("cameraOpened", false);
        boolean recording = serviceStatus.optBoolean("recordingInFlight", false);
        boolean policyWantsRecording = serviceStatus.optBoolean("policyWantsRecording", false);
        int deviceCount = serviceStatus.optInt("deviceCount", 0);
        String runtimeMode = serviceStatus.optString("runtimeMode", "unknown");
        String sourceState = serviceStatus.optJSONObject("signalClassification") == null
                ? "unknown"
                : serviceStatus.optJSONObject("signalClassification").optString("label", "unknown");

        if (deviceCount == 0) {
            return "Dongle missing";
        }
        if (permissionRequestInFlight || !hasUsbPermission) {
            return "Waiting for USB approval";
        }
        if ("REOPENING".equals(runtimeMode) || openInFlight || ("DEEP_IDLE".equals(runtimeMode) && !cameraOpened)) {
            return "Refreshing live preview";
        }
        if (recording) {
            return "Recording clip";
        }
        if (policyWantsRecording && "live".equals(sourceState)) {
            return "Live source detected";
        }
        if ("no_signal_colorbars".equals(sourceState)) {
            return "No source video";
        }
        if ("blank_dark".equals(sourceState)) {
            return "Source dark or sleeping";
        }
        if ("stale_static".equals(sourceState)) {
            return "Static source";
        }
        if ("live".equals(sourceState)) {
            return "Watching live source";
        }
        return "Waiting for signal";
    }

    private void applyPreviewOverlay(String message) {
        if (previewOverlayView == null) {
            return;
        }
        previewOverlayView.setText(message);
        previewOverlayView.setVisibility(View.VISIBLE);
    }

    private String humanAge(long ageMs) {
        if (ageMs < 1000L) {
            return "just now";
        }
        long ageSeconds = ageMs / 1000L;
        if (ageSeconds < 60L) {
            return ageSeconds + "s";
        }
        long ageMinutes = ageSeconds / 60L;
        if (ageMinutes < 60L) {
            return ageMinutes + "m";
        }
        long ageHours = ageMinutes / 60L;
        return ageHours + "h";
    }

    private String humanBytes(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit += 1;
        }
        return String.format(Locale.US, unit == 0 ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }

    private String basenameOrFallback(String path, String fallback) {
        if (path == null || path.isEmpty()) {
            return fallback;
        }
        return new File(path).getName();
    }

    private String extractTransferBaseUrl(JSONObject service) {
        if (service == null) {
            return "";
        }
        JSONObject transferServer = service.optJSONObject("transferServer");
        if (transferServer == null || !transferServer.optBoolean("running", false)) {
            return "";
        }
        JSONArray baseUrls = transferServer.optJSONArray("baseUrls");
        if (baseUrls == null || baseUrls.length() == 0) {
            return "";
        }
        return baseUrls.optString(0, "");
    }

    private void updateActionButtons() {
        if (networkButton != null) {
            boolean transferReady = !isAirplaneModeOn();
            networkButton.setEnabled(true);
            networkButton.setActivated(transferReady);
            networkButton.setSelected(transferReady);
            networkButton.setAlpha(transferReady ? 1.0f : 0.75f);
            networkButton.setText(transferReady
                    ? R.string.transfer_mode_ready
                    : R.string.transfer_mode_setup);
        }
        if (airplaneModeButton != null) {
            boolean enabled = isAirplaneModeOn();
            airplaneModeButton.setEnabled(true);
            airplaneModeButton.setActivated(enabled);
            airplaneModeButton.setSelected(enabled);
            airplaneModeButton.setAlpha(enabled ? 1.0f : 0.75f);
            airplaneModeButton.setText(enabled
                    ? R.string.airplane_mode_on
                    : R.string.airplane_mode_off);
        }
    }

    private boolean isAirplaneModeOn() {
        try {
            return Settings.Global.getInt(getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
        } catch (Exception e) {
            Log.w(TAG, "Failed to read airplane mode state", e);
            return false;
        }
    }

    private Object readUvcServiceStatus() {
        File target = new File(getDiagnosticsDirectory(), "uvc-status.json");
        if (!target.exists()) {
            return JSONObject.NULL;
        }
        String raw = readTextSafe(target);
        try {
            return new JSONObject(raw);
        } catch (JSONException ignored) {
            return raw;
        }
    }

    private JSONObject buildUsbDiagnostics() throws JSONException {
        JSONObject root = new JSONObject();
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        JSONArray devices = new JSONArray();
        boolean foundSupportedCapture = false;

        for (UsbDevice device : usbManager.getDeviceList().values()) {
            JSONObject entry = new JSONObject();
            entry.put("deviceName", device.getDeviceName());
            entry.put("vendorId", device.getVendorId());
            entry.put("productId", device.getProductId());
            entry.put("deviceClass", device.getDeviceClass());
            entry.put("deviceSubclass", device.getDeviceSubclass());
            entry.put("deviceProtocol", device.getDeviceProtocol());
            entry.put("manufacturerName", safeUsbString(() -> device.getManufacturerName()));
            entry.put("productName", safeUsbString(() -> device.getProductName()));
            entry.put("serialNumber", safeUsbString(() -> device.getSerialNumber()));
            entry.put("hasPermission", usbManager.hasPermission(device));
            entry.put("isSupportedCaptureDevice", isSupportedCaptureDevice(device));
            entry.put("interfaces", buildUsbInterfaces(device));
            devices.put(entry);

            if (isSupportedCaptureDevice(device)) {
                foundSupportedCapture = true;
            }
        }

        root.put("deviceCount", devices.length());
        root.put("foundSupportedCapture", foundSupportedCapture);
        root.put("devices", devices);
        return root;
    }

    private JSONArray buildUsbInterfaces(UsbDevice device) throws JSONException {
        JSONArray interfaces = new JSONArray();
        for (int index = 0; index < device.getInterfaceCount(); index++) {
            UsbInterface usbInterface = device.getInterface(index);
            JSONObject interfaceEntry = new JSONObject();
            interfaceEntry.put("id", usbInterface.getId());
            interfaceEntry.put("alternateSetting", usbInterface.getAlternateSetting());
            interfaceEntry.put("name", usbInterface.getName() == null ? "" : usbInterface.getName());
            interfaceEntry.put("interfaceClass", usbInterface.getInterfaceClass());
            interfaceEntry.put("interfaceSubclass", usbInterface.getInterfaceSubclass());
            interfaceEntry.put("interfaceProtocol", usbInterface.getInterfaceProtocol());

            JSONArray endpoints = new JSONArray();
            for (int endpointIndex = 0; endpointIndex < usbInterface.getEndpointCount(); endpointIndex++) {
                UsbEndpoint endpoint = usbInterface.getEndpoint(endpointIndex);
                JSONObject endpointEntry = new JSONObject();
                endpointEntry.put("address", endpoint.getAddress());
                endpointEntry.put("direction", endpoint.getDirection());
                endpointEntry.put("endpointNumber", endpoint.getEndpointNumber());
                endpointEntry.put("attributes", endpoint.getAttributes());
                endpointEntry.put("type", endpoint.getType());
                endpointEntry.put("maxPacketSize", endpoint.getMaxPacketSize());
                endpointEntry.put("interval", endpoint.getInterval());
                endpoints.put(endpointEntry);
            }

            interfaceEntry.put("endpoints", endpoints);
            interfaces.put(interfaceEntry);
        }
        return interfaces;
    }

    private boolean isSupportedCaptureDevice(UsbDevice device) {
        return (device.getVendorId() == 11145
                && device.getProductId() == 22614)
                || (device.getVendorId() == 21325
                && device.getProductId() == 8457);
    }

    private String safeUsbString(UsbStringSupplier supplier) {
        try {
            String value = supplier.get();
            return value == null ? "" : value;
        } catch (SecurityException e) {
            return "";
        }
    }

    private interface UsbStringSupplier {
        String get();
    }

    private JSONArray sizeArray(Size[] sizes) throws JSONException {
        JSONArray result = new JSONArray();
        if (sizes == null) {
            return result;
        }

        Arrays.stream(sizes)
                .sorted(Comparator.comparingInt(Size::getWidth).thenComparingInt(Size::getHeight))
                .forEach(size -> {
                    JSONObject entry = new JSONObject();
                    try {
                        entry.put("width", size.getWidth());
                        entry.put("height", size.getHeight());
                        result.put(entry);
                    } catch (JSONException ignored) {
                    }
                });
        return result;
    }

    private int readHardwareLevel(CameraCharacteristics characteristics) {
        Integer level = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        return level == null ? -1 : level;
    }

    private boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return powerManager != null && powerManager.isIgnoringBatteryOptimizations(getPackageName());
    }

    private String readLensFacing(Integer lensFacing) {
        if (lensFacing == null) {
            return "unknown";
        }
        if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            return "front";
        }
        if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
            return "back";
        }
        if (lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
            return "external";
        }
        return "unknown(" + lensFacing + ")";
    }

    private String readHardwareLevelName(int hardwareLevel) {
        if (hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
            return "LEGACY";
        }
        if (hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED) {
            return "LIMITED";
        }
        if (hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL) {
            return "FULL";
        }
        if (hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3) {
            return "LEVEL_3";
        }
        if (hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL) {
            return "EXTERNAL";
        }
        return "unknown(" + hardwareLevel + ")";
    }

    private void writeLastCapturePointer(ExternalCaptureController.CaptureReport report) {
        try {
            JSONObject pointer = new JSONObject();
            pointer.put("timestamp", isoTimestamp());
            pointer.put("captureId", report.captureId);
            pointer.put("captureDir", report.captureDirectory.getAbsolutePath());
            pointer.put("manifest", report.manifestFile.getAbsolutePath());
            pointer.put("savedFrames", report.savedFrames);
            writeJson(new File(getDiagnosticsDirectory(), "last-capture.json"), pointer);
        } catch (JSONException | IOException e) {
            Log.e(TAG, "Failed to write capture pointer", e);
        }
    }

    private File getMediaRoot() {
        File[] mediaDirs = getExternalMediaDirs();
        File base = mediaDirs != null && mediaDirs.length > 0 && mediaDirs[0] != null
                ? mediaDirs[0]
                : getExternalFilesDir(null);
        if (base == null) {
            base = getFilesDir();
        }
        if (!base.exists()) {
            base.mkdirs();
        }
        return base;
    }

    private File getDiagnosticsDirectory() {
        File directory = new File(getMediaRoot(), "diagnostics");
        if (!directory.exists()) {
            directory.mkdirs();
        }
        return directory;
    }

    private void writeJson(File file, JSONObject object) throws IOException, JSONException {
        try (FileOutputStream outputStream = new FileOutputStream(file)) {
            outputStream.write(object.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    private String readTextSafe(File file) {
        try {
            return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Failed to read " + file.getAbsolutePath() + ": " + e.getMessage();
        }
    }

    private String isoTimestamp() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date());
    }
}
