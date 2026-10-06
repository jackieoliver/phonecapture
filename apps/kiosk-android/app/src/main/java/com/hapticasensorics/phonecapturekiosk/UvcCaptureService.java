package com.hapticasensorics.phonecapturekiosk;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.Nullable;

import com.jiangdg.ausbc.MultiCameraClient;
import com.jiangdg.ausbc.callback.ICameraStateCallBack;
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack;
import com.jiangdg.ausbc.callback.IPreviewDataCallBack;
import com.jiangdg.ausbc.camera.bean.CameraRequest;
import com.jiangdg.ausbc.camera.bean.PreviewSize;
import com.serenegiant.usb.USBMonitor;
import fi.iki.elonen.NanoHTTPD;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

public final class UvcCaptureService extends Service
        implements IDeviceConnectCallBack, ICameraStateCallBack {
    private static final String TAG = "PhonecaptureUvcSvc";
    public static final String ACTION_START = "com.hapticasensorics.phonecapturekiosk.action.START";
    public static final String ACTION_STOP = "com.hapticasensorics.phonecapturekiosk.action.STOP";
    public static final String ACTION_REFRESH = "com.hapticasensorics.phonecapturekiosk.action.REFRESH";
    public static final String ACTION_CAPTURE_IMAGE = "com.hapticasensorics.phonecapturekiosk.action.CAPTURE_IMAGE";
    public static final String ACTION_CAPTURE_VIDEO = "com.hapticasensorics.phonecapturekiosk.action.CAPTURE_VIDEO";
    public static final String ACTION_CAPTURE_HEALTH_PREVIEW = "com.hapticasensorics.phonecapturekiosk.action.CAPTURE_HEALTH_PREVIEW";

    private static final int NOTIFICATION_ID = 1001;
    private static final String NOTIFICATION_CHANNEL_ID = "capture_service";
    private static final int UGREEN_VENDOR_ID = 11145;
    private static final int UGREEN_PRODUCT_ID = 22614;
    private static final int MACROSILICON_VENDOR_ID = 21325;
    private static final int MACROSILICON_USB_VIDEO_PRODUCT_ID = 8457;
    private static final int REQUEST_PREVIEW_WIDTH = 1280;
    private static final int REQUEST_PREVIEW_HEIGHT = 720;
    private static final String ACTION_USB_PERMISSION =
            "com.hapticasensorics.phonecapturekiosk.USB_PERMISSION";
    private static final long VIDEO_DURATION_MILLIS = 6000L;
    private static final long HEALTH_CHECK_INTERVAL_MS = 10000L;
    private static final long CLASSIFICATION_INTERVAL_MS = 300L;
    private static final long CLASSIFICATION_PERSIST_INTERVAL_MS = 1000L;
    private static final long PREVIEW_RECENT_FOR_CAPTURE_MS = 5000L;
    private static final long FROZEN_PREVIEW_RECOVERY_MS = 30000L;
    private static final long STALE_STATIC_RECORDING_GRACE_MS = 60000L;
    private static final long POLICY_CLIP_INTERVAL_MS = 30000L;
    private static final long POLICY_RECORDING_WARMUP_MS = 1000L;
    private static final long POLICY_PREVIEW_FRESHNESS_MS = 1500L;
    private static final long POLICY_REOPEN_COOLDOWN_MS = 5000L;
    private static final long POLICY_POST_CLIP_COOLDOWN_MS = 10000L;
    private static final long HARD_DEVICE_MISSING_REBOOT_MS = 180000L;
    private static final long MIN_AUTO_REBOOT_INTERVAL_MS = 900000L;
    private static final int POLICY_MIN_PREVIEW_FRAMES = 10;
    private static final int OPEN_FAILURES_BEFORE_STACK_RESET = 3;
    private static final long MIN_STACK_RESET_INTERVAL_MS = 15000L;
    private static final long MIN_FROZEN_PREVIEW_REFRESH_INTERVAL_MS = 120000L;
    private static final long DEEP_IDLE_AFTER_NO_USEFUL_MS = 30000L;
    private static final long DEEP_IDLE_PROBE_INTERVAL_MS = 30000L;
    private static final long HEALTH_PREVIEW_REFRESH_INTERVAL_MS = 5000L;
    private static final long REOPEN_SETTLE_DELAY_MS = 1500L;
    private static final long REOPEN_PROBE_WINDOW_MS = 8000L;
    private static final long USB_PERMISSION_REQUEST_TIMEOUT_MS = 15000L;
    private static final double ANALYSIS_REGION_X_INSET_FRACTION = 0.30;
    private static final double ANALYSIS_REGION_Y_INSET_FRACTION = 0.08;

    private MultiCameraClient cameraClient;
    private MultiCameraClient.Camera activeCamera;
    private UsbDevice activeDevice;
    private USBMonitor.UsbControlBlock activeControlBlock;
    private SurfaceTexture headlessSurfaceTexture;
    private Surface headlessPreviewSurface;
    private boolean pendingCapture;
    private boolean pendingHealthPreview;
    private File pendingCaptureTarget;
    private boolean pendingVideoCapture;
    private boolean permissionRequestInFlight;
    private long permissionRequestStartedAtMs;
    private boolean openInFlight;
    private boolean recordingInFlight;
    private File activeVideoTarget;
    private Nv21Mp4Recorder activeRecorder;
    private int previewWidth = REQUEST_PREVIEW_WIDTH;
    private int previewHeight = REQUEST_PREVIEW_HEIGHT;
    private long lastPreviewFrameAtMs;
    private long lastHealthPreviewSavedAtMs;
    private long lastClassificationAtMs;
    private long lastClassificationPersistAtMs;
    private long lastSessionOpenAtMs;
    private long lastSessionReopenAtMs;
    private long activeVideoStartedAtMs;
    private long lastStackResetAtMs;
    private long lastPreviewWatchAtMs;
    private long lastPreviewWatchCount;
    private long recordEligibleSinceAtMs;
    private long lastPolicyClipStartedAtMs;
    private long lastPolicyClipCompletedAtMs;
    private long staleStaticSinceAtMs;
    private long deviceMissingSinceAtMs;
    private long lastAutoRecoveryAttemptAtMs;
    private long previewFrameCount;
    private int consecutiveOpenFailures;
    private int[] previousLumaSamples;
    private FrameClassification lastClassification = FrameClassification.initial();
    private String statusMessage = "Service starting";
    private String lastOpenErrorMessage = "";
    private boolean cameraClientRegistered;
    private boolean serviceActive;
    private boolean policyWantsRecording;
    private boolean hadHealthyCaptureSessionThisBoot;
    private PowerManager.WakeLock serviceWakeLock;
    private CaptureRuntimeMode runtimeMode = CaptureRuntimeMode.DEEP_IDLE;
    private long noUsefulSignalSinceAtMs;
    private long reopenWindowStartedAtMs;
    private long lastDeepIdleProbeAtMs;
    private int reopenGeneration;
    private int activeSessionGeneration;
    private Runnable pendingReopenRunnable;
    private LocalTransferServer transferServer;

    private enum CaptureRuntimeMode {
        ACTIVE,
        SOFT_IDLE,
        DEEP_IDLE,
        REOPENING
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable healthCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (!serviceActive) {
                return;
            }
            long now = System.currentTimeMillis();
            maybeExpireStaleUsbPermissionRequest(now);
            maybeTransitionToDeepIdle(now);
            if (runtimeMode == CaptureRuntimeMode.DEEP_IDLE) {
                maybeProbeDeepIdle(now);
                maybeRecoverFromHardFault(now);
                refreshAndPersist();
                scheduleHealthCheck();
                return;
            }
            if (ensureHeadlessSurface()
                    && activeDevice != null
                    && Boolean.TRUE.equals(hasUsbPermission(activeDevice))
                    && !permissionRequestInFlight
                    && !recordingInFlight) {
                if (activeCamera == null || !activeCamera.isCameraOpened()) {
                    Log.i(TAG, "Health check reopening closed UVC session.");
                    maybeOpenAuthorizedControlBlock();
                    maybeOpenActiveCamera();
                } else if (shouldRecoverFrozenPreview(now)) {
                    Log.w(TAG, "Health check detected frozen preview stream; refreshing session.");
                    reopenActiveSession("frozen preview stream", REOPEN_SETTLE_DELAY_MS);
                }
            }
            maybeConcludeReopenProbe(now);
            maybeRecoverFromHardFault(now);
            refreshAndPersist();
            scheduleHealthCheck();
        }
    };

    private boolean usbPermissionReceiverRegistered;
    private final android.content.BroadcastReceiver usbPermissionReceiver = new android.content.BroadcastReceiver() {
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
                updateStatusMessage("USB permission granted");
                maybeOpenAuthorizedControlBlock();
                maybeOpenActiveCamera();
            } else {
                updateStatusMessage("USB permission denied");
            }
            refreshAndPersist();
        }
    };

    public static Intent buildServiceIntent(Context context, String action) {
        Intent intent = new Intent(context, UvcCaptureService.class);
        intent.setAction(action);
        return intent;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        serviceActive = true;
        transitionRuntimeMode(CaptureRuntimeMode.REOPENING, "service create");
        startForegroundInternal();
        registerUsbPermissionReceiver();
        cameraClient = new MultiCameraClient(this, this);
        cameraClient.openDebug(true);
        cameraClient.register();
        cameraClientRegistered = true;
        ensureHeadlessSurface();
        startTransferServer();
        updateStatusMessage("Capture service started");
        discoverExistingDevices();
        scheduleHealthCheck();
        refreshAndPersist();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (action == null) {
            action = ACTION_START;
        }
        Log.i(TAG, "onStartCommand action=" + action);
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        handleAction(action);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        serviceActive = false;
        mainHandler.removeCallbacks(healthCheckRunnable);
        if (activeRecorder != null) {
            activeRecorder.stop();
            activeRecorder = null;
        }
        closeActiveCamera();
        releaseActiveControlBlock();
        releaseHeadlessSurface();
        if (cameraClient != null) {
            if (cameraClientRegistered) {
                cameraClient.unRegister();
                cameraClientRegistered = false;
            }
            cameraClient.destroy();
            cameraClient = null;
        }
        unregisterUsbPermissionReceiver();
        releaseServiceWakeLock();
        stopTransferServer();
        updateStatusMessage("Capture service stopped");
        refreshAndPersist();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onAttachDev(UsbDevice device) {
        if (!isSupportedCaptureDevice(device)) {
            return;
        }
        Log.i(TAG, "UVC device attached: " + device.getDeviceName());
        activeDevice = device;
        if (runtimeMode == CaptureRuntimeMode.DEEP_IDLE) {
            transitionRuntimeMode(CaptureRuntimeMode.REOPENING, "device attached");
        }
        updateStatusMessage("Capture dongle attached");
        maybeOpenAuthorizedControlBlock();
        maybeOpenActiveCamera();
        refreshAndPersist();
    }

    @Override
    public void onDetachDec(UsbDevice device) {
        if (!isSameDevice(device, activeDevice)) {
            return;
        }
        Log.i(TAG, "UVC device detached: " + device.getDeviceName());
        closeActiveCamera();
        activeDevice = null;
        releaseActiveControlBlock();
        clearUsbPermissionRequestState();
        openInFlight = false;
        transitionRuntimeMode(CaptureRuntimeMode.SOFT_IDLE, "device detached");
        applySyntheticClassification(FrameClassification.deviceMissing());
        updateStatusMessage("Capture dongle missing");
        refreshAndPersist();
    }

    @Override
    public void onConnectDev(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
        if (!isSupportedCaptureDevice(device)) {
            return;
        }
        Log.i(TAG, "UVC device connected: " + device.getDeviceName());
        activeDevice = device;
        activeControlBlock = ctrlBlock;
        clearUsbPermissionRequestState();
        transitionRuntimeMode(CaptureRuntimeMode.REOPENING, "control block connected");
        ensureActiveCamera();
        maybeOpenActiveCamera();
        updateStatusMessage("UVC control block connected");
        refreshAndPersist();
    }

    @Override
    public void onDisConnectDec(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
        if (!isSameDevice(device, activeDevice)) {
            return;
        }
        Log.i(TAG, "UVC device disconnected: " + device.getDeviceName());
        closeActiveCamera();
        releaseActiveControlBlock();
        openInFlight = false;
        transitionRuntimeMode(CaptureRuntimeMode.SOFT_IDLE, "control block disconnected");
        applySyntheticClassification(FrameClassification.deviceMissing());
        updateStatusMessage("UVC control block disconnected");
        refreshAndPersist();
    }

    @Override
    public void onCancelDev(UsbDevice device) {
        if (!isSameDevice(device, activeDevice)) {
            return;
        }
        Log.w(TAG, "USB permission canceled for: " + device.getDeviceName());
        pendingCapture = false;
        pendingCaptureTarget = null;
        pendingVideoCapture = false;
        recordingInFlight = false;
        activeVideoTarget = null;
        activeRecorder = null;
        clearUsbPermissionRequestState();
        policyWantsRecording = false;
        updateStatusMessage("USB permission canceled");
        refreshAndPersist();
    }

    @Override
    public void onCameraState(MultiCameraClient.Camera camera, ICameraStateCallBack.State state, String msg) {
        Log.i(TAG, "cameraState=" + state + " msg=" + msg);
        if (state == ICameraStateCallBack.State.OPENED) {
            openInFlight = false;
            consecutiveOpenFailures = 0;
            lastOpenErrorMessage = "";
            lastSessionOpenAtMs = System.currentTimeMillis();
            reopenWindowStartedAtMs = lastSessionOpenAtMs;
            activeSessionGeneration = reopenGeneration;
            hadHealthyCaptureSessionThisBoot = true;
            deviceMissingSinceAtMs = 0L;
            PreviewSize previewSize = camera.getPreviewSize();
            if (previewSize != null) {
                previewWidth = previewSize.getWidth();
                previewHeight = previewSize.getHeight();
                if (headlessSurfaceTexture != null) {
                    headlessSurfaceTexture.setDefaultBufferSize(previewWidth, previewHeight);
                }
            }
            updateStatusMessage("Capture session open");
            if (runtimeMode == CaptureRuntimeMode.DEEP_IDLE) {
                transitionRuntimeMode(CaptureRuntimeMode.REOPENING, "camera opened from deep idle");
            }
        }
        if (state == ICameraStateCallBack.State.ERROR) {
            openInFlight = false;
            pendingCapture = false;
            pendingCaptureTarget = null;
            pendingVideoCapture = false;
            consecutiveOpenFailures += 1;
            lastOpenErrorMessage = msg == null ? "" : msg;
            updateStatusMessage("Capture session error: " + msg);
            maybeRecoverFromRepeatedOpenFailure(msg);
        }
        if (state == ICameraStateCallBack.State.CLOSED) {
            openInFlight = false;
            if (runtimeMode != CaptureRuntimeMode.DEEP_IDLE && serviceActive) {
                transitionRuntimeMode(CaptureRuntimeMode.SOFT_IDLE, "camera closed");
            }
            updateStatusMessage("Capture session closed");
        }
        refreshAndPersist();
    }

    private void handleAction(String action) {
        ensureHeadlessSurface();
        if (ACTION_REFRESH.equals(action) || ACTION_START.equals(action)) {
            transitionRuntimeMode(CaptureRuntimeMode.REOPENING, "manual refresh");
            discoverExistingDevices();
            maybeOpenAuthorizedControlBlock();
            maybeOpenActiveCamera();
            updateStatusMessage("Refreshed capture service");
        } else if (ACTION_CAPTURE_IMAGE.equals(action)) {
            if (runtimeMode == CaptureRuntimeMode.DEEP_IDLE) {
                transitionRuntimeMode(CaptureRuntimeMode.REOPENING, "still capture wake");
            }
            requestImageCapture();
        } else if (ACTION_CAPTURE_HEALTH_PREVIEW.equals(action)) {
            if (runtimeMode == CaptureRuntimeMode.DEEP_IDLE) {
                transitionRuntimeMode(CaptureRuntimeMode.REOPENING, "health preview wake");
            }
            requestHealthPreviewCapture();
        } else if (ACTION_CAPTURE_VIDEO.equals(action)) {
            if (runtimeMode == CaptureRuntimeMode.DEEP_IDLE) {
                transitionRuntimeMode(CaptureRuntimeMode.REOPENING, "video capture wake");
            }
            requestVideoCapture();
        }
        refreshAndPersist();
    }

    private void startForegroundInternal() {
        createNotificationChannel();
        Intent launchIntent = new Intent(this, MainActivity.class);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, launchIntent, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                : new Notification.Builder(this);
        Notification notification = builder
                .setContentTitle("Phonecapture running")
                .setContentText("Jelly Star capture appliance is active")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void acquireServiceWakeLock() {
        if (serviceWakeLock != null && serviceWakeLock.isHeld()) {
            return;
        }
        PowerManager powerManager = getSystemService(PowerManager.class);
        if (powerManager == null) {
            Log.w(TAG, "PowerManager unavailable; cannot acquire wake lock");
            return;
        }
        serviceWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                getPackageName() + ":UvcCaptureService"
        );
        serviceWakeLock.setReferenceCounted(false);
        try {
            serviceWakeLock.acquire();
            Log.i(TAG, "Acquired partial wake lock for capture service");
        } catch (Exception e) {
            Log.e(TAG, "Failed to acquire wake lock", e);
        }
    }

    private void releaseServiceWakeLock() {
        if (serviceWakeLock == null) {
            return;
        }
        try {
            if (serviceWakeLock.isHeld()) {
                serviceWakeLock.release();
                Log.i(TAG, "Released partial wake lock for capture service");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to release wake lock", e);
        }
        serviceWakeLock = null;
    }

    private void transitionRuntimeMode(CaptureRuntimeMode newMode, String reason) {
        if (runtimeMode == newMode) {
            return;
        }
        Log.i(TAG, "runtime mode " + runtimeMode + " -> " + newMode + " reason=" + reason);
        runtimeMode = newMode;
        switch (newMode) {
            case ACTIVE:
            case SOFT_IDLE:
            case REOPENING:
            case DEEP_IDLE:
                acquireServiceWakeLock();
                break;
        }
        if (newMode != CaptureRuntimeMode.DEEP_IDLE) {
            lastDeepIdleProbeAtMs = 0L;
        }
    }

    private void cancelPendingReopen() {
        if (pendingReopenRunnable != null) {
            mainHandler.removeCallbacks(pendingReopenRunnable);
            pendingReopenRunnable = null;
        }
    }

    private void releaseActiveControlBlock() {
        if (activeControlBlock == null) {
            return;
        }
        try {
            activeControlBlock.close();
        } catch (Exception e) {
            Log.w(TAG, "Failed to close active USB control block cleanly", e);
        }
        activeControlBlock = null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Capture Service",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Persistent status for the phone capture appliance");
        manager.createNotificationChannel(channel);
    }

    private boolean ensureHeadlessSurface() {
        if (headlessSurfaceTexture != null && headlessPreviewSurface != null) {
            return true;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                headlessSurfaceTexture = new SurfaceTexture(false);
            } else {
                headlessSurfaceTexture = new SurfaceTexture(0);
            }
            headlessSurfaceTexture.setDefaultBufferSize(previewWidth, previewHeight);
            headlessSurfaceTexture.setOnFrameAvailableListener(
                    surfaceTexture -> Log.v(TAG, "Headless preview surface signaled a frame."),
                    mainHandler
            );
            headlessPreviewSurface = new Surface(headlessSurfaceTexture);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to create headless preview surface", e);
            updateStatusMessage("Failed to create headless preview surface: " + e.getMessage());
            refreshAndPersist();
            return false;
        }
    }

    private void releaseHeadlessSurface() {
        if (headlessPreviewSurface != null) {
            headlessPreviewSurface.release();
            headlessPreviewSurface = null;
        }
        if (headlessSurfaceTexture != null) {
            headlessSurfaceTexture.release();
            headlessSurfaceTexture = null;
        }
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
        activeDevice = null;
    }

    private void requestPermissionForActiveDevice() {
        Log.i(TAG, "requestPermissionForActiveDevice activeDevice="
                + (activeDevice == null ? "null" : activeDevice.getDeviceName())
                + " inflight=" + permissionRequestInFlight);
        if (activeDevice == null) {
            return;
        }
        if (permissionRequestInFlight) {
            return;
        }
        boolean hasPermission = Boolean.TRUE.equals(hasUsbPermission(activeDevice));
        if (hasPermission) {
            maybeOpenAuthorizedControlBlock();
            if (activeControlBlock != null) {
                maybeOpenActiveCamera();
                return;
            }
        }
        permissionRequestInFlight = true;
        permissionRequestStartedAtMs = System.currentTimeMillis();
        transitionRuntimeMode(CaptureRuntimeMode.REOPENING, "requesting USB permission");
        updateStatusMessage("Requesting USB permission");
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
        if (!ensureHeadlessSurface() || activeDevice == null || activeControlBlock == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            updateStatusMessage("Camera permission missing");
            return;
        }
        ensureActiveCamera();
        if (activeCamera == null || activeCamera.isCameraOpened() || openInFlight) {
            return;
        }
        openInFlight = true;
        transitionRuntimeMode(CaptureRuntimeMode.REOPENING, "opening camera");
        activeCamera.openCamera(headlessSurfaceTexture, buildCameraRequest());
    }

    private CameraRequest buildCameraRequest() {
        return new CameraRequest.Builder()
                .setPreviewWidth(REQUEST_PREVIEW_WIDTH)
                .setPreviewHeight(REQUEST_PREVIEW_HEIGHT)
                .setContinuousAFModel(true)
                .setContinuousAutoModel(true)
                .create();
    }

    private boolean hasRecentPreviewFrames() {
        if (activeCamera == null || !activeCamera.isCameraOpened()) {
            return false;
        }
        if (lastPreviewFrameAtMs == 0L) {
            return false;
        }
        return System.currentTimeMillis() - lastPreviewFrameAtMs <= PREVIEW_RECENT_FOR_CAPTURE_MS;
    }

    private void reopenActiveSession(String reason, long delayMs) {
        Log.i(TAG, "Reopening UVC session: " + reason + " delayMs=" + delayMs);
        lastSessionReopenAtMs = System.currentTimeMillis();
        reopenGeneration += 1;
        transitionRuntimeMode(CaptureRuntimeMode.REOPENING, reason);
        cancelPendingReopen();
        closeActiveCamera();
        releaseActiveControlBlock();
        openInFlight = false;
        lastPreviewFrameAtMs = 0L;
        reopenWindowStartedAtMs = 0L;
        pendingReopenRunnable = () -> {
            pendingReopenRunnable = null;
            discoverExistingDevices();
            maybeOpenAuthorizedControlBlock();
            maybeOpenActiveCamera();
            refreshAndPersist();
        };
        mainHandler.postDelayed(pendingReopenRunnable, delayMs);
    }

    private boolean shouldRecoverFrozenPreview(long now) {
        if (runtimeMode == CaptureRuntimeMode.DEEP_IDLE || runtimeMode == CaptureRuntimeMode.REOPENING) {
            return false;
        }
        if (activeCamera == null || !activeCamera.isCameraOpened()) {
            return false;
        }
        if (activeDevice == null || activeControlBlock == null) {
            return false;
        }
        if (lastPreviewFrameAtMs == 0L || previewFrameCount == 0L) {
            lastPreviewWatchAtMs = now;
            lastPreviewWatchCount = previewFrameCount;
            return false;
        }
        if (previewFrameCount != lastPreviewWatchCount) {
            lastPreviewWatchCount = previewFrameCount;
            lastPreviewWatchAtMs = now;
            return false;
        }
        if (lastPreviewWatchAtMs == 0L) {
            lastPreviewWatchAtMs = now;
            return false;
        }
        if (now - lastSessionReopenAtMs < MIN_FROZEN_PREVIEW_REFRESH_INTERVAL_MS) {
            return false;
        }
        return now - lastPreviewWatchAtMs >= FROZEN_PREVIEW_RECOVERY_MS;
    }

    private void maybeTransitionToDeepIdle(long now) {
        if (runtimeMode == CaptureRuntimeMode.DEEP_IDLE || runtimeMode == CaptureRuntimeMode.REOPENING) {
            return;
        }
        if (recordingInFlight || pendingVideoCapture || pendingCapture || openInFlight || permissionRequestInFlight) {
            return;
        }
        if (noUsefulSignalSinceAtMs == 0L) {
            return;
        }
        if (shouldKeepWarmWhileIdle()) {
            return;
        }
        if (now - noUsefulSignalSinceAtMs < DEEP_IDLE_AFTER_NO_USEFUL_MS) {
            return;
        }
        enterDeepIdle("no useful signal for " + (DEEP_IDLE_AFTER_NO_USEFUL_MS / 1000L) + "s");
    }

    private boolean shouldKeepWarmWhileIdle() {
        return activeDevice != null
                && activeControlBlock != null
                && activeCamera != null
                && activeCamera.isCameraOpened()
                && Boolean.TRUE.equals(hasUsbPermission(activeDevice));
    }

    private void enterDeepIdle(String reason) {
        Log.i(TAG, "Entering deep idle: " + reason);
        cancelPendingReopen();
        transitionRuntimeMode(CaptureRuntimeMode.DEEP_IDLE, reason);
        closeActiveCamera();
        releaseActiveControlBlock();
        openInFlight = false;
        clearUsbPermissionRequestState();
        reopenWindowStartedAtMs = 0L;
        lastDeepIdleProbeAtMs = System.currentTimeMillis();
        updateStatusMessage("Deep idle: " + reason);
        refreshAndPersist();
    }

    private void maybeProbeDeepIdle(long now) {
        if (runtimeMode != CaptureRuntimeMode.DEEP_IDLE) {
            return;
        }
        if (recordingInFlight || pendingVideoCapture || pendingCapture || openInFlight || permissionRequestInFlight) {
            return;
        }
        if (lastDeepIdleProbeAtMs != 0L
                && now - lastDeepIdleProbeAtMs < DEEP_IDLE_PROBE_INTERVAL_MS) {
            return;
        }
        discoverExistingDevices();
        if (activeDevice == null) {
            return;
        }
        lastDeepIdleProbeAtMs = now;
        Log.i(TAG, "Deep idle probe reopening capture session.");
        transitionRuntimeMode(CaptureRuntimeMode.REOPENING, "deep idle probe");
        updateStatusMessage("Deep idle probe");
        maybeOpenAuthorizedControlBlock();
        if (activeControlBlock == null && !Boolean.TRUE.equals(hasUsbPermission(activeDevice))) {
            requestPermissionForActiveDevice();
        }
        maybeOpenActiveCamera();
        refreshAndPersist();
    }

    private void maybeConcludeReopenProbe(long now) {
        if (runtimeMode != CaptureRuntimeMode.REOPENING) {
            return;
        }
        if (reopenWindowStartedAtMs == 0L) {
            return;
        }
        if (openInFlight || permissionRequestInFlight) {
            return;
        }
        if (recordingInFlight) {
            return;
        }
        if (isRecordEligibleLabel(lastClassification.label)) {
            return;
        }
        if (now - reopenWindowStartedAtMs < REOPEN_PROBE_WINDOW_MS) {
            return;
        }
        enterDeepIdle("probe window expired without useful signal");
    }

    private void scheduleHealthCheck() {
        mainHandler.removeCallbacks(healthCheckRunnable);
        if (!serviceActive) {
            return;
        }
        mainHandler.postDelayed(healthCheckRunnable, HEALTH_CHECK_INTERVAL_MS);
    }

    private void requestImageCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            updateStatusMessage("Camera permission is required before UVC capture");
            refreshAndPersist();
            return;
        }
        if (activeDevice == null) {
            discoverExistingDevices();
        }
        if (activeDevice == null) {
            updateStatusMessage("No supported capture device found");
            refreshAndPersist();
            return;
        }
        Boolean hasPermission = hasUsbPermission(activeDevice);
        if (!Boolean.TRUE.equals(hasPermission)) {
            pendingCapture = true;
            updateStatusMessage("Requesting USB permission for still capture");
            requestPermissionForActiveDevice();
            refreshAndPersist();
            return;
        }
        if (!hasRecentPreviewFrames()) {
            pendingCapture = true;
            updateStatusMessage("Waiting for fresh preview frames before still capture");
            maybeOpenAuthorizedControlBlock();
            maybeOpenActiveCamera();
            refreshAndPersist();
            return;
        }
        maybeOpenActiveCamera();
        if (activeCamera == null || !activeCamera.isCameraOpened()) {
            pendingCapture = true;
            updateStatusMessage("Opening UVC camera for still capture");
            refreshAndPersist();
            return;
        }

        File capturesDir = new File(getMediaRoot(), "uvc-captures");
        capturesDir.mkdirs();
        String name = "uvc-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".jpg";
        pendingCaptureTarget = new File(capturesDir, name);
        pendingCapture = true;
        updateStatusMessage("Waiting for one NV21 preview frame: " + pendingCaptureTarget.getAbsolutePath());
        refreshAndPersist();
    }

    private void requestHealthPreviewCapture() {
        if (activeDevice == null) {
            discoverExistingDevices();
        }
        if (activeDevice == null) {
            refreshAndPersist();
            return;
        }
        if (!Boolean.TRUE.equals(hasUsbPermission(activeDevice))) {
            requestPermissionForActiveDevice();
            refreshAndPersist();
            return;
        }
        if (!hasRecentPreviewFrames()) {
            maybeOpenAuthorizedControlBlock();
            maybeOpenActiveCamera();
            refreshAndPersist();
            return;
        }
        File target = new File(getDiagnosticsDirectory(), "health-preview.jpg");
        pendingHealthPreview = true;
        pendingCapture = true;
        pendingCaptureTarget = target;
    }

    private void requestVideoCapture() {
        if (recordingInFlight) {
            updateStatusMessage("A video recording is already in progress");
            refreshAndPersist();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            updateStatusMessage("Camera permission is required before video capture");
            refreshAndPersist();
            return;
        }
        if (activeDevice == null) {
            discoverExistingDevices();
        }
        if (activeDevice == null) {
            pendingVideoCapture = true;
            updateStatusMessage("No supported capture device found");
            refreshAndPersist();
            return;
        }
        if (!Boolean.TRUE.equals(hasUsbPermission(activeDevice))) {
            pendingVideoCapture = true;
            updateStatusMessage("USB permission is required before video capture");
            requestPermissionForActiveDevice();
            refreshAndPersist();
            return;
        }
        if (!hasRecentPreviewFrames()) {
            pendingVideoCapture = true;
            updateStatusMessage("Waiting for fresh preview frames before video capture");
            maybeOpenAuthorizedControlBlock();
            maybeOpenActiveCamera();
            refreshAndPersist();
            return;
        }
        maybeOpenAuthorizedControlBlock();
        maybeOpenActiveCamera();
        if (activeCamera == null || !activeCamera.isCameraOpened()) {
            pendingVideoCapture = true;
            updateStatusMessage("Opening UVC camera for video capture");
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
                        updateStatusMessage("Recording UVC MP4: " + target.getAbsolutePath());
                        refreshAndPersist();
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
                        lastPolicyClipCompletedAtMs = System.currentTimeMillis();
                        CaptureMetadataStore.recordVideo(
                                getMediaRoot(),
                                new File(path),
                                startedAtMs,
                                completedAtMs,
                                previewWidth,
                                previewHeight,
                                10,
                                "service"
                        );
                        writeLastVideo(path);
                        updateStatusMessage("Saved UVC MP4: " + path);
                        maybeEvaluateRecordingPolicy(System.currentTimeMillis());
                        refreshAndPersist();
                    }

                    @Override
                    public void onError(String message) {
                        pendingVideoCapture = false;
                        recordingInFlight = false;
                        activeVideoTarget = null;
                        activeRecorder = null;
                        activeVideoStartedAtMs = 0L;
                        lastPolicyClipCompletedAtMs = System.currentTimeMillis();
                        updateStatusMessage("Video capture failed: " + message);
                        maybeEvaluateRecordingPolicy(System.currentTimeMillis());
                        refreshAndPersist();
                    }
                }
        );
        updateStatusMessage("Starting short UVC video capture: " + target.getAbsolutePath());
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
        previewFrameCount += 1L;
        lastPreviewWatchCount = previewFrameCount;
        lastPreviewWatchAtMs = lastPreviewFrameAtMs;
        if (previewFrameCount <= 3L || previewFrameCount % 30L == 0L) {
            Log.i(TAG, "Preview callback #" + previewFrameCount
                    + " format=" + format
                    + " bytes=" + (data == null ? 0 : data.length)
                    + " size=" + previewWidth + "x" + previewHeight);
        }
        maybeClassifyPreviewFrame(data, format);
        maybeRefreshHealthPreview(data, format);
        if (pendingVideoCapture && !recordingInFlight && format == IPreviewDataCallBack.DataFormat.NV21) {
            requestVideoCapture();
        }
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
        boolean healthPreview = pendingHealthPreview;
        pendingHealthPreview = false;
        pendingCaptureTarget = null;
        try {
            saveNv21FrameAsJpeg(data, target);
            Log.i(TAG, "Saved preview frame to " + target.getAbsolutePath());
            if (!healthPreview) {
                CaptureMetadataStore.recordStill(
                        getMediaRoot(),
                        target,
                        System.currentTimeMillis(),
                        previewWidth,
                        previewHeight,
                        "service"
                );
                writeLastCapture(target.getAbsolutePath());
                updateStatusMessage("Saved preview frame: " + target.getAbsolutePath());
            }
            refreshAndPersist();
        } catch (IOException e) {
            Log.e(TAG, "Failed to save preview frame", e);
            if (!healthPreview) {
                updateStatusMessage("Capture failed: " + e.getMessage());
            }
            refreshAndPersist();
        }
    }

    private void maybeRefreshHealthPreview(byte[] data, IPreviewDataCallBack.DataFormat format) {
        if (format != IPreviewDataCallBack.DataFormat.NV21 || data == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (pendingHealthPreview) {
            return;
        }
        if (lastHealthPreviewSavedAtMs != 0L
                && now - lastHealthPreviewSavedAtMs < HEALTH_PREVIEW_REFRESH_INTERVAL_MS) {
            return;
        }
        try {
            saveNv21FrameAsJpeg(data, new File(getDiagnosticsDirectory(), "health-preview.jpg"));
            lastHealthPreviewSavedAtMs = now;
        } catch (IOException e) {
            Log.w(TAG, "Failed to refresh health preview", e);
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
        lastPreviewFrameAtMs = 0L;
        lastHealthPreviewSavedAtMs = 0L;
        previewFrameCount = 0L;
        lastPreviewWatchAtMs = 0L;
        lastPreviewWatchCount = 0L;
        previousLumaSamples = null;
        policyWantsRecording = false;
        deviceMissingSinceAtMs = 0L;
        if (activeCamera != null) {
            activeCamera.closeCamera();
            activeCamera = null;
        }
        clearStaleHealthPreview();
    }

    private void clearStaleHealthPreview() {
        File preview = new File(getDiagnosticsDirectory(), "health-preview.jpg");
        if (preview.exists() && !preview.delete()) {
            Log.w(TAG, "Failed to delete stale health preview: " + preview.getAbsolutePath());
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
            updateStatusMessage("Repeated UVC open failures; waiting before another stack reset");
            return;
        }
        lastStackResetAtMs = now;
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
        updateStatusMessage("Resetting USB camera stack: " + reason);
        transitionRuntimeMode(CaptureRuntimeMode.REOPENING, "stack reset");
        closeActiveCamera();
        releaseActiveControlBlock();
        permissionRequestInFlight = false;
        openInFlight = false;
        if (cameraClient != null) {
            if (cameraClientRegistered) {
                cameraClient.unRegister();
                cameraClientRegistered = false;
            }
            cameraClient.destroy();
            cameraClient = null;
        }
        cameraClient = new MultiCameraClient(this, this);
        cameraClient.openDebug(true);
        cameraClient.register();
        cameraClientRegistered = true;
        consecutiveOpenFailures = 0;
        reopenGeneration += 1;
        cancelPendingReopen();
        pendingReopenRunnable = () -> {
            pendingReopenRunnable = null;
            discoverExistingDevices();
            maybeOpenAuthorizedControlBlock();
            maybeOpenActiveCamera();
            refreshAndPersist();
        };
        mainHandler.postDelayed(pendingReopenRunnable, 500L);
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

    private void maybeExpireStaleUsbPermissionRequest(long now) {
        if (!permissionRequestInFlight || permissionRequestStartedAtMs == 0L) {
            return;
        }
        if (now - permissionRequestStartedAtMs < USB_PERMISSION_REQUEST_TIMEOUT_MS) {
            return;
        }
        Log.w(TAG, "USB permission request timed out after "
                + USB_PERMISSION_REQUEST_TIMEOUT_MS + "ms; clearing stuck request state.");
        clearUsbPermissionRequestState();
        updateStatusMessage("USB permission request timed out");
        refreshAndPersist();
        if (activeDevice != null && !Boolean.TRUE.equals(hasUsbPermission(activeDevice))) {
            requestPermissionForActiveDevice();
        }
    }

    private USBMonitor getUsbMonitor() {
        if (cameraClient == null) {
            return null;
        }
        try {
            Field usbMonitorField = MultiCameraClient.class.getDeclaredField("mUsbMonitor");
            usbMonitorField.setAccessible(true);
            return (USBMonitor) usbMonitorField.get(cameraClient);
        } catch (Exception e) {
            Log.e(TAG, "Failed to access USBMonitor", e);
            return null;
        }
    }

    private List<UsbDevice> safeGetDeviceList() {
        if (cameraClient == null) {
            return null;
        }
        try {
            return cameraClient.getDeviceList(null);
        } catch (Exception e) {
            Log.e(TAG, "Failed to enumerate USB devices", e);
            return null;
        }
    }

    private Boolean hasUsbPermission(UsbDevice device) {
        if (device == null || cameraClient == null) {
            return false;
        }
        try {
            return cameraClient.hasPermission(device);
        } catch (Exception e) {
            Log.e(TAG, "Failed to check USB permission", e);
            return false;
        }
    }

    private boolean isSupportedCaptureDevice(UsbDevice device) {
        if (device == null) {
            return false;
        }
        return (device.getVendorId() == UGREEN_VENDOR_ID && device.getProductId() == UGREEN_PRODUCT_ID)
                || (device.getVendorId() == MACROSILICON_VENDOR_ID
                && device.getProductId() == MACROSILICON_USB_VIDEO_PRODUCT_ID);
    }

    private boolean isSameDevice(UsbDevice left, UsbDevice right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.getVendorId() == right.getVendorId()
                && left.getProductId() == right.getProductId()
                && left.getDeviceId() == right.getDeviceId()
                && left.getDeviceName().equals(right.getDeviceName());
    }

    private void refreshAndPersist() {
        try {
            JSONObject status = buildStatusJson();
            writeJson(new File(getDiagnosticsDirectory(), "uvc-status.json"), status);
        } catch (JSONException | IOException e) {
            Log.e(TAG, "Failed to persist UVC status", e);
        }
    }

    private JSONObject buildStatusJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("timestamp", isoTimestamp());
        root.put("engineOwner", "service");
        root.put("serviceActive", serviceActive);
        root.put("runtimeMode", runtimeMode.name());
        root.put("cameraPermissionGranted", hasCameraPermission());
        root.put("ignoringBatteryOptimizations", isIgnoringBatteryOptimizations());
        root.put("wakeLockHeld", serviceWakeLock != null && serviceWakeLock.isHeld());
        root.put("cameraClientRegistered", cameraClientRegistered);
        root.put("deviceCount", safeGetDeviceList() == null ? 0 : safeGetDeviceList().size());
        root.put("activeDevice", activeDevice == null ? JSONObject.NULL : activeDevice.getDeviceName());
        root.put("hasPermission", activeDevice != null && Boolean.TRUE.equals(hasUsbPermission(activeDevice)));
        root.put("cameraOpened", activeCamera != null && activeCamera.isCameraOpened());
        root.put("hasActiveControlBlock", activeControlBlock != null);
        root.put("previewWidth", previewWidth);
        root.put("previewHeight", previewHeight);
        root.put("recordingInFlight", recordingInFlight);
        root.put("permissionRequestInFlight", permissionRequestInFlight);
        root.put("openInFlight", openInFlight);
        root.put("lastPreviewFrameAtMs", lastPreviewFrameAtMs);
        root.put("previewFrameCount", previewFrameCount);
        root.put("consecutiveOpenFailures", consecutiveOpenFailures);
        root.put("lastOpenErrorMessage", lastOpenErrorMessage);
        root.put("lastStatusMessage", statusMessage);
        root.put("policyWantsRecording", policyWantsRecording);
        root.put("recordEligibleSinceAtMs", recordEligibleSinceAtMs == 0L ? JSONObject.NULL : recordEligibleSinceAtMs);
        root.put("lastPolicyClipStartedAtMs", lastPolicyClipStartedAtMs == 0L ? JSONObject.NULL : lastPolicyClipStartedAtMs);
        root.put("lastPolicyClipCompletedAtMs", lastPolicyClipCompletedAtMs == 0L ? JSONObject.NULL : lastPolicyClipCompletedAtMs);
        root.put("staleStaticSinceAtMs", staleStaticSinceAtMs == 0L ? JSONObject.NULL : staleStaticSinceAtMs);
        root.put("noUsefulSignalSinceAtMs", noUsefulSignalSinceAtMs == 0L ? JSONObject.NULL : noUsefulSignalSinceAtMs);
        root.put("reopenWindowStartedAtMs", reopenWindowStartedAtMs == 0L ? JSONObject.NULL : reopenWindowStartedAtMs);
        root.put("lastDeepIdleProbeAtMs", lastDeepIdleProbeAtMs == 0L ? JSONObject.NULL : lastDeepIdleProbeAtMs);
        root.put("deviceMissingSinceAtMs", deviceMissingSinceAtMs == 0L ? JSONObject.NULL : deviceMissingSinceAtMs);
        root.put("hadHealthyCaptureSessionThisBoot", hadHealthyCaptureSessionThisBoot);
        root.put("deviceOwner", isDeviceOwner());
        root.put("hardRecoveryRebootAvailable", isDeviceOwner());
        root.put("signalClassification", lastClassification.toJson());
        root.put("battery", buildBatteryJson());
        root.put("storage", buildStorageJson());
        root.put("saveLocations", buildSaveLocationsJson());
        root.put("usbDiagnostics", buildUsbDiagnostics());
        root.put("lastCapturePath", readLastCapturePointer());
        root.put("lastVideoPath", readLastVideoPointer());
        root.put("transferServer", buildTransferServerJson());
        return root;
    }

    private JSONObject buildTransferServerJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("running", transferServer != null);
        root.put("port", LocalTransferServer.DEFAULT_PORT);
        JSONArray baseUrls = new JSONArray();
        for (String baseUrl : getTransferBaseUrls()) {
            baseUrls.put(baseUrl);
        }
        root.put("baseUrls", baseUrls);
        root.put("healthUrl", baseUrls.length() > 0
                ? baseUrls.getString(0) + "/healthz"
                : JSONObject.NULL);
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
        int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        root.put("levelPercent", level >= 0 && scale > 0 ? Math.round((level * 100f) / scale) : JSONObject.NULL);
        root.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL);
        root.put("plugged", plugged);
        return root;
    }

    private JSONObject buildStorageJson() throws JSONException {
        JSONObject root = new JSONObject();
        File mediaRoot = getMediaRoot();
        StatFs statFs = new StatFs(mediaRoot.getAbsolutePath());
        long total = statFs.getTotalBytes();
        long free = statFs.getAvailableBytes();
        root.put("path", mediaRoot.getAbsolutePath());
        root.put("totalBytes", total);
        root.put("freeBytes", free);
        return root;
    }

    private JSONObject buildSaveLocationsJson() throws JSONException {
        JSONObject root = new JSONObject();
        File mediaRoot = getMediaRoot();
        root.put("mediaRoot", mediaRoot.getAbsolutePath());
        root.put("diagnosticsDir", getDiagnosticsDirectory().getAbsolutePath());
        root.put("capturesDir", new File(mediaRoot, "uvc-captures").getAbsolutePath());
        root.put("videosDir", new File(mediaRoot, "uvc-videos").getAbsolutePath());
        root.put("captureIndexPath", CaptureMetadataStore.getIndexPath(mediaRoot));
        root.put("healthPreviewPath", new File(getDiagnosticsDirectory(), "health-preview.jpg").getAbsolutePath());
        return root;
    }

    private JSONObject buildUsbDiagnostics() throws JSONException {
        JSONObject root = new JSONObject();
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        JSONArray devices = new JSONArray();
        boolean foundSupportedCapture = false;
        if (usbManager != null) {
            for (UsbDevice device : usbManager.getDeviceList().values()) {
                JSONObject entry = new JSONObject();
                entry.put("deviceName", device.getDeviceName());
                entry.put("vendorId", device.getVendorId());
                entry.put("productId", device.getProductId());
                entry.put("manufacturerName", safeString(device.getManufacturerName()));
                entry.put("productName", safeString(device.getProductName()));
                entry.put("hasPermission", usbManager.hasPermission(device));
                devices.put(entry);
                if (isSupportedCaptureDevice(device)) {
                    foundSupportedCapture = true;
                }
            }
        }
        root.put("foundSupportedCaptureDevice", foundSupportedCapture);
        root.put("devices", devices);
        return root;
    }

    private void maybeClassifyPreviewFrame(byte[] data, IPreviewDataCallBack.DataFormat format) {
        if (format != IPreviewDataCallBack.DataFormat.NV21 || data == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastClassificationAtMs < CLASSIFICATION_INTERVAL_MS) {
            return;
        }
        lastClassificationAtMs = now;

        int[] lumaSamples = sampleLumaGrid(data, previewWidth, previewHeight, 16, 9);
        double motionScore = calculateAverageAbsDelta(previousLumaSamples, lumaSamples);
        previousLumaSamples = lumaSamples;

        StripeStats stripeStats = sampleVerticalStripes(data, previewWidth, previewHeight, 7);
        FrameClassification classification = classifyFrame(motionScore, stripeStats);
        if (activeDevice == null) {
            classification = new FrameClassification(
                    "device_missing",
                    stripeStats.yAvg,
                    stripeStats.satAvg,
                    motionScore,
                    stripeStats.boundaryCount
            );
        }
        if (!classification.label.equals(lastClassification.label)) {
            Log.i(TAG, "signal classification " + lastClassification.label + " -> "
                    + classification.label + " motion=" + round2(motionScore)
                    + " y=" + round2(stripeStats.yAvg)
                    + " sat=" + round2(stripeStats.satAvg)
                    + " bars=" + stripeStats.boundaryCount);
        }
        applyClassification(classification, now);
        if (now - lastClassificationPersistAtMs >= CLASSIFICATION_PERSIST_INTERVAL_MS) {
            lastClassificationPersistAtMs = now;
            refreshAndPersist();
        }
    }

    private void applyClassification(FrameClassification classification, long now) {
        if ("device_missing".equals(classification.label)) {
            if (deviceMissingSinceAtMs == 0L) {
                deviceMissingSinceAtMs = now;
            }
        } else {
            deviceMissingSinceAtMs = 0L;
        }
        boolean wasRecordEligible = isRecordEligibleLabel(lastClassification.label);
        boolean isRecordEligible = isRecordEligibleLabel(classification.label);
        if (!classification.label.equals(lastClassification.label)) {
            if ("stale_static".equals(classification.label)) {
                staleStaticSinceAtMs = now;
            } else {
                staleStaticSinceAtMs = 0L;
            }
            if (isRecordEligible && !wasRecordEligible) {
                recordEligibleSinceAtMs = now;
            } else if (!isRecordEligible) {
                recordEligibleSinceAtMs = 0L;
            }
        } else if (!"stale_static".equals(classification.label)) {
            staleStaticSinceAtMs = 0L;
        }
        if (!isRecordEligible) {
            recordEligibleSinceAtMs = 0L;
        } else if (recordEligibleSinceAtMs == 0L) {
            recordEligibleSinceAtMs = now;
        }
        lastClassification = classification;
        if (isRecordEligible) {
            noUsefulSignalSinceAtMs = 0L;
            transitionRuntimeMode("live".equals(classification.label)
                    ? CaptureRuntimeMode.ACTIVE
                    : CaptureRuntimeMode.SOFT_IDLE, "useful signal");
        } else if (noUsefulSignalSinceAtMs == 0L) {
            noUsefulSignalSinceAtMs = now;
            if (runtimeMode != CaptureRuntimeMode.DEEP_IDLE) {
                transitionRuntimeMode(CaptureRuntimeMode.SOFT_IDLE, "non-useful signal");
            }
        }
        maybeEvaluateRecordingPolicy(now);
    }

    private void applySyntheticClassification(FrameClassification classification) {
        long now = System.currentTimeMillis();
        if (!classification.label.equals(lastClassification.label)) {
            Log.i(TAG, "signal classification " + lastClassification.label + " -> " + classification.label);
        }
        applyClassification(classification, now);
    }

    private void maybeRecoverFromHardFault(long now) {
        if (!serviceActive || recordingInFlight || openInFlight || permissionRequestInFlight) {
            return;
        }
        if (!hadHealthyCaptureSessionThisBoot || !isDeviceOwner()) {
            return;
        }
        if (activeDevice != null || activeControlBlock != null) {
            return;
        }
        if (safeGetDeviceList() != null && !safeGetDeviceList().isEmpty()) {
            return;
        }
        if (deviceMissingSinceAtMs == 0L) {
            deviceMissingSinceAtMs = now;
            return;
        }
        if (now - deviceMissingSinceAtMs < HARD_DEVICE_MISSING_REBOOT_MS) {
            return;
        }
        if (now - lastAutoRecoveryAttemptAtMs < MIN_AUTO_REBOOT_INTERVAL_MS) {
            return;
        }
        lastAutoRecoveryAttemptAtMs = now;
        triggerDeviceOwnerReboot("hard device_missing fault");
    }

    private boolean isDeviceOwner() {
        DevicePolicyManager dpm = getSystemService(DevicePolicyManager.class);
        return dpm != null && dpm.isDeviceOwnerApp(getPackageName());
    }

    private void triggerDeviceOwnerReboot(String reason) {
        DevicePolicyManager dpm = getSystemService(DevicePolicyManager.class);
        if (dpm == null) {
            return;
        }
        updateStatusMessage("Rebooting appliance for recovery: " + reason);
        refreshAndPersist();
        try {
            dpm.reboot(new ComponentName(this, PhonecaptureDeviceAdminReceiver.class));
        } catch (SecurityException e) {
            Log.e(TAG, "Device-owner reboot denied", e);
            updateStatusMessage("Automatic reboot denied: " + e.getMessage());
            refreshAndPersist();
        } catch (Exception e) {
            Log.e(TAG, "Automatic reboot failed", e);
            updateStatusMessage("Automatic reboot failed: " + e.getMessage());
            refreshAndPersist();
        }
    }

    private void maybeEvaluateRecordingPolicy(long now) {
        boolean wantsRecording = shouldPolicyRecord(now);
        if (wantsRecording != policyWantsRecording) {
            Log.i(TAG, "recording policy " + (policyWantsRecording ? "record" : "idle")
                    + " -> " + (wantsRecording ? "record" : "idle")
                    + " classification=" + lastClassification.label);
        }
        policyWantsRecording = wantsRecording;

        if (!policyWantsRecording) {
            return;
        }

        if (!recordingInFlight && !pendingVideoCapture && isReadyForPolicyRecording(now)) {
            lastPolicyClipStartedAtMs = now;
            requestVideoCapture();
        }
    }

    private boolean shouldPolicyRecord(long now) {
        if (!serviceActive || activeDevice == null) {
            return false;
        }
        switch (lastClassification.label) {
            case "live":
                return true;
            case "stale_static":
                return staleStaticSinceAtMs == 0L
                        || now - staleStaticSinceAtMs < STALE_STATIC_RECORDING_GRACE_MS;
            case "blank_dark":
            case "no_signal_colorbars":
            case "device_missing":
            case "initializing":
            default:
                return false;
        }
    }

    private boolean isReadyForPolicyRecording(long now) {
        if (recordEligibleSinceAtMs == 0L) {
            return false;
        }
        if (now - recordEligibleSinceAtMs < POLICY_RECORDING_WARMUP_MS) {
            return false;
        }
        if (previewFrameCount < POLICY_MIN_PREVIEW_FRAMES) {
            return false;
        }
        if (lastPreviewFrameAtMs == 0L || now - lastPreviewFrameAtMs > POLICY_PREVIEW_FRESHNESS_MS) {
            return false;
        }
        if (lastSessionReopenAtMs != 0L && now - lastSessionReopenAtMs < POLICY_REOPEN_COOLDOWN_MS) {
            return false;
        }
        if (lastPolicyClipCompletedAtMs != 0L
                && now - lastPolicyClipCompletedAtMs < POLICY_POST_CLIP_COOLDOWN_MS) {
            return false;
        }
        return lastPolicyClipStartedAtMs == 0L
                || now - lastPolicyClipStartedAtMs >= POLICY_CLIP_INTERVAL_MS;
    }

    private boolean isRecordEligibleLabel(String label) {
        return "live".equals(label) || "stale_static".equals(label);
    }

    private FrameClassification classifyFrame(double motionScore, StripeStats stats) {
        // Let clear temporal change win before bar/static heuristics so animated
        // external-display content is not mistaken for stale or no-signal frames.
        if (motionScore >= 2.5) {
            return new FrameClassification("live", stats.yAvg, stats.satAvg, motionScore, stats.boundaryCount);
        }
        if (stats.yAvg < 20.0 && stats.satAvg < 18.0) {
            return new FrameClassification("blank_dark", stats.yAvg, stats.satAvg, motionScore, stats.boundaryCount);
        }
        if (stats.yAvg > 90.0 && stats.satAvg > 75.0 && stats.boundaryCount >= 5) {
            return new FrameClassification("no_signal_colorbars", stats.yAvg, stats.satAvg, motionScore, stats.boundaryCount);
        }
        return new FrameClassification("stale_static", stats.yAvg, stats.satAvg, motionScore, stats.boundaryCount);
    }

    private int[] sampleLumaGrid(byte[] nv21, int width, int height, int columns, int rows) {
        int[] result = new int[columns * rows];
        int index = 0;
        int minX = analysisMinX(width);
        int maxX = analysisMaxX(width);
        int minY = analysisMinY(height);
        int maxY = analysisMaxY(height);
        int regionWidth = Math.max(1, maxX - minX);
        int regionHeight = Math.max(1, maxY - minY);
        int xStep = Math.max(1, regionWidth / (columns + 1));
        int yStep = Math.max(1, regionHeight / (rows + 1));
        for (int row = 1; row <= rows; row++) {
            int y = Math.min(maxY, minY + (row * yStep));
            for (int col = 1; col <= columns; col++) {
                int x = Math.min(maxX, minX + (col * xStep));
                result[index++] = nv21[(y * width) + x] & 0xff;
            }
        }
        return result;
    }

    private StripeStats sampleVerticalStripes(byte[] nv21, int width, int height, int stripeCount) {
        double[] stripeY = new double[stripeCount];
        double[] stripeSat = new double[stripeCount];
        int uvOffset = width * height;
        int minX = analysisMinX(width);
        int maxX = analysisMaxX(width);
        int minY = analysisMinY(height);
        int maxY = analysisMaxY(height);
        int regionWidth = Math.max(2, maxX - minX);
        int regionHeight = Math.max(2, maxY - minY);
        int sampleRows = Math.max(8, Math.min(24, height / 30));
        int sampleColsPerStripe = Math.max(3, Math.min(8, regionWidth / (stripeCount * 20)));
        for (int stripe = 0; stripe < stripeCount; stripe++) {
            int xCenter = Math.min(maxX, Math.round(minX + ((stripe + 0.5f) * regionWidth / stripeCount)));
            double yTotal = 0.0;
            double satTotal = 0.0;
            int samples = 0;
            for (int r = 1; r <= sampleRows; r++) {
                int y = Math.min(maxY, Math.round(minY + (r * (regionHeight / (float) (sampleRows + 1)))));
                for (int dx = -sampleColsPerStripe / 2; dx <= sampleColsPerStripe / 2; dx++) {
                    int x = Math.max(minX, Math.min(maxX, xCenter + dx));
                    int yIndex = (y * width) + x;
                    int uvIndex = uvOffset + ((y / 2) * width) + (x & ~1);
                    int luma = nv21[yIndex] & 0xff;
                    int v = nv21[uvIndex] & 0xff;
                    int u = nv21[uvIndex + 1] & 0xff;
                    yTotal += luma;
                    satTotal += Math.abs(u - 128) + Math.abs(v - 128);
                    samples++;
                }
            }
            stripeY[stripe] = samples == 0 ? 0.0 : yTotal / samples;
            stripeSat[stripe] = samples == 0 ? 0.0 : satTotal / samples;
        }
        int boundaryCount = 0;
        for (int i = 1; i < stripeCount; i++) {
            if (Math.abs(stripeY[i] - stripeY[i - 1]) > 18.0
                    || Math.abs(stripeSat[i] - stripeSat[i - 1]) > 30.0) {
                boundaryCount++;
            }
        }
        return new StripeStats(average(stripeY), average(stripeSat), boundaryCount);
    }

    private int analysisMinX(int width) {
        return clampAnalysisInset((int) Math.round(width * ANALYSIS_REGION_X_INSET_FRACTION), width);
    }

    private int analysisMaxX(int width) {
        int inset = clampAnalysisInset((int) Math.round(width * ANALYSIS_REGION_X_INSET_FRACTION), width);
        return Math.max(inset, width - inset - 1);
    }

    private int analysisMinY(int height) {
        return clampAnalysisInset((int) Math.round(height * ANALYSIS_REGION_Y_INSET_FRACTION), height);
    }

    private int analysisMaxY(int height) {
        int inset = clampAnalysisInset((int) Math.round(height * ANALYSIS_REGION_Y_INSET_FRACTION), height);
        return Math.max(inset, height - inset - 1);
    }

    private int clampAnalysisInset(int inset, int dimension) {
        return Math.max(0, Math.min(inset, Math.max(0, (dimension / 2) - 2)));
    }

    private double calculateAverageAbsDelta(int[] previous, int[] current) {
        if (previous == null || current == null || previous.length != current.length) {
            return 0.0;
        }
        double total = 0.0;
        for (int i = 0; i < current.length; i++) {
            total += Math.abs(current[i] - previous[i]);
        }
        return total / current.length;
    }

    private double average(double[] values) {
        if (values.length == 0) {
            return 0.0;
        }
        double total = 0.0;
        for (double value : values) {
            total += value;
        }
        return total / values.length;
    }

    private boolean hasCameraPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        PowerManager powerManager = getSystemService(PowerManager.class);
        return powerManager != null && powerManager.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void updateStatusMessage(String message) {
        statusMessage = message;
        Log.i(TAG, message);
    }

    private String isoTimestamp() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date());
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    private File getMediaRoot() {
        File root = new File(getExternalMediaDirs()[0], ".");
        root.mkdirs();
        return root;
    }

    private File getDiagnosticsDirectory() {
        File diagnosticsDir = new File(getMediaRoot(), "diagnostics");
        diagnosticsDir.mkdirs();
        return diagnosticsDir;
    }

    private void writeJson(File target, JSONObject json) throws IOException {
        if (target.getParentFile() != null) {
            target.getParentFile().mkdirs();
        }
        try (FileOutputStream outputStream = new FileOutputStream(target, false)) {
            outputStream.write(json.toString().getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        }
    }

    private void startTransferServer() {
        stopTransferServer();
        try {
            transferServer = new LocalTransferServer(getMediaRoot());
            transferServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            Log.i(TAG, "Local transfer server listening on port " + LocalTransferServer.DEFAULT_PORT);
        } catch (IOException e) {
            transferServer = null;
            Log.e(TAG, "Failed to start local transfer server", e);
            updateStatusMessage("Local transfer server failed: " + e.getMessage());
        }
    }

    private void stopTransferServer() {
        if (transferServer != null) {
            transferServer.stop();
            transferServer = null;
        }
    }

    private List<String> getTransferBaseUrls() {
        List<String> baseUrls = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address)
                            || address.isLoopbackAddress()
                            || address.isLinkLocalAddress()) {
                        continue;
                    }
                    baseUrls.add("http://" + address.getHostAddress() + ":" + LocalTransferServer.DEFAULT_PORT);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to enumerate transfer server interfaces", e);
        }
        return baseUrls;
    }

    private void writeLastCapture(String path) {
        writePointerFile("last-capture.txt", path);
    }

    private void writeLastVideo(String path) {
        writePointerFile("last-video.txt", path);
    }

    private void writePointerFile(String name, String value) {
        File target = new File(getDiagnosticsDirectory(), name);
        try (FileOutputStream outputStream = new FileOutputStream(target, false)) {
            outputStream.write(value.getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        } catch (IOException e) {
            Log.e(TAG, "Failed to write pointer file " + name, e);
        }
    }

    private Object readLastCapturePointer() {
        File target = new File(getDiagnosticsDirectory(), "last-capture.txt");
        if (!target.exists()) {
            return JSONObject.NULL;
        }
        try {
            return new String(java.nio.file.Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            Log.e(TAG, "Failed to read last-capture.txt", e);
            return target.getAbsolutePath();
        }
    }

    private Object readLastVideoPointer() {
        File target = new File(getDiagnosticsDirectory(), "last-video.txt");
        if (!target.exists()) {
            return JSONObject.NULL;
        }
        try {
            return new String(java.nio.file.Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            Log.e(TAG, "Failed to read last-video.txt", e);
            return target.getAbsolutePath();
        }
    }

    private static final class StripeStats {
        final double yAvg;
        final double satAvg;
        final int boundaryCount;

        StripeStats(double yAvg, double satAvg, int boundaryCount) {
            this.yAvg = yAvg;
            this.satAvg = satAvg;
            this.boundaryCount = boundaryCount;
        }
    }

    private static final class FrameClassification {
        static FrameClassification initial() {
            return new FrameClassification("initializing", 0.0, 0.0, 0.0, 0);
        }

        static FrameClassification deviceMissing() {
            return new FrameClassification("device_missing", 0.0, 0.0, 0.0, 0);
        }

        final String label;
        final double yAvg;
        final double satAvg;
        final double motionScore;
        final int colorBarBoundaryCount;

        FrameClassification(String label, double yAvg, double satAvg, double motionScore, int colorBarBoundaryCount) {
            this.label = label;
            this.yAvg = yAvg;
            this.satAvg = satAvg;
            this.motionScore = motionScore;
            this.colorBarBoundaryCount = colorBarBoundaryCount;
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
