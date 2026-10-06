package com.hapticasensorics.phonecapturekiosk;

import android.media.MediaMetadataRetriever;
import android.util.Log;

import androidx.exifinterface.media.ExifInterface;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

final class CaptureMetadataStore {
    static final String INDEX_FILENAME = "capture-index.jsonl";

    private static final String TAG = "CaptureMetadataStore";
    private static final Object LOCK = new Object();

    private CaptureMetadataStore() {
    }

    static String getIndexPath(File mediaRoot) {
        return new File(mediaRoot, INDEX_FILENAME).getAbsolutePath();
    }

    static void recordStill(File mediaRoot, File target, long capturedAtMs, int width, int height, String engineOwner) {
        setFileTimestamp(target, capturedAtMs);
        writeJpegExifTimestamp(target, capturedAtMs);
        JSONObject entry = new JSONObject();
        try {
            entry.put("schema", 1);
            entry.put("kind", "capture");
            entry.put("basename", target.getName());
            entry.put("category", "captures");
            entry.put("absolutePath", target.getAbsolutePath());
            entry.put("relativePath", relativePath(mediaRoot, target));
            entry.put("recordedAtMs", capturedAtMs);
            entry.put("recordedAtIso", isoTimestamp(capturedAtMs));
            entry.put("timezoneOffsetMinutes", timezoneOffsetMinutes(capturedAtMs));
            entry.put("width", width);
            entry.put("height", height);
            entry.put("engineOwner", engineOwner);
            appendEntry(mediaRoot, entry);
        } catch (Exception e) {
            Log.e(TAG, "Failed to append still metadata for " + target.getAbsolutePath(), e);
        }
    }

    static void recordVideo(
            File mediaRoot,
            File target,
            long startedAtMs,
            long completedAtMs,
            int width,
            int height,
            int fps,
            String engineOwner
    ) {
        long recordedAtMs = startedAtMs > 0L ? startedAtMs : completedAtMs;
        long durationMs = readVideoDurationMs(target);
        setFileTimestamp(target, recordedAtMs);
        JSONObject entry = new JSONObject();
        try {
            entry.put("schema", 1);
            entry.put("kind", "video");
            entry.put("basename", target.getName());
            entry.put("category", "videos");
            entry.put("absolutePath", target.getAbsolutePath());
            entry.put("relativePath", relativePath(mediaRoot, target));
            entry.put("recordedAtMs", recordedAtMs);
            entry.put("recordedAtIso", isoTimestamp(recordedAtMs));
            entry.put("startedAtMs", startedAtMs > 0L ? startedAtMs : JSONObject.NULL);
            entry.put("completedAtMs", completedAtMs > 0L ? completedAtMs : JSONObject.NULL);
            if (durationMs > 0L) {
                entry.put("durationMs", durationMs);
            } else if (startedAtMs > 0L && completedAtMs >= startedAtMs) {
                entry.put("durationMs", completedAtMs - startedAtMs);
            } else {
                entry.put("durationMs", JSONObject.NULL);
            }
            entry.put("timezoneOffsetMinutes", timezoneOffsetMinutes(recordedAtMs));
            entry.put("width", width);
            entry.put("height", height);
            entry.put("fps", fps);
            entry.put("engineOwner", engineOwner);
            appendEntry(mediaRoot, entry);
        } catch (Exception e) {
            Log.e(TAG, "Failed to append video metadata for " + target.getAbsolutePath(), e);
        }
    }

    private static long readVideoDurationMs(File target) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(target.getAbsolutePath());
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (duration == null || duration.isEmpty()) {
                return 0L;
            }
            return Long.parseLong(duration);
        } catch (Exception e) {
            Log.w(TAG, "Failed to read video duration for " + target.getAbsolutePath(), e);
            return 0L;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static void appendEntry(File mediaRoot, JSONObject entry) throws IOException {
        File target = new File(mediaRoot, INDEX_FILENAME);
        if (target.getParentFile() != null) {
            target.getParentFile().mkdirs();
        }
        synchronized (LOCK) {
            try (FileOutputStream outputStream = new FileOutputStream(target, true)) {
                outputStream.write(entry.toString().getBytes(StandardCharsets.UTF_8));
                outputStream.write('\n');
                outputStream.flush();
            }
        }
    }

    private static void setFileTimestamp(File target, long timestampMs) {
        if (timestampMs <= 0L) {
            return;
        }
        if (!target.setLastModified(timestampMs)) {
            Log.w(TAG, "Failed to set lastModified for " + target.getAbsolutePath());
        }
    }

    private static void writeJpegExifTimestamp(File target, long timestampMs) {
        try {
            ExifInterface exif = new ExifInterface(target.getAbsolutePath());
            String formatted = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(new Date(timestampMs));
            exif.setAttribute(ExifInterface.TAG_DATETIME, formatted);
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, formatted);
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, formatted);
            exif.saveAttributes();
        } catch (IOException e) {
            Log.w(TAG, "Failed to write EXIF timestamp for " + target.getAbsolutePath(), e);
        }
    }

    private static String relativePath(File mediaRoot, File target) {
        String mediaRootPath = mediaRoot.getAbsolutePath();
        String targetPath = target.getAbsolutePath();
        if (targetPath.startsWith(mediaRootPath + File.separator)) {
            return targetPath.substring(mediaRootPath.length() + 1);
        }
        return target.getName();
    }

    private static String isoTimestamp(long timestampMs) {
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);
        formatter.setTimeZone(TimeZone.getDefault());
        return formatter.format(new Date(timestampMs));
    }

    private static int timezoneOffsetMinutes(long timestampMs) {
        return TimeZone.getDefault().getOffset(timestampMs) / 60000;
    }
}
