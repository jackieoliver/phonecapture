package com.hapticasensorics.phonecapturekiosk;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.jiangdg.ausbc.MultiCameraClient;
import com.jiangdg.ausbc.callback.ICameraStateCallBack;
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack;
import com.jiangdg.ausbc.callback.IPreviewDataCallBack;
import com.jiangdg.ausbc.camera.bean.CameraRequest;
import com.jiangdg.ausbc.camera.bean.PreviewSize;
import com.jiangdg.ausbc.widget.AspectRatioTextureView;
import com.serenegiant.usb.USBMonitor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class UvcProbeActivity extends Activity
        implements TextureView.SurfaceTextureListener, IDeviceConnectCallBack, ICameraStateCallBack {
    private static final String TAG = "PhonecaptureUvc";
    private static final int REQUEST_CAMERA_PERMISSION = 2001;
    private static final int UGREEN_VENDOR_ID = 11145;
    private static final int UGREEN_PRODUCT_ID = 22614;
    private static final int MACROSILICON_VENDOR_ID = 21325;
    private static final int MACROSILICON_USB_VIDEO_PRODUCT_ID = 8457;
    private static final int REQUEST_PREVIEW_WIDTH = 1280;
    private static final int REQUEST_PREVIEW_HEIGHT = 720;
    private static final String ACTION_USB_PERMISSION =
            "com.hapticasensorics.phonecapturekiosk.USB_PERMISSION";
    private static final String EXTRA_COMMAND = "command";
    private static final String COMMAND_CAPTURE_IMAGE = "capture_image";
    private static final String COMMAND_CAPTURE_VIDEO = "capture_video";
    private static final String COMMAND_REFRESH = "refresh";
    private static final long VIDEO_DURATION_MILLIS = 6000L;
    private static final long PREVIEW_STALE_TIMEOUT_MS = 2000L;
    private static final long HEALTH_CHECK_INTERVAL_MS = 1000L;
    private static final long CLASSIFICATION_INTERVAL_MS = 300L;
    private static final long CLASSIFICATION_PERSIST_INTERVAL_MS = 1000L;
    private static final int OPEN_FAILURES_BEFORE_STACK_RESET = 3;
    private static final long MIN_STACK_RESET_INTERVAL_MS = 15000L;
    private static final long USB_PERMISSION_REQUEST_TIMEOUT_MS = 15000L;

    private FrameLayout previewContainer;
    private AspectRatioTextureView cameraView;
    private TextView previewStateView;
    private TextView summaryView;
    private TextView statusView;
    private MultiCameraClient cameraClient;
    private MultiCameraClient.Camera activeCamera;
    private UsbDevice activeDevice;
    private USBMonitor.UsbControlBlock activeControlBlock;
    private boolean surfaceReady;
    private boolean pendingCapture;
    private boolean pendingVideoCapture;
    private File pendingCaptureTarget;
    private boolean permissionRequestInFlight;
    private long permissionRequestStartedAtMs;
    private boolean openInFlight;
    private boolean recordingInFlight;
    private boolean activityVisible;
    private boolean cameraClientRegistered;
    private boolean cameraClientDestroyed;
    private File activeVideoTarget;
    private Nv21Mp4Recorder activeRecorder;
    private int previewWidth = REQUEST_PREVIEW_WIDTH;
    private int previewHeight = REQUEST_PREVIEW_HEIGHT;
    private long activeVideoStartedAtMs;
    private long lastPreviewFrameAtMs;
    private long lastClassificationAtMs;
    private long lastClassificationPersistAtMs;
    private long lastStackResetAtMs;
    private int consecutiveOpenFailures;
    private String lastOpenErrorMessage = "";
    private int[] previousLumaSamples;
    private FrameClassification lastClassification = FrameClassification.initial();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable healthCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (!activityVisible) {
                return;
            }
            maybeExpireStaleUsbPermissionRequest();
            if (surfaceReady
                    && activeDevice != null
                    && Boolean.TRUE.equals(hasUsbPermission(activeDevice))
                    && !permissionRequestInFlight
                    && !recordingInFlight) {
                if (activeCamera == null || !activeCamera.isCameraOpened()) {
                    Log.i(TAG, "Health check reopening closed UVC session.");
                    maybeOpenAuthorizedControlBlock();
                    maybeOpenActiveCamera();
                } else if (isPreviewStale()) {
                    Log.w(TAG, "Health check detected stale preview; reopening session.");
                    reopenActiveSession("health check stale preview");
                }
            }
            scheduleHealthCheck();
        }
    };
    private boolean usbPermissionReceiverRegistered;
    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) {
                return;
            }
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            Log.i(TAG, "USB permission result: device=" + (device == null ? "null" : device.getDeviceName())
                    + " granted=" + granted);
            clearUsbPermissionRequestState();
            if (!isSameDevice(device, activeDevice)) {
                refreshAndPersist();
                return;
            }
            if (granted) {
                maybeOpenAuthorizedControlBlock();
                maybeOpenActiveCamera();
            }
            refreshAndPersist();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContentView());
        registerUsbPermissionReceiver();

        cameraClient = new MultiCameraClient(this, this);
        cameraClient.openDebug(true);
        cameraClientDestroyed = false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }

        refreshAndPersist();
        handleCommand(getIntent());
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityVisible = true;
        cameraClient.register();
        cameraClientRegistered = true;
        refreshAndPersist();
        discoverExistingDevices();
        scheduleHealthCheck();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityVisible = true;
        refreshAndPersist();
        discoverExistingDevices();
        maybeOpenActiveCamera();
        scheduleHealthCheck();
    }

    @Override
    protected void onStop() {
        super.onStop();
        activityVisible = false;
        mainHandler.removeCallbacks(healthCheckRunnable);
        closeActiveCamera();
        activeControlBlock = null;
        permissionRequestInFlight = false;
        lastPreviewFrameAtMs = 0L;
        cameraClient.unRegister();
        cameraClientRegistered = false;
        refreshAndPersist();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        activityVisible = false;
        mainHandler.removeCallbacks(healthCheckRunnable);
        if (activeRecorder != null) {
            activeRecorder.stop();
            activeRecorder = null;
        }
        closeActiveCamera();
        cameraClientDestroyed = true;
        cameraClient.destroy();
        unregisterUsbPermissionReceiver();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleCommand(intent);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            refreshAndPersist();
            maybeOpenActiveCamera();
        }
    }

    @Override
    public void onAttachDev(UsbDevice device) {
        if (!isSupportedCaptureDevice(device)) {
            return;
        }
        Log.i(TAG, "UVC device attached: " + device.getDeviceName());
        activeDevice = device;
        runOnUiThread(() -> {
            maybeOpenAuthorizedControlBlock();
            maybeOpenActiveCamera();
            refreshAndPersist();
        });
    }

    @Override
    public void onDetachDec(UsbDevice device) {
        if (!isSameDevice(device, activeDevice)) {
            return;
        }
        Log.i(TAG, "UVC device detached: " + device.getDeviceName());
        runOnUiThread(() -> {
            closeActiveCamera();
            activeDevice = null;
            activeControlBlock = null;
            permissionRequestInFlight = false;
            openInFlight = false;
            refreshAndPersist();
        });
    }

    @Override
    public void onConnectDev(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
        if (!isSupportedCaptureDevice(device)) {
            return;
        }
        Log.i(TAG, "UVC device connected: " + device.getDeviceName());
        runOnUiThread(() -> {
            activeDevice = device;
            activeControlBlock = ctrlBlock;
            permissionRequestInFlight = false;
            ensureActiveCamera();
            maybeOpenActiveCamera();
            refreshAndPersist();
        });
    }

    @Override
    public void onDisConnectDec(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
        if (!isSameDevice(device, activeDevice)) {
            return;
        }
        Log.i(TAG, "UVC device disconnected: " + device.getDeviceName());
        runOnUiThread(() -> {
            closeActiveCamera();
            activeControlBlock = null;
            openInFlight = false;
            refreshAndPersist();
        });
    }

    @Override
    public void onCancelDev(UsbDevice device) {
        if (!isSameDevice(device, activeDevice)) {
            return;
        }
        Log.w(TAG, "USB permission canceled for: " + device.getDeviceName());
        runOnUiThread(() -> {
            pendingCapture = false;
            pendingCaptureTarget = null;
            pendingVideoCapture = false;
            recordingInFlight = false;
            activeVideoTarget = null;
            activeRecorder = null;
            permissionRequestInFlight = false;
            refreshAndPersist();
        });
    }

    @Override
    public void onCameraState(MultiCameraClient.Camera camera, ICameraStateCallBack.State state, String msg) {
        Log.i(TAG, "cameraState=" + state + " msg=" + msg);
        runOnUiThread(() -> {
            if (state == ICameraStateCallBack.State.OPENED) {
                openInFlight = false;
                consecutiveOpenFailures = 0;
                lastOpenErrorMessage = "";
                PreviewSize previewSize = camera.getPreviewSize();
                if (previewSize != null) {
                    previewWidth = previewSize.getWidth();
                    previewHeight = previewSize.getHeight();
                    cameraView.setAspectRatio(previewSize.getHeight(), previewSize.getWidth());
                    updatePreviewContainerLayout();
                }
                if (pendingVideoCapture && !recordingInFlight) {
                    requestVideoCapture();
                } else if (pendingCapture) {
                    requestImageCapture();
                }
            }
            if (state == ICameraStateCallBack.State.ERROR) {
                openInFlight = false;
                pendingCapture = false;
                pendingCaptureTarget = null;
                pendingVideoCapture = false;
                consecutiveOpenFailures += 1;
                lastOpenErrorMessage = msg == null ? "" : msg;
                maybeRecoverFromRepeatedOpenFailure(msg);
            }
            if (state == ICameraStateCallBack.State.CLOSED) {
                openInFlight = false;
            }
            refreshAndPersist();
        });
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        surfaceReady = true;
        updatePreviewContainerLayout();
        maybeOpenActiveCamera();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        updatePreviewContainerLayout();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        surfaceReady = false;
        closeActiveCamera();
        activeControlBlock = null;
        lastPreviewFrameAtMs = 0L;
        refreshAndPersist();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    private LinearLayout buildContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        root.setBackgroundColor(Color.WHITE);

        previewContainer = new FrameLayout(this);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(270)
        );
        previewContainer.setLayoutParams(previewParams);
        previewContainer.setBackgroundColor(Color.BLACK);

        cameraView = new AspectRatioTextureView(this);
        cameraView.setSurfaceTextureListener(this);
        previewContainer.addView(
                cameraView,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                )
        );

        previewStateView = new TextView(this);
        previewStateView.setTextSize(20f);
        previewStateView.setTypeface(android.graphics.Typeface.MONOSPACE);
        previewStateView.setTextColor(Color.WHITE);
        previewStateView.setGravity(Gravity.CENTER);
        previewStateView.setBackgroundColor(0x55000000);
        previewStateView.setPadding(24, 24, 24, 24);
        previewContainer.addView(
                previewStateView,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                )
        );

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_HORIZONTAL);
        controls.setPadding(0, 18, 0, 18);

        Button refreshButton = new Button(this);
        refreshButton.setText("Refresh");
        refreshButton.setAllCaps(false);
        refreshButton.setOnClickListener(v -> {
            discoverExistingDevices();
            refreshAndPersist();
        });
        controls.addView(refreshButton, weightedButtonParams());

        Button captureButton = new Button(this);
        captureButton.setText("Save JPEG");
        captureButton.setAllCaps(false);
        captureButton.setOnClickListener(v -> requestImageCapture());
        controls.addView(captureButton, weightedButtonParams());

        Button captureVideoButton = new Button(this);
        captureVideoButton.setText("Save MP4");
        captureVideoButton.setAllCaps(false);
        captureVideoButton.setOnClickListener(v -> requestVideoCapture());
        controls.addView(captureVideoButton, weightedButtonParams());

        summaryView = new TextView(this);
        summaryView.setTextSize(15f);
        summaryView.setTypeface(android.graphics.Typeface.MONOSPACE);
        summaryView.setTextColor(Color.DKGRAY);
        summaryView.setPadding(0, 0, 0, 18);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        );
        scrollView.setLayoutParams(scrollParams);

        statusView = new TextView(this);
        statusView.setTextSize(13f);
        statusView.setTypeface(android.graphics.Typeface.MONOSPACE);
        statusView.setTextColor(Color.GRAY);
        scrollView.addView(statusView);

        root.addView(previewContainer);
        root.addView(controls);
        root.addView(summaryView);
        root.addView(scrollView);
        return root;
    }

    private LinearLayout.LayoutParams weightedButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        params.leftMargin = 8;
        params.rightMargin = 8;
        return params;
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private void handleCommand(Intent intent) {
        if (intent == null) {
            return;
        }
        handleUsbAttachIntent(intent);
        String command = intent.getStringExtra(EXTRA_COMMAND);
        if (command == null || command.isEmpty()) {
            return;
        }

        Log.i(TAG, "Received command: " + command);
        if (COMMAND_REFRESH.equals(command)) {
            reopenActiveSession("refresh command");
            discoverExistingDevices();
            refreshAndPersist();
        } else if (COMMAND_CAPTURE_IMAGE.equals(command)) {
            requestImageCapture();
        } else if (COMMAND_CAPTURE_VIDEO.equals(command)) {
            requestVideoCapture();
        }

        intent.removeExtra(EXTRA_COMMAND);
    }

    private void handleUsbAttachIntent(Intent intent) {
        if (!UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            return;
        }
        UsbDevice device;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        } else {
            device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        }
        if (!isSupportedCaptureDevice(device)) {
            return;
        }
        Log.i(TAG, "Handling USB_DEVICE_ATTACHED intent for " + device.getDeviceName());
        activeDevice = device;
        maybeOpenAuthorizedControlBlock();
        maybeOpenActiveCamera();
        refreshAndPersist();
    }

    private void discoverExistingDevices() {
        List<UsbDevice> devices = safeGetDeviceList();
        if (devices == null) {
            return;
        }

        for (UsbDevice device : devices) {
            if (isSupportedCaptureDevice(device)) {
                activeDevice = device;
                if (Boolean.TRUE.equals(hasUsbPermission(device))) {
                    maybeOpenAuthorizedControlBlock();
                }
                if (activeControlBlock != null) {
                    maybeOpenActiveCamera();
                } else {
                    requestPermissionForActiveDevice();
                }
                return;
            }
        }
    }

    private void requestPermissionForActiveDevice() {
        Log.i(TAG, "requestPermissionForActiveDevice activeDevice="
                + (activeDevice == null ? "null" : activeDevice.getDeviceName())
                + " inflight=" + permissionRequestInFlight);
        if (activeDevice == null) {
            return;
        }
        if (permissionRequestInFlight) {
            Log.i(TAG, "USB permission request already in flight");
            return;
        }
        boolean hasPermission = Boolean.TRUE.equals(hasUsbPermission(activeDevice));
        Log.i(TAG, "requestPermissionForActiveDevice current permission=" + hasPermission);
        if (hasPermission) {
            maybeOpenAuthorizedControlBlock();
            if (activeControlBlock != null) {
                maybeOpenActiveCamera();
                return;
            }
        }
        if (hasPermission && activeControlBlock != null) {
            return;
        }
        permissionRequestInFlight = true;
        permissionRequestStartedAtMs = System.currentTimeMillis();
        requestUsbPermissionDirectly(activeDevice);
    }

    private void requestUsbPermissionDirectly(UsbDevice device) {
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            clearUsbPermissionRequestState();
            Log.e(TAG, "UsbManager unavailable while requesting permission");
            refreshAndPersist();
            return;
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent permissionIntent = PendingIntent.getBroadcast(
                this,
                0,
                new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()),
                flags
        );
        Log.i(TAG, "Requesting USB permission directly for " + device.getDeviceName()
                + " package=" + getPackageName());
        usbManager.requestPermission(device, permissionIntent);
    }

    private void registerUsbPermissionReceiver() {
        if (usbPermissionReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbPermissionReceiver, filter);
        }
        usbPermissionReceiverRegistered = true;
    }

    private void unregisterUsbPermissionReceiver() {
        if (!usbPermissionReceiverRegistered) {
            return;
        }
        try {
            unregisterReceiver(usbPermissionReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        usbPermissionReceiverRegistered = false;
    }

    private void ensureActiveCamera() {
        if (activeDevice == null || activeControlBlock == null) {
            return;
        }
        if (activeCamera != null && activeCamera.getUsbDevice() != null
                && isSameDevice(activeCamera.getUsbDevice(), activeDevice)) {
            activeCamera.setUsbControlBlock(activeControlBlock);
            activeCamera.setCameraStateCallBack(this);
            return;
        }

        closeActiveCamera();
        activeCamera = new MultiCameraClient.Camera(this, activeDevice);
        activeCamera.setUsbControlBlock(activeControlBlock);
        activeCamera.setCameraStateCallBack(this);
        activeCamera.addPreviewDataCallBack(this::onPreviewFrame);
    }

    private void maybeOpenActiveCamera() {
        if (!surfaceReady || activeDevice == null || activeControlBlock == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        ensureActiveCamera();
        if (activeCamera == null || activeCamera.isCameraOpened() || openInFlight) {
            return;
        }

        openInFlight = true;
        activeCamera.openCamera(cameraView, buildCameraRequest());
    }

    private boolean isPreviewStale() {
        if (activeCamera == null || !activeCamera.isCameraOpened()) {
            return false;
        }
        if (lastPreviewFrameAtMs == 0L) {
            return true;
        }
        return System.currentTimeMillis() - lastPreviewFrameAtMs > PREVIEW_STALE_TIMEOUT_MS;
    }

    private void reopenActiveSession(String reason) {
        Log.i(TAG, "Reopening UVC session: " + reason);
        closeActiveCamera();
        activeControlBlock = null;
        openInFlight = false;
        lastPreviewFrameAtMs = 0L;
        mainHandler.postDelayed(() -> {
            maybeOpenAuthorizedControlBlock();
            maybeOpenActiveCamera();
            refreshAndPersist();
        }, 250L);
    }

    private void scheduleHealthCheck() {
        mainHandler.removeCallbacks(healthCheckRunnable);
        if (!activityVisible) {
            return;
        }
        mainHandler.postDelayed(healthCheckRunnable, HEALTH_CHECK_INTERVAL_MS);
    }

    private void updatePreviewContainerLayout() {
        if (previewContainer == null || previewWidth <= 0 || previewHeight <= 0) {
            return;
        }
        previewContainer.post(() -> {
            int availableWidth = previewContainer.getWidth();
            if (availableWidth <= 0) {
                return;
            }
            int targetHeight = Math.round((availableWidth * previewHeight) / (float) previewWidth);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) previewContainer.getLayoutParams();
            if (params != null && params.height != targetHeight) {
                params.height = targetHeight;
                previewContainer.setLayoutParams(params);
            }
        });
    }

    private CameraRequest buildCameraRequest() {
        return new CameraRequest.Builder()
                .setPreviewWidth(REQUEST_PREVIEW_WIDTH)
                .setPreviewHeight(REQUEST_PREVIEW_HEIGHT)
                .setContinuousAFModel(true)
                .setContinuousAutoModel(true)
                .create();
    }

    private void requestImageCapture() {
        Log.i(TAG, "requestImageCapture activeDevice="
                + (activeDevice == null ? "null" : activeDevice.getDeviceName())
                + " hasPermission="
                + (activeDevice == null ? "n/a" : hasUsbPermission(activeDevice))
                + " inflight=" + permissionRequestInFlight);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            statusView.setText("Camera permission is required before UVC capture.");
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
            return;
        }
        if (activeDevice == null) {
            discoverExistingDevices();
        }
        if (activeDevice == null) {
            statusView.setText("No supported capture device found.");
            refreshAndPersist();
            return;
        }

        Boolean hasPermission = hasUsbPermission(activeDevice);
        if (!Boolean.TRUE.equals(hasPermission)) {
            pendingCapture = true;
            statusView.setText("Requesting USB permission. Accept the prompt on the phone.");
            requestPermissionForActiveDevice();
            refreshAndPersist();
            return;
        }

        if (isPreviewStale()) {
            pendingCapture = true;
            statusView.setText("Preview looks stale. Reopening UVC session.");
            reopenActiveSession("stale preview before image capture");
            refreshAndPersist();
            return;
        }

        maybeOpenActiveCamera();
        if (activeCamera == null || !activeCamera.isCameraOpened()) {
            pendingCapture = true;
            statusView.setText("Opening UVC camera. If prompted, allow USB access.");
            refreshAndPersist();
            return;
        }

        File capturesDir = new File(getMediaRoot(), "uvc-captures");
        capturesDir.mkdirs();
        String name = "uvc-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".jpg";
        pendingCaptureTarget = new File(capturesDir, name);
        pendingCapture = true;
        statusView.setText("Waiting for one NV21 preview frame...\n\n" + pendingCaptureTarget.getAbsolutePath());
        refreshAndPersist();
    }

    private void requestVideoCapture() {
        if (recordingInFlight) {
            statusView.setText("A video recording is already in progress.");
            refreshAndPersist();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            statusView.setText("Camera permission is required before UVC capture.");
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
            return;
        }
        if (activeDevice == null) {
            discoverExistingDevices();
        }
        if (activeDevice == null) {
            pendingVideoCapture = true;
            statusView.setText("No supported capture device found.");
            refreshAndPersist();
            return;
        }

        boolean hasPermission = Boolean.TRUE.equals(hasUsbPermission(activeDevice));
        if (!hasPermission) {
            pendingVideoCapture = true;
            statusView.setText("USB permission is required before video capture.");
            requestPermissionForActiveDevice();
            refreshAndPersist();
            return;
        }

        if (isPreviewStale()) {
            pendingVideoCapture = true;
            statusView.setText("Preview looks stale. Reopening UVC session.");
            reopenActiveSession("stale preview before video capture");
            refreshAndPersist();
            return;
        }

        maybeOpenAuthorizedControlBlock();
        maybeOpenActiveCamera();
        if (activeCamera == null || !activeCamera.isCameraOpened()) {
            pendingVideoCapture = true;
            statusView.setText("Opening UVC camera for video capture...");
            refreshAndPersist();
            return;
        }

        File capturesDir = new File(getMediaRoot(), "uvc-videos");
        capturesDir.mkdirs();
        String name = "uvc-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".mp4";
        File target = new File(capturesDir, name);

        pendingVideoCapture = false;
        recordingInFlight = true;
        activeVideoStartedAtMs = 0L;
        activeVideoTarget = target;
        activeRecorder = new Nv21Mp4Recorder(
                target,
                previewWidth,
                previewHeight,
                10,
                2_500_000,
                new Nv21Mp4Recorder.Listener() {
                    @Override
                    public void onStarted() {
                        activeVideoStartedAtMs = System.currentTimeMillis();
                        runOnUiThread(() -> {
                            statusView.setText("Recording UVC MP4...\n\n" + target.getAbsolutePath());
                            refreshAndPersist();
                        });
                        mainHandler.postDelayed(() -> stopVideoCapture(target), VIDEO_DURATION_MILLIS);
                    }

                    @Override
                    public void onCompleted(String path) {
                        long completedAtMs = System.currentTimeMillis();
                        long startedAtMs = activeVideoStartedAtMs;
                        pendingVideoCapture = false;
                        recordingInFlight = false;
                        activeVideoTarget = null;
                        activeRecorder = null;
                        activeVideoStartedAtMs = 0L;
                        CaptureMetadataStore.recordVideo(
                                getMediaRoot(),
                                new File(path),
                                startedAtMs,
                                completedAtMs,
                                previewWidth,
                                previewHeight,
                                10,
                                "probe"
                        );
                        writeLastVideo(path);
                        runOnUiThread(() -> {
                            statusView.setText("Saved UVC MP4\n\n" + path);
                            refreshAndPersist();
                        });
                    }

                    @Override
                    public void onError(String message) {
                        pendingVideoCapture = false;
                        recordingInFlight = false;
                        activeVideoTarget = null;
                        activeRecorder = null;
                        activeVideoStartedAtMs = 0L;
                        runOnUiThread(() -> {
                            statusView.setText("Video capture failed\n\n" + message);
                            refreshAndPersist();
                        });
                    }
                }
        );
        statusView.setText("Starting short UVC video capture...\n\n" + target.getAbsolutePath());
        refreshAndPersist();
        activeRecorder.start();
    }

    private void stopVideoCapture(File expectedTarget) {
        if (!recordingInFlight) {
            return;
        }
        try {
            if (activeRecorder != null && expectedTarget.equals(activeVideoTarget)) {
                activeRecorder.stop();
                Log.i(TAG, "Requested stop for UVC MP4: " + expectedTarget.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop video capture cleanly", e);
        }
    }

    private void onPreviewFrame(byte[] data, IPreviewDataCallBack.DataFormat format) {
        lastPreviewFrameAtMs = System.currentTimeMillis();
        maybeClassifyPreviewFrame(data, format);
        if (recordingInFlight && activeRecorder != null && format == IPreviewDataCallBack.DataFormat.NV21) {
            activeRecorder.offerFrame(data, previewWidth, previewHeight);
        }
        if (!pendingCapture || pendingCaptureTarget == null) {
            return;
        }
        if (format != IPreviewDataCallBack.DataFormat.NV21) {
            Log.w(TAG, "Skipping preview frame with unexpected format: " + format);
            return;
        }

        File target = pendingCaptureTarget;
        pendingCapture = false;
        pendingCaptureTarget = null;

        try {
            saveNv21FrameAsJpeg(data, target);
            Log.i(TAG, "Saved preview frame to " + target.getAbsolutePath());
            CaptureMetadataStore.recordStill(
                    getMediaRoot(),
                    target,
                    System.currentTimeMillis(),
                    previewWidth,
                    previewHeight,
                    "probe"
            );
            writeLastCapture(target.getAbsolutePath());
            runOnUiThread(() -> {
                statusView.setText("Saved preview frame\n\n" + target.getAbsolutePath());
                refreshAndPersist();
            });
        } catch (IOException e) {
            Log.e(TAG, "Failed to save preview frame", e);
            runOnUiThread(() -> {
                statusView.setText("Capture failed\n\n" + e.getMessage());
                refreshAndPersist();
            });
        }
    }

    private void saveNv21FrameAsJpeg(byte[] data, File target) throws IOException {
        if (target.getParentFile() != null) {
            target.getParentFile().mkdirs();
        }
        YuvImage yuvImage = new YuvImage(data, ImageFormat.NV21, previewWidth, previewHeight, null);
        try (FileOutputStream outputStream = new FileOutputStream(target, false)) {
            if (!yuvImage.compressToJpeg(new Rect(0, 0, previewWidth, previewHeight), 95, outputStream)) {
                throw new IOException("compressToJpeg returned false");
            }
            outputStream.flush();
        }
    }

    private void closeActiveCamera() {
        openInFlight = false;
        if (activeRecorder != null) {
            activeRecorder.stop();
            activeRecorder = null;
        }
        recordingInFlight = false;
        activeVideoTarget = null;
        activeVideoStartedAtMs = 0L;
        previousLumaSamples = null;
        if (activeCamera != null) {
            activeCamera.closeCamera();
            activeCamera = null;
        }
    }

    private void maybeRecoverFromRepeatedOpenFailure(String message) {
        if (!looksLikeNativeOpenFailure(message)) {
            return;
        }
        if (consecutiveOpenFailures < OPEN_FAILURES_BEFORE_STACK_RESET) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastStackResetAtMs < MIN_STACK_RESET_INTERVAL_MS) {
            return;
        }
        lastStackResetAtMs = now;
        statusView.setText("Resetting USB camera stack after repeated native open failures...");
        mainHandler.postDelayed(() -> resetCameraClientStack("native open failure " + consecutiveOpenFailures), 300L);
    }

    private boolean looksLikeNativeOpenFailure(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        return message.contains("result=-99")
                || message.contains("open camera failed")
                || message.contains("unsupported preview size");
    }

    private void resetCameraClientStack(String reason) {
        Log.w(TAG, "Resetting USB camera stack: " + reason);
        closeActiveCamera();
        activeControlBlock = null;
        clearUsbPermissionRequestState();
        openInFlight = false;
        if (cameraClient != null) {
            if (cameraClientRegistered) {
                cameraClient.unRegister();
                cameraClientRegistered = false;
            }
            cameraClient.destroy();
        }
        cameraClient = new MultiCameraClient(this, this);
        cameraClient.openDebug(true);
        cameraClientDestroyed = false;
        if (activityVisible) {
            cameraClient.register();
            cameraClientRegistered = true;
        }
        consecutiveOpenFailures = 0;
        statusView.setText("USB camera stack reset: " + reason);
        mainHandler.postDelayed(() -> {
            discoverExistingDevices();
            maybeOpenAuthorizedControlBlock();
            maybeOpenActiveCamera();
            refreshAndPersist();
        }, 500L);
    }

    private void maybeOpenAuthorizedControlBlock() {
        if (activeDevice == null || activeControlBlock != null) {
            return;
        }
        if (!Boolean.TRUE.equals(hasUsbPermission(activeDevice))) {
            return;
        }

        USBMonitor usbMonitor = getUsbMonitor();
        if (usbMonitor == null) {
            return;
        }

        try {
            activeControlBlock = usbMonitor.openDevice(activeDevice);
            clearUsbPermissionRequestState();
            if (activeControlBlock != null) {
                Log.i(TAG, "Opened control block directly for authorized device.");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to open authorized device directly", e);
        }
    }

    private void clearUsbPermissionRequestState() {
        permissionRequestInFlight = false;
        permissionRequestStartedAtMs = 0L;
    }

    private void maybeExpireStaleUsbPermissionRequest() {
        if (!permissionRequestInFlight || permissionRequestStartedAtMs == 0L) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - permissionRequestStartedAtMs < USB_PERMISSION_REQUEST_TIMEOUT_MS) {
            return;
        }
        Log.w(TAG, "USB permission request timed out after "
                + USB_PERMISSION_REQUEST_TIMEOUT_MS + "ms; clearing stuck request state.");
        clearUsbPermissionRequestState();
        refreshAndPersist();
        if (activeDevice != null && !Boolean.TRUE.equals(hasUsbPermission(activeDevice))) {
            requestPermissionForActiveDevice();
        }
    }

    private USBMonitor getUsbMonitor() {
        try {
            Field usbMonitorField = MultiCameraClient.class.getDeclaredField("mUsbMonitor");
            usbMonitorField.setAccessible(true);
            Object value = usbMonitorField.get(cameraClient);
            if (value instanceof USBMonitor) {
                return (USBMonitor) value;
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e(TAG, "Failed to access internal USBMonitor", e);
        }
        return null;
    }

    private void refreshAndPersist() {
        try {
            JSONObject status = buildStatusJson();
            writeJson(new File(getDiagnosticsDirectory(), "uvc-status.json"), status);
            if (summaryView != null) {
                summaryView.setText(buildSummaryText());
            }
            updatePreviewOverlay();
            statusView.setText(status.toString(2));
        } catch (JSONException | IOException e) {
            Log.e(TAG, "refreshAndPersist failed", e);
            if (summaryView != null) {
                summaryView.setText("Phonecapture UVC Probe\nstate: error");
            }
            statusView.setText("Failed to refresh UVC status\n\n" + e.getMessage());
        }
    }

    private String buildSummaryText() {
        FrameClassification classification = getEffectiveClassification();
        StringBuilder sb = new StringBuilder();
        sb.append("Phonecapture UVC Probe\n");
        sb.append("device: ").append(activeDevice != null ? "attached" : "missing").append('\n');
        sb.append("usb: ").append(activeDevice != null && Boolean.TRUE.equals(hasUsbPermission(activeDevice)) ? "granted" : "waiting").append('\n');
        sb.append("camera: ").append(activeCamera != null && activeCamera.isCameraOpened() ? "open" : "closed").append('\n');
        sb.append("preview: ").append(previewWidth).append('x').append(previewHeight).append('\n');
        sb.append("signal: ").append(classification.label).append('\n');
        sb.append("recording: ").append(recordingInFlight ? "active" : "idle");
        if (activeVideoTarget != null) {
            sb.append('\n').append("target: ").append(activeVideoTarget.getName());
        }
        return sb.toString();
    }

    private JSONObject buildStatusJson() throws JSONException {
        FrameClassification classification = getEffectiveClassification();
        JSONObject root = new JSONObject();
        root.put("timestamp", isoTimestamp());
        root.put("surfaceReady", surfaceReady);
        root.put("activityVisible", activityVisible);
        root.put("cameraClientRegistered", cameraClientRegistered);
        root.put("cameraClientDestroyed", cameraClientDestroyed);
        root.put("cameraOpened", activeCamera != null && activeCamera.isCameraOpened());
        root.put("mediaRoot", getMediaRoot().getAbsolutePath());
        root.put("diagnosticsDir", getDiagnosticsDirectory().getAbsolutePath());
        root.put("hasActiveControlBlock", activeControlBlock != null);
        root.put("recordingInFlight", recordingInFlight);
        root.put("pendingVideoCapture", pendingVideoCapture);
        root.put("lastPreviewFrameAtMs", lastPreviewFrameAtMs);
        root.put("previewStale", isPreviewStale());
        root.put("consecutiveOpenFailures", consecutiveOpenFailures);
        root.put("lastOpenErrorMessage", lastOpenErrorMessage);
        root.put("signalClassification", classification.toJson());
        root.put(
                "cameraPermissionGranted",
                Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                        || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        );

        JSONArray devices = new JSONArray();
        List<UsbDevice> usbDevices = safeGetDeviceList();
        if (usbDevices != null) {
            for (UsbDevice device : usbDevices) {
                JSONObject entry = new JSONObject();
                entry.put("deviceName", device.getDeviceName());
                entry.put("vendorId", device.getVendorId());
                entry.put("productId", device.getProductId());
                entry.put("deviceClass", device.getDeviceClass());
                entry.put("deviceSubclass", device.getDeviceSubclass());
                entry.put("deviceProtocol", device.getDeviceProtocol());
                entry.put("isSupportedCaptureDevice", isSupportedCaptureDevice(device));
                entry.put("hasPermission", Boolean.TRUE.equals(hasUsbPermission(device)));
                devices.put(entry);
            }
        }
        root.put("deviceCount", devices.length());
        root.put("devices", devices);

        if (activeDevice != null) {
            JSONObject active = new JSONObject();
            active.put("deviceName", activeDevice.getDeviceName());
            active.put("vendorId", activeDevice.getVendorId());
            active.put("productId", activeDevice.getProductId());
            active.put("hasPermission", Boolean.TRUE.equals(hasUsbPermission(activeDevice)));
            root.put("activeDevice", active);
        }

        if (activeVideoTarget != null) {
            root.put("activeVideoTarget", activeVideoTarget.getAbsolutePath());
        }

        if (activeCamera != null) {
            PreviewSize previewSize = activeCamera.getPreviewSize();
            if (previewSize != null) {
                JSONObject preview = new JSONObject();
                preview.put("width", previewSize.getWidth());
                preview.put("height", previewSize.getHeight());
                root.put("previewSize", preview);
            }

            JSONArray previewSizes = new JSONArray();
            List<PreviewSize> sizes = activeCamera.getAllPreviewSizes(null);
            if (sizes != null) {
                for (PreviewSize size : sizes) {
                    JSONObject entry = new JSONObject();
                    entry.put("width", size.getWidth());
                    entry.put("height", size.getHeight());
                    previewSizes.put(entry);
                }
            }
            root.put("supportedPreviewSizes", previewSizes);
        }

        return root;
    }

    private void updatePreviewOverlay() {
        if (previewStateView == null) {
            return;
        }
        FrameClassification classification = getEffectiveClassification();
        String label = classification.label;
        if ("device_missing".equals(label)) {
            previewStateView.setText("Capture dongle missing");
            previewStateView.setVisibility(TextView.VISIBLE);
        } else if ("no_signal_colorbars".equals(label)) {
            previewStateView.setText("No source video");
            previewStateView.setVisibility(TextView.VISIBLE);
        } else if (activeCamera == null || !activeCamera.isCameraOpened()) {
            previewStateView.setText("Opening capture session");
            previewStateView.setVisibility(TextView.VISIBLE);
        } else {
            previewStateView.setText("");
            previewStateView.setVisibility(TextView.GONE);
        }
    }

    private List<UsbDevice> safeGetDeviceList() {
        if (cameraClient == null || cameraClientDestroyed) {
            return null;
        }
        try {
            return cameraClient.getDeviceList(null);
        } catch (IllegalStateException e) {
            Log.w(TAG, "USB device list unavailable during lifecycle transition", e);
            return null;
        }
    }

    private Boolean hasUsbPermission(UsbDevice device) {
        if (device == null || cameraClient == null || cameraClientDestroyed) {
            return false;
        }
        try {
            return cameraClient.hasPermission(device);
        } catch (IllegalStateException e) {
            Log.w(TAG, "USB permission unavailable during lifecycle transition", e);
            return false;
        }
    }

    private void writeLastCapture(String path) {
        try {
            JSONObject lastCapture = new JSONObject();
            lastCapture.put("timestamp", isoTimestamp());
            lastCapture.put("path", path);
            writeJson(new File(getDiagnosticsDirectory(), "uvc-last-capture.json"), lastCapture);
            writeText(new File(getDiagnosticsDirectory(), "last-capture.txt"), path);
        } catch (JSONException | IOException e) {
            Log.e(TAG, "Failed to write last capture file", e);
        }
    }

    private void writeLastVideo(String path) {
        try {
            JSONObject lastVideo = new JSONObject();
            lastVideo.put("timestamp", isoTimestamp());
            lastVideo.put("path", path);
            writeJson(new File(getDiagnosticsDirectory(), "uvc-last-video.json"), lastVideo);
            writeText(new File(getDiagnosticsDirectory(), "last-video.txt"), path);
        } catch (JSONException | IOException e) {
            Log.e(TAG, "Failed to write last video file", e);
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
        File diagnostics = new File(getMediaRoot(), "diagnostics");
        diagnostics.mkdirs();
        return diagnostics;
    }

    private void writeJson(File target, JSONObject object) throws IOException {
        if (target.getParentFile() != null) {
            target.getParentFile().mkdirs();
        }
        try (FileOutputStream outputStream = new FileOutputStream(target, false)) {
            outputStream.write(object.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void writeText(File target, String value) throws IOException {
        if (target.getParentFile() != null) {
            target.getParentFile().mkdirs();
        }
        try (FileOutputStream outputStream = new FileOutputStream(target, false)) {
            outputStream.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String isoTimestamp() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date());
    }

    private boolean isSupportedCaptureDevice(UsbDevice device) {
        if (device == null) {
            return false;
        }
        if (device.getVendorId() == UGREEN_VENDOR_ID
                && device.getProductId() == UGREEN_PRODUCT_ID) {
            return true;
        }
        return device.getVendorId() == MACROSILICON_VENDOR_ID
                && device.getProductId() == MACROSILICON_USB_VIDEO_PRODUCT_ID;
    }

    private boolean isSameDevice(UsbDevice left, UsbDevice right) {
        return left != null && right != null && left.getDeviceName().equals(right.getDeviceName());
    }

    private FrameClassification getEffectiveClassification() {
        if (activeDevice == null) {
            return FrameClassification.deviceMissing();
        }
        return lastClassification;
    }

    private void maybeClassifyPreviewFrame(byte[] data, IPreviewDataCallBack.DataFormat format) {
        if (format != IPreviewDataCallBack.DataFormat.NV21 || data == null || previewWidth <= 0 || previewHeight <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastClassificationAtMs < CLASSIFICATION_INTERVAL_MS) {
            return;
        }
        lastClassificationAtMs = now;
        FrameClassification classification = classifyNv21Frame(data, previewWidth, previewHeight, previousLumaSamples);
        previousLumaSamples = classification.lumaSamples;
        boolean classificationChanged = !classification.label.equals(lastClassification.label);
        if (classificationChanged) {
            Log.i(
                    TAG,
                    "Signal classification changed: " + lastClassification.label
                            + " -> " + classification.label
                            + " motion=" + round2(classification.motionScore)
                            + " yAvg=" + round2(classification.yAvg)
                            + " satAvg=" + round2(classification.satAvg)
                            + " bars=" + classification.colorBarBoundaryCount
            );
        }
        lastClassification = classification;
        if (classificationChanged || now - lastClassificationPersistAtMs >= CLASSIFICATION_PERSIST_INTERVAL_MS) {
            lastClassificationPersistAtMs = now;
            mainHandler.post(this::refreshAndPersist);
        }
    }

    private FrameClassification classifyNv21Frame(byte[] data, int width, int height, int[] previousSamples) {
        int[] lumaSamples = sampleLumaGrid(data, width, height, 16, 9);
        double yAvg = average(lumaSamples);
        double motion = averageAbsoluteDifference(lumaSamples, previousSamples);

        StripeStats[] stripes = sampleStripeStats(data, width, height, 7);
        double satAvg = 0.0;
        int strongBoundaries = 0;
        for (int i = 0; i < stripes.length; i++) {
            satAvg += stripes[i].saturation();
            if (i == 0) {
                continue;
            }
            double delta = stripes[i - 1].distanceTo(stripes[i]);
            if (delta > 90.0) {
                strongBoundaries++;
            }
        }
        satAvg /= stripes.length;

        String label;
        if (motion >= 9.0) {
            label = "live";
        } else if (yAvg < 28.0 && satAvg < 25.0) {
            label = "blank_dark";
        } else if (strongBoundaries >= 5 && satAvg > 45.0 && yAvg > 70.0) {
            label = "no_signal_colorbars";
        } else {
            label = "stale_static";
        }
        return new FrameClassification(label, yAvg, satAvg, motion, strongBoundaries, lumaSamples);
    }

    private int[] sampleLumaGrid(byte[] data, int width, int height, int cols, int rows) {
        int[] samples = new int[cols * rows];
        int index = 0;
        for (int row = 0; row < rows; row++) {
            int y = Math.min(height - 1, ((row * 2 + 1) * height) / (rows * 2));
            for (int col = 0; col < cols; col++) {
                int x = Math.min(width - 1, ((col * 2 + 1) * width) / (cols * 2));
                samples[index++] = sampleY(data, width, x, y);
            }
        }
        return samples;
    }

    private StripeStats[] sampleStripeStats(byte[] data, int width, int height, int stripes) {
        StripeStats[] result = new StripeStats[stripes];
        int[] rows = new int[]{
                Math.max(0, height / 4),
                Math.max(0, height / 2),
                Math.max(0, (height * 3) / 4)
        };
        for (int i = 0; i < stripes; i++) {
            int centerX = Math.min(width - 2, ((i * 2 + 1) * width) / (stripes * 2));
            int x = centerX & ~1;
            double ySum = 0.0;
            double uSum = 0.0;
            double vSum = 0.0;
            for (int row : rows) {
                ySum += sampleY(data, width, x, row);
                uSum += sampleU(data, width, height, x, row);
                vSum += sampleV(data, width, height, x, row);
            }
            result[i] = new StripeStats(ySum / rows.length, uSum / rows.length, vSum / rows.length);
        }
        return result;
    }

    private int sampleY(byte[] data, int width, int x, int y) {
        return data[y * width + x] & 0xff;
    }

    private int sampleV(byte[] data, int width, int height, int x, int y) {
        int frameSize = width * height;
        int uvIndex = frameSize + (y / 2) * width + (x & ~1);
        return data[uvIndex] & 0xff;
    }

    private int sampleU(byte[] data, int width, int height, int x, int y) {
        int frameSize = width * height;
        int uvIndex = frameSize + (y / 2) * width + (x & ~1);
        return data[uvIndex + 1] & 0xff;
    }

    private double average(int[] values) {
        if (values == null || values.length == 0) {
            return 0.0;
        }
        long total = 0L;
        for (int value : values) {
            total += value;
        }
        return total / (double) values.length;
    }

    private double averageAbsoluteDifference(int[] current, int[] previous) {
        if (current == null || previous == null || current.length != previous.length) {
            return 0.0;
        }
        long total = 0L;
        for (int i = 0; i < current.length; i++) {
            total += Math.abs(current[i] - previous[i]);
        }
        return total / (double) current.length;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static final class StripeStats {
        final double y;
        final double u;
        final double v;

        StripeStats(double y, double u, double v) {
            this.y = y;
            this.u = u;
            this.v = v;
        }

        double saturation() {
            return Math.abs(u - 128.0) + Math.abs(v - 128.0);
        }

        double distanceTo(StripeStats other) {
            return Math.abs(y - other.y) + Math.abs(u - other.u) + Math.abs(v - other.v);
        }
    }

    private static final class FrameClassification {
        final String label;
        final double yAvg;
        final double satAvg;
        final double motionScore;
        final int colorBarBoundaryCount;
        final int[] lumaSamples;

        FrameClassification(
                String label,
                double yAvg,
                double satAvg,
                double motionScore,
                int colorBarBoundaryCount,
                int[] lumaSamples
        ) {
            this.label = label;
            this.yAvg = yAvg;
            this.satAvg = satAvg;
            this.motionScore = motionScore;
            this.colorBarBoundaryCount = colorBarBoundaryCount;
            this.lumaSamples = lumaSamples;
        }

        static FrameClassification initial() {
            return new FrameClassification("unknown", 0.0, 0.0, 0.0, 0, null);
        }

        static FrameClassification deviceMissing() {
            return new FrameClassification("device_missing", 0.0, 0.0, 0.0, 0, null);
        }

        JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("label", label);
            json.put("yAvg", yAvg);
            json.put("satAvg", satAvg);
            json.put("motionScore", motionScore);
            json.put("colorBarBoundaryCount", colorBarBoundaryCount);
            return json;
        }
    }
}
