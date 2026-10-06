package com.hapticasensorics.phonecapturekiosk;

import fi.iki.elonen.NanoHTTPD;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Locale;

final class LocalTransferServer extends NanoHTTPD {
    static final int DEFAULT_PORT = 28781;

    private final File mediaRoot;

    LocalTransferServer(File mediaRoot) {
        super(DEFAULT_PORT);
        this.mediaRoot = mediaRoot;
    }

    @Override
    public Response serve(IHTTPSession session) {
        Method method = session.getMethod();
        if (method != Method.GET && method != Method.HEAD) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT,
                    "Only GET and HEAD are supported.\n");
        }

        String uri = session.getUri();
        if (uri == null || uri.isEmpty()) {
            uri = "/";
        }

        try {
            if ("/healthz".equals(uri)) {
                return plainText(Response.Status.OK, "ok\n");
            }
            if ("/api/status".equals(uri)) {
                return serveFile(new File(getDiagnosticsDirectory(), "uvc-status.json"),
                        "application/json; charset=utf-8", false);
            }
            if ("/api/index".equals(uri)) {
                return serveFile(new File(mediaRoot, CaptureMetadataStore.INDEX_FILENAME),
                        "application/x-ndjson; charset=utf-8", false);
            }
            if ("/api/diagnostics/health-preview.jpg".equals(uri)) {
                return serveFile(new File(getDiagnosticsDirectory(), "health-preview.jpg"),
                        "image/jpeg", false);
            }
            if (uri.startsWith("/api/files/videos/")) {
                return serveCategoryFile("uvc-videos", uri.substring("/api/files/videos/".length()));
            }
            if (uri.startsWith("/api/files/captures/")) {
                return serveCategoryFile("uvc-captures", uri.substring("/api/files/captures/".length()));
            }
        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT,
                    "Failed to serve request: " + e.getMessage() + "\n");
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found.\n");
    }

    private Response serveCategoryFile(String directoryName, String basename) throws IOException {
        if (!isSafeBasename(basename)) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT,
                    "Unsafe filename.\n");
        }
        File target = new File(new File(mediaRoot, directoryName), basename);
        Response response = serveFile(target, mimeTypeFor(target.getName()), true);
        if (response.getStatus() == Response.Status.OK) {
            response.addHeader("Content-Disposition", "attachment; filename=\"" + basename + "\"");
        }
        return response;
    }

    private Response serveFile(File file, String mimeType, boolean noStore) throws IOException {
        if (!file.exists() || !file.isFile()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Missing file.\n");
        }

        try {
            FileInputStream input = new FileInputStream(file);
            Response response = newFixedLengthResponse(Response.Status.OK, mimeType, input, file.length());
            response.addHeader("Accept-Ranges", "bytes");
            if (noStore) {
                response.addHeader("Cache-Control", "no-store");
            }
            return response;
        } catch (FileNotFoundException e) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Missing file.\n");
        }
    }

    private Response plainText(Response.Status status, String body) {
        Response response = newFixedLengthResponse(status, "text/plain; charset=utf-8", body);
        response.addHeader("Cache-Control", "no-store");
        return response;
    }

    private File getDiagnosticsDirectory() {
        File diagnosticsDir = new File(mediaRoot, "diagnostics");
        diagnosticsDir.mkdirs();
        return diagnosticsDir;
    }

    private boolean isSafeBasename(String basename) {
        return basename != null
                && !basename.isEmpty()
                && !basename.contains("/")
                && !basename.contains("\\")
                && !basename.contains("..");
    }

    private String mimeTypeFor(String basename) {
        String lower = basename.toLowerCase(Locale.US);
        if (lower.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        return "application/octet-stream";
    }
}
