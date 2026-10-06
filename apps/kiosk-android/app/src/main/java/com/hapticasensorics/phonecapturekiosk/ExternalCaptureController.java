package com.hapticasensorics.phonecapturekiosk;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class ExternalCaptureController {
    private static final String TAG = "PhonecaptureKiosk";
    private static final long TIMEOUT_MS = 15000L;

    public interface Callback {
        void onSuccess(CaptureReport report);

        void onError(String message);
    }

    public static final class CaptureReport {
        public final String captureId;
        public final String cameraId;
        public final int width;
        public final int height;
        public final int savedFrames;
        public final File captureDirectory;
        public final File manifestFile;

        CaptureReport(
                String captureId,
                String cameraId,
                int width,
                int height,
                int savedFrames,
                File captureDirectory,
                File manifestFile
        ) {
            this.captureId = captureId;
            this.cameraId = cameraId;
            this.width = width;
            this.height = height;
            this.savedFrames = savedFrames;
            this.captureDirectory = captureDirectory;
            this.manifestFile = manifestFile;
        }
    }

    private final Context appContext;
    private final CameraManager cameraManager;

    public ExternalCaptureController(Context context) {
        this.appContext = context.getApplicationContext();
        this.cameraManager = (CameraManager) appContext.getSystemService(Context.CAMERA_SERVICE);
    }

    public void captureFrames(int requestedFrames, int warmupFrames, Callback callback) {
        try {
            CameraChoice choice = selectExternalCamera();
            if (choice == null) {
                callback.onError("No external camera found in Camera2 enumeration.");
                return;
            }

            File mediaRoot = getMediaRoot();
            File capturesRoot = new File(mediaRoot, "captures");
            capturesRoot.mkdirs();

            String captureId = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File captureDirectory = new File(capturesRoot, "capture-" + captureId);
            captureDirectory.mkdirs();

            new CaptureSessionRunner(
                    choice,
                    requestedFrames,
                    warmupFrames,
                    captureId,
                    captureDirectory,
                    callback
            ).start();
        } catch (CameraAccessException e) {
            callback.onError("Camera access failed: " + e.getMessage());
        }
    }

    private CameraChoice selectExternalCamera() throws CameraAccessException {
        for (String cameraId : cameraManager.getCameraIdList()) {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (lensFacing == null || lensFacing != CameraCharacteristics.LENS_FACING_EXTERNAL) {
                continue;
            }

            StreamConfigurationMap map =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                continue;
            }

            Size[] yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888);
            if (yuvSizes == null || yuvSizes.length == 0) {
                continue;
            }

            Size chosenSize = choosePreferredSize(yuvSizes);
            Range<Integer> fpsRange = chooseFpsRange(
                    characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            );
            return new CameraChoice(cameraId, chosenSize, fpsRange);
        }
        return null;
    }

    private Size choosePreferredSize(Size[] sizes) {
        for (Size size : sizes) {
            if (size.getWidth() == 1280 && size.getHeight() == 720) {
                return size;
            }
        }

        return Arrays.stream(sizes)
                .min(Comparator.comparingLong(size ->
                        Math.abs((long) size.getWidth() * size.getHeight() - (1280L * 720L))))
                .orElse(sizes[0]);
    }

    private Range<Integer> chooseFpsRange(Range<Integer>[] ranges) {
        if (ranges == null || ranges.length == 0) {
            return null;
        }

        Range<Integer> best = null;
        for (Range<Integer> range : ranges) {
            if (range.getLower() <= 10 && range.getUpper() >= 10) {
                if (best == null || range.getUpper() < best.getUpper()) {
                    best = range;
                }
            }
        }

        if (best != null) {
            return best;
        }

        return Arrays.stream(ranges)
                .min(Comparator.comparingInt(range -> Math.abs(range.getUpper() - 10)))
                .orElse(ranges[0]);
    }

    private File getMediaRoot() {
        File[] mediaDirs = appContext.getExternalMediaDirs();
        File base = mediaDirs != null && mediaDirs.length > 0 && mediaDirs[0] != null
                ? mediaDirs[0]
                : appContext.getExternalFilesDir(null);
        if (base == null) {
            base = appContext.getFilesDir();
        }
        if (!base.exists()) {
            base.mkdirs();
        }
        return base;
    }

    private final class CaptureSessionRunner {
        private final CameraChoice cameraChoice;
        private final int requestedFrames;
        private final int warmupFrames;
        private final String captureId;
        private final File captureDirectory;
        private final Callback callback;
        private final List<JSONObject> frameEntries = new ArrayList<>();

        private HandlerThread handlerThread;
        private Handler handler;
        private ImageReader imageReader;
        private CameraDevice cameraDevice;
        private CameraCaptureSession captureSession;
        private int receivedFrames;
        private int savedFrames;
        private boolean finished;

        CaptureSessionRunner(
                CameraChoice cameraChoice,
                int requestedFrames,
                int warmupFrames,
                String captureId,
                File captureDirectory,
                Callback callback
        ) {
            this.cameraChoice = cameraChoice;
            this.requestedFrames = requestedFrames;
            this.warmupFrames = warmupFrames;
            this.captureId = captureId;
            this.captureDirectory = captureDirectory;
            this.callback = callback;
        }

        void start() {
            handlerThread = new HandlerThread("ExternalCaptureController");
            handlerThread.start();
            handler = new Handler(handlerThread.getLooper());

            imageReader = ImageReader.newInstance(
                    cameraChoice.size.getWidth(),
                    cameraChoice.size.getHeight(),
                    ImageFormat.YUV_420_888,
                    Math.max(3, requestedFrames + warmupFrames)
            );
            imageReader.setOnImageAvailableListener(this::onImageAvailable, handler);
            handler.postDelayed(() -> fail("Timed out waiting for camera frames."), TIMEOUT_MS);

            try {
                cameraManager.openCamera(cameraChoice.cameraId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(CameraDevice camera) {
                        cameraDevice = camera;
                        createSession();
                    }

                    @Override
                    public void onDisconnected(CameraDevice camera) {
                        fail("Camera disconnected.");
                    }

                    @Override
                    public void onError(CameraDevice camera, int error) {
                        fail("Camera open error: " + error);
                    }
                }, handler);
            } catch (SecurityException | CameraAccessException e) {
                fail("Failed to open camera: " + e.getMessage());
            }
        }

        private void createSession() {
            try {
                cameraDevice.createCaptureSession(
                        Arrays.asList(imageReader.getSurface()),
                        new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(CameraCaptureSession session) {
                                captureSession = session;
                                startRepeating();
                            }

                            @Override
                            public void onConfigureFailed(CameraCaptureSession session) {
                                fail("Capture session configuration failed.");
                            }
                        },
                        handler
                );
            } catch (CameraAccessException e) {
                fail("Failed to create capture session: " + e.getMessage());
            }
        }

        private void startRepeating() {
            try {
                CaptureRequest.Builder builder =
                        cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                builder.addTarget(imageReader.getSurface());
                builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                if (cameraChoice.fpsRange != null) {
                    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, cameraChoice.fpsRange);
                }
                captureSession.setRepeatingRequest(builder.build(), null, handler);
            } catch (CameraAccessException e) {
                fail("Failed to start repeating request: " + e.getMessage());
            }
        }

        private void onImageAvailable(ImageReader reader) {
            Image image = reader.acquireLatestImage();
            if (image == null || finished) {
                return;
            }

            try {
                receivedFrames += 1;
                if (receivedFrames <= warmupFrames) {
                    return;
                }

                savedFrames += 1;
                String fileName = String.format(Locale.US, "frame-%03d.jpg", savedFrames);
                File frameFile = new File(captureDirectory, fileName);
                byte[] jpegBytes = imageToJpeg(image, 90);
                try (FileOutputStream outputStream = new FileOutputStream(frameFile)) {
                    outputStream.write(jpegBytes);
                }

                JSONObject entry = new JSONObject();
                entry.put("index", savedFrames);
                entry.put("file", fileName);
                entry.put("timestampNs", image.getTimestamp());
                entry.put("bytes", jpegBytes.length);
                frameEntries.add(entry);

                if (savedFrames >= requestedFrames) {
                    finishSuccess();
                }
            } catch (IOException | JSONException e) {
                fail("Failed while saving frames: " + e.getMessage());
            } finally {
                image.close();
            }
        }

        private void finishSuccess() {
            if (finished) {
                return;
            }
            finished = true;

            try {
                JSONObject manifest = new JSONObject();
                manifest.put("captureId", captureId);
                manifest.put("cameraId", cameraChoice.cameraId);
                manifest.put("width", cameraChoice.size.getWidth());
                manifest.put("height", cameraChoice.size.getHeight());
                manifest.put("requestedFrames", requestedFrames);
                manifest.put("warmupFrames", warmupFrames);
                manifest.put("savedFrames", savedFrames);
                if (cameraChoice.fpsRange != null) {
                    JSONObject fpsRange = new JSONObject();
                    fpsRange.put("lower", cameraChoice.fpsRange.getLower());
                    fpsRange.put("upper", cameraChoice.fpsRange.getUpper());
                    manifest.put("fpsRange", fpsRange);
                }

                JSONArray frames = new JSONArray();
                for (JSONObject entry : frameEntries) {
                    frames.put(entry);
                }
                manifest.put("frames", frames);

                File manifestFile = new File(captureDirectory, "manifest.json");
                try (FileOutputStream outputStream = new FileOutputStream(manifestFile)) {
                    outputStream.write(manifest.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }

                cleanup();
                callback.onSuccess(new CaptureReport(
                        captureId,
                        cameraChoice.cameraId,
                        cameraChoice.size.getWidth(),
                        cameraChoice.size.getHeight(),
                        savedFrames,
                        captureDirectory,
                        manifestFile
                ));
            } catch (JSONException | IOException e) {
                fail("Failed to write capture manifest: " + e.getMessage());
            }
        }

        private void fail(String message) {
            if (finished) {
                return;
            }
            finished = true;
            Log.e(TAG, message);
            cleanup();
            callback.onError(message);
        }

        private void cleanup() {
            if (captureSession != null) {
                try {
                    captureSession.stopRepeating();
                } catch (CameraAccessException ignored) {
                }
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            if (handlerThread != null) {
                handlerThread.quitSafely();
                handlerThread = null;
                handler = null;
            }
        }
    }

    private static byte[] imageToJpeg(Image image, int quality) throws IOException {
        byte[] nv21 = yuv420888ToNv21(image);
        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), quality, outputStream);
        return outputStream.toByteArray();
    }

    private static byte[] yuv420888ToNv21(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();
        byte[] nv21 = new byte[width * height * 3 / 2];

        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();
        int uRowStride = planes[1].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vRowStride = planes[2].getRowStride();
        int vPixelStride = planes[2].getPixelStride();

        int offset = 0;
        for (int row = 0; row < height; row++) {
            int rowStart = row * yRowStride;
            for (int col = 0; col < width; col++) {
                nv21[offset++] = yBuffer.get(rowStart + col * yPixelStride);
            }
        }

        for (int row = 0; row < height / 2; row++) {
            int uRowStart = row * uRowStride;
            int vRowStart = row * vRowStride;
            for (int col = 0; col < width / 2; col++) {
                nv21[offset++] = vBuffer.get(vRowStart + col * vPixelStride);
                nv21[offset++] = uBuffer.get(uRowStart + col * uPixelStride);
            }
        }

        return nv21;
    }

    private static final class CameraChoice {
        final String cameraId;
        final Size size;
        final Range<Integer> fpsRange;

        CameraChoice(String cameraId, Size size, Range<Integer> fpsRange) {
            this.cameraId = cameraId;
            this.size = size;
            this.fpsRange = fpsRange;
        }
    }
}
