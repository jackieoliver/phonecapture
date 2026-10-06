package com.hapticasensorics.phonecapturesourcestimulus;

import android.app.Presentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.app.KeyguardManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_SOURCE = "command_source";
    public static final String ACTION_SET_MODE = "com.hapticasensorics.phonecapturesourcestimulus.SET_MODE";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
            syncExternalPresentation();
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            syncExternalPresentation();
        }

        @Override
        public void onDisplayChanged(int displayId) {
            syncExternalPresentation();
        }
    };
    private final Runnable overlayTicker = new Runnable() {
        @Override
        public void run() {
            updateOverlay();
            handler.postDelayed(this, 250L);
        }
    };

    private FrameLayout stimulusHost;
    private TextView overlayText;
    private StimulusSurface activeSurface;
    private StimulusMode currentMode = StimulusMode.TEXT;
    private String lastSource = "launcher";
    private long modeStartedAtMs;
    private DisplayManager displayManager;
    private ExternalPresentation externalPresentation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        displayManager = getSystemService(DisplayManager.class);
        configureWindowForStimulus();
        buildUi();
        applySystemUi();
        handleIntent(getIntent());
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (displayManager != null) {
            displayManager.registerDisplayListener(displayListener, handler);
        }
        syncExternalPresentation();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        applySystemUi();
        requestKeyguardDismissal();
        if (activeSurface != null) {
            activeSurface.onStart();
        }
        syncExternalPresentation();
        handler.removeCallbacks(overlayTicker);
        handler.post(overlayTicker);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(overlayTicker);
        if (activeSurface != null) {
            activeSurface.onStop();
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (displayManager != null) {
            displayManager.unregisterDisplayListener(displayListener);
        }
        dismissExternalPresentation();
        super.onStop();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applySystemUi();
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        stimulusHost = new FrameLayout(this);
        root.addView(stimulusHost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        overlayText = new TextView(this);
        overlayText.setTextColor(Color.WHITE);
        overlayText.setTypeface(Typeface.MONOSPACE);
        overlayText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        overlayText.setBackgroundColor(0xA6000000);
        int padding = dp(this, 10);
        overlayText.setPadding(padding, padding, padding, padding);
        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START
        );
        overlayParams.setMargins(dp(this, 12), dp(this, 12), dp(this, 12), dp(this, 12));
        root.addView(overlayText, overlayParams);

        setContentView(root);
    }

    private void handleIntent(Intent intent) {
        StimulusMode requestedMode = StimulusMode.fromWireValue(intent.getStringExtra(EXTRA_MODE));
        String source = intent.getStringExtra(EXTRA_SOURCE);
        if (source == null || source.trim().isEmpty()) {
            source = Intent.ACTION_MAIN.equals(intent.getAction()) ? "launcher" : "adb-start";
        }
        switchMode(requestedMode, source);
    }

    private void switchMode(@NonNull StimulusMode mode, @NonNull String source) {
        if (activeSurface != null) {
            activeSurface.onStop();
        }
        stimulusHost.removeAllViews();
        activeSurface = createSurface(this, mode);
        currentMode = mode;
        lastSource = source;
        modeStartedAtMs = SystemClock.elapsedRealtime();
        stimulusHost.addView(activeSurface.asView(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        activeSurface.onStart();
        updateOverlay();
        syncExternalPresentation();
    }

    private StimulusSurface createSurface(Context context, StimulusMode mode) {
        switch (mode) {
            case UI:
                return new StaticUiSurface(context);
            case ANIMATION:
                return new AnimationSurface(context);
            case TIMER:
                return new TimerSurface(context);
            case TEST_PATTERN:
                return new TestPatternSurface(context);
            case TEXT:
            default:
                return new TextSurface(context);
        }
    }

    private void updateOverlay() {
        long elapsedMs = Math.max(0L, SystemClock.elapsedRealtime() - modeStartedAtMs);
        String debugText =
                "phonecapture-source-stimulus\n" +
                "mode=" + currentMode.wireValue() + "\n" +
                "source=" + lastSource + "\n" +
                "elapsed_ms=" + elapsedMs;
        overlayText.setText(debugText);
        if (externalPresentation != null) {
            externalPresentation.updateContent(currentMode, lastSource, modeStartedAtMs);
        }
    }

    private void applySystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void configureWindowForStimulus() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void requestKeyguardDismissal() {
        KeyguardManager keyguardManager = getSystemService(KeyguardManager.class);
        if (keyguardManager == null || !keyguardManager.isKeyguardLocked()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            keyguardManager.requestDismissKeyguard(this, null);
        }
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private void syncExternalPresentation() {
        Display targetDisplay = findExternalDisplay();
        if (targetDisplay == null) {
            dismissExternalPresentation();
            return;
        }
        if (externalPresentation != null && externalPresentation.getDisplay().getDisplayId() == targetDisplay.getDisplayId()) {
            externalPresentation.updateContent(currentMode, lastSource, modeStartedAtMs);
            return;
        }
        dismissExternalPresentation();
        externalPresentation = new ExternalPresentation(this, targetDisplay);
        externalPresentation.show();
        externalPresentation.updateContent(currentMode, lastSource, modeStartedAtMs);
    }

    private void dismissExternalPresentation() {
        if (externalPresentation != null) {
            externalPresentation.dismiss();
            externalPresentation = null;
        }
    }

    private Display findExternalDisplay() {
        if (displayManager == null) {
            return null;
        }
        Display[] displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (displays == null || displays.length == 0) {
            return null;
        }
        for (Display display : displays) {
            if (display != null && display.isValid()) {
                return display;
            }
        }
        return null;
    }

    interface StimulusSurface {
        View asView();

        void onStart();

        void onStop();
    }

    static final class TextSurface extends ScrollView implements StimulusSurface {
        TextSurface(Context context) {
            super(context);
            setFillViewport(true);
            setBackgroundColor(Color.rgb(248, 248, 242));

            TextView body = new TextView(context);
            body.setTextColor(Color.rgb(24, 24, 24));
            body.setTypeface(Typeface.MONOSPACE);
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
            int padding = dp(context, 20);
            body.setPadding(padding, padding, padding, padding);
            body.setMovementMethod(new ScrollingMovementMethod());
            body.setText(
                    "Phonecapture Deterministic Text Screen\n\n" +
                    "Objective:\n" +
                    "- static text-heavy source\n" +
                    "- dense glyphs for OCR and layout validation\n" +
                    "- stable alignment, punctuation, and number forms\n\n" +
                    "Paragraph 1:\n" +
                    "The quick brown fox jumps over the lazy dog. 0123456789. " +
                    "ABCDEFGHIJKLMNOPQRSTUVWXYZ. abcdefghijklmnopqrstuvwxyz.\n\n" +
                    "Paragraph 2:\n" +
                    "Meeting cadence: 08:30 check-in, 12:00 review, 17:15 handoff. " +
                    "Build refs: alpha-17, bravo-204, charlie-4096.\n\n" +
                    "Status lines:\n" +
                    "sensor.main = READY\n" +
                    "capture.policy = LIVE_ONLY\n" +
                    "frame.rate = 10 fps\n" +
                    "retention.codec = H264\n" +
                    "source.device = PIXEL_8A\n\n" +
                    "Receipt-like block:\n" +
                    "SKU   DESC                QTY  PRICE\n" +
                    "A104  USB-C CABLE          1   19.95\n" +
                    "B512  CAPTURE DONGLE       1   34.99\n" +
                    "C900  PHONE STAND          2   12.50\n" +
                    "TAX                          5.72\n" +
                    "TOTAL                       85.66\n\n" +
                    "Calendar:\n" +
                    "Mon  Tue  Wed  Thu  Fri  Sat  Sun\n" +
                    " 01   02   03   04   05   06   07\n" +
                    " 08   09   10   11   12   13   14\n" +
                    " 15   16   17   18   19   20   21\n\n" +
                    "End of static text stimulus."
            );
            addView(body, new LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT
            ));
        }

        @Override
        public View asView() {
            return this;
        }

        @Override
        public void onStart() {
        }

        @Override
        public void onStop() {
        }
    }

    static final class StaticUiSurface extends LinearLayout implements StimulusSurface {
        StaticUiSurface(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setBackgroundColor(Color.WHITE);
            int margin = dp(context, 14);
            int padding = dp(context, 16);

            addView(makeHeader(context, "Operations Dashboard", "Live preview disabled"));
            addView(makeCard(context, margin, padding, "Alerts", "2 critical\n5 warnings", Color.BLACK, Color.WHITE));
            addView(makeCard(context, margin, padding, "Tasks", "Approve build\nReview capture\nShip nightly", Color.WHITE, Color.BLACK));
            addView(makeButtonRow(context, margin, padding));
            addView(makeMetricStrip(context, margin, padding));
        }

        private View makeHeader(Context context, String title, String subtitle) {
            LinearLayout header = new LinearLayout(context);
            header.setOrientation(VERTICAL);
            header.setPadding(dp(context, 18), dp(context, 22), dp(context, 18), dp(context, 18));
            header.setBackgroundColor(Color.BLACK);

            TextView titleView = new TextView(context);
            titleView.setText(title);
            titleView.setTextColor(Color.WHITE);
            titleView.setTypeface(Typeface.DEFAULT_BOLD);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f);

            TextView subtitleView = new TextView(context);
            subtitleView.setText(subtitle);
            subtitleView.setTextColor(Color.rgb(208, 208, 208));
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);

            header.addView(titleView);
            header.addView(subtitleView);
            return header;
        }

        private View makeCard(Context context, int margin, int padding, String title, String body, int bg, int fg) {
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(VERTICAL);
            card.setBackgroundColor(bg);
            card.setPadding(padding, padding, padding, padding);
            LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
            params.setMargins(margin, margin, margin, 0);
            card.setLayoutParams(params);

            TextView titleView = new TextView(context);
            titleView.setText(title);
            titleView.setTextColor(fg);
            titleView.setTypeface(Typeface.DEFAULT_BOLD);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);

            TextView bodyView = new TextView(context);
            bodyView.setText(body);
            bodyView.setTextColor(fg);
            bodyView.setTypeface(Typeface.MONOSPACE);
            bodyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);

            card.addView(titleView);
            card.addView(bodyView);
            return card;
        }

        private View makeButtonRow(Context context, int margin, int padding) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(HORIZONTAL);
            LayoutParams rowParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            rowParams.setMargins(margin, margin, margin, 0);
            row.setLayoutParams(rowParams);
            row.addView(makeButton(context, "Arm", Color.BLACK, Color.WHITE, padding));
            row.addView(makeButton(context, "Pause", Color.WHITE, Color.BLACK, padding));
            row.addView(makeButton(context, "Ship", Color.rgb(0, 96, 255), Color.WHITE, padding));
            return row;
        }

        private View makeButton(Context context, String text, int bg, int fg, int padding) {
            TextView button = new TextView(context);
            button.setText(text);
            button.setGravity(Gravity.CENTER);
            button.setTextColor(fg);
            button.setTypeface(Typeface.DEFAULT_BOLD);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
            button.setBackgroundColor(bg);
            button.setPadding(padding, padding, padding, padding);
            LayoutParams params = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            params.setMargins(dp(context, 4), 0, dp(context, 4), 0);
            button.setLayoutParams(params);
            return button;
        }

        private View makeMetricStrip(Context context, int margin, int padding) {
            LinearLayout strip = new LinearLayout(context);
            strip.setOrientation(HORIZONTAL);
            strip.setBackgroundColor(Color.rgb(245, 245, 245));
            strip.setPadding(padding, padding, padding, padding);
            LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            params.setMargins(margin, margin, margin, margin);
            strip.setLayoutParams(params);
            strip.addView(makeMetric(context, "BAT", "83%"));
            strip.addView(makeMetric(context, "TEMP", "34C"));
            strip.addView(makeMetric(context, "FPS", "10"));
            strip.addView(makeMetric(context, "NET", "OFF"));
            return strip;
        }

        private View makeMetric(Context context, String label, String value) {
            LinearLayout box = new LinearLayout(context);
            box.setOrientation(VERTICAL);
            box.setGravity(Gravity.CENTER);
            LayoutParams params = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            box.setLayoutParams(params);

            TextView labelView = new TextView(context);
            labelView.setText(label);
            labelView.setTextColor(Color.DKGRAY);
            labelView.setTypeface(Typeface.DEFAULT_BOLD);
            labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);

            TextView valueView = new TextView(context);
            valueView.setText(value);
            valueView.setTextColor(Color.BLACK);
            valueView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);

            box.addView(labelView);
            box.addView(valueView);
            return box;
        }

        @Override
        public View asView() {
            return this;
        }

        @Override
        public void onStart() {
        }

        @Override
        public void onStop() {
        }
    }

    static final class AnimationSurface extends View implements StimulusSurface {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Runnable ticker = new Runnable() {
            @Override
            public void run() {
                if (!running) {
                    return;
                }
                invalidate();
                handler.postDelayed(this, 16L);
            }
        };

        private boolean running;
        private long startedAtMs;

        AnimationSurface(Context context) {
            super(context);
            setBackgroundColor(Color.BLACK);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            float elapsed = (SystemClock.elapsedRealtime() - startedAtMs) / 1000f;
            canvas.drawColor(Color.rgb(8, 8, 14));

            int stripeCount = 10;
            float stripeWidth = width / stripeCount;
            for (int i = 0; i < stripeCount + 2; i++) {
                float offset = (elapsed * 180f) % (stripeWidth * 2f);
                float left = i * stripeWidth - offset;
                paint.setColor((i % 2 == 0) ? Color.rgb(255, 120, 48) : Color.rgb(60, 180, 255));
                canvas.drawRect(left, 0, left + stripeWidth, height, paint);
            }

            paint.setColor(Color.WHITE);
            paint.setStrokeWidth(Math.max(4f, width / 180f));
            for (int i = 0; i < 8; i++) {
                float y = height * i / 8f;
                canvas.drawLine(0, y, width, height - y, paint);
            }

            float radius = Math.min(width, height) * 0.12f;
            float cx = width * (0.5f + 0.28f * (float) Math.cos(elapsed * 1.7f));
            float cy = height * (0.5f + 0.24f * (float) Math.sin(elapsed * 2.1f));
            paint.setColor(Color.BLACK);
            canvas.drawCircle(cx, cy, radius + 10f, paint);
            paint.setColor(Color.YELLOW);
            canvas.drawCircle(cx, cy, radius, paint);
        }

        @Override
        public View asView() {
            return this;
        }

        @Override
        public void onStart() {
            running = true;
            startedAtMs = SystemClock.elapsedRealtime();
            handler.removeCallbacks(ticker);
            handler.post(ticker);
        }

        @Override
        public void onStop() {
            running = false;
            handler.removeCallbacks(ticker);
        }
    }

    static final class TimerSurface extends FrameLayout implements StimulusSurface {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final TextView timerView;
        private final TextView sublabelView;
        private final Runnable ticker = new Runnable() {
            @Override
            public void run() {
                if (!running) {
                    return;
                }
                updateTime();
                handler.postDelayed(this, 100L);
            }
        };

        private boolean running;
        private long startedAtMs;

        TimerSurface(Context context) {
            super(context);
            setBackgroundColor(Color.rgb(16, 16, 16));

            LinearLayout stack = new LinearLayout(context);
            stack.setOrientation(LinearLayout.VERTICAL);
            stack.setGravity(Gravity.CENTER);

            timerView = new TextView(context);
            timerView.setTextColor(Color.WHITE);
            timerView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            timerView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 52f);

            sublabelView = new TextView(context);
            sublabelView.setTextColor(Color.rgb(180, 255, 180));
            sublabelView.setTypeface(Typeface.MONOSPACE);
            sublabelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
            sublabelView.setText("elapsed-seconds / deterministic counter");

            stack.addView(timerView);
            stack.addView(sublabelView);

            addView(stack, new LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT
            ));
        }

        private void updateTime() {
            long elapsedMs = Math.max(0L, SystemClock.elapsedRealtime() - startedAtMs);
            long totalSeconds = elapsedMs / 1000L;
            long minutes = totalSeconds / 60L;
            long seconds = totalSeconds % 60L;
            long tenths = (elapsedMs % 1000L) / 100L;
            String text = String.format("%02d:%02d.%01d", minutes, seconds, tenths);
            timerView.setText(text);
        }

        @Override
        public View asView() {
            return this;
        }

        @Override
        public void onStart() {
            running = true;
            startedAtMs = SystemClock.elapsedRealtime();
            handler.removeCallbacks(ticker);
            handler.post(ticker);
        }

        @Override
        public void onStop() {
            running = false;
            handler.removeCallbacks(ticker);
        }
    }

    static final class TestPatternSurface extends View implements StimulusSurface {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        TestPatternSurface(Context context) {
            super(context);
            setBackgroundColor(Color.BLACK);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            float halfHeight = height / 2f;

            int[] bars = {
                    Color.WHITE,
                    Color.YELLOW,
                    Color.CYAN,
                    Color.GREEN,
                    Color.MAGENTA,
                    Color.RED,
                    Color.BLUE,
                    Color.BLACK
            };
            float barWidth = width / bars.length;
            for (int i = 0; i < bars.length; i++) {
                paint.setColor(bars[i]);
                canvas.drawRect(i * barWidth, 0, (i + 1) * barWidth, halfHeight, paint);
            }

            paint.setColor(Color.rgb(25, 25, 25));
            canvas.drawRect(0, halfHeight, width, height, paint);

            float cell = width / 12f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, width / 220f));
            paint.setColor(Color.WHITE);
            for (int i = 0; i <= 12; i++) {
                float x = i * cell;
                canvas.drawLine(x, halfHeight, x, height, paint);
            }
            for (int i = 0; i <= 8; i++) {
                float y = halfHeight + i * ((height - halfHeight) / 8f);
                canvas.drawLine(0, y, width, y, paint);
            }

            paint.setStyle(Paint.Style.FILL);
            float rampTop = halfHeight + dp(18);
            float rampLeft = dp(18);
            float rampWidth = width * 0.42f;
            float rampHeight = dp(42);
            for (int i = 0; i < 16; i++) {
                int gray = Math.round(255f * i / 15f);
                paint.setColor(Color.rgb(gray, gray, gray));
                float left = rampLeft + (rampWidth / 16f) * i;
                canvas.drawRect(left, rampTop, left + rampWidth / 16f, rampTop + rampHeight, paint);
            }

            float centerX = width * 0.76f;
            float centerY = height * 0.77f;
            paint.setColor(Color.WHITE);
            canvas.drawCircle(centerX, centerY, dp(82), paint);
            paint.setColor(Color.BLACK);
            canvas.drawCircle(centerX, centerY, dp(54), paint);
            paint.setColor(Color.rgb(255, 120, 40));
            canvas.drawRect(centerX - dp(18), centerY - dp(96), centerX + dp(18), centerY + dp(96), paint);
            paint.setColor(Color.rgb(0, 180, 255));
            canvas.drawRect(centerX - dp(96), centerY - dp(18), centerX + dp(96), centerY + dp(18), paint);

            paint.setColor(Color.GREEN);
            Path triangle = new Path();
            triangle.moveTo(width * 0.08f, height * 0.92f);
            triangle.lineTo(width * 0.18f, height * 0.60f);
            triangle.lineTo(width * 0.28f, height * 0.92f);
            triangle.close();
            canvas.drawPath(triangle, paint);

            paint.setColor(Color.RED);
            RectF rect = new RectF(width * 0.32f, height * 0.65f, width * 0.48f, height * 0.91f);
            canvas.drawRoundRect(rect, dp(16), dp(16), paint);
        }

        private float dp(int value) {
            return value * getResources().getDisplayMetrics().density;
        }

        @Override
        public View asView() {
            return this;
        }

        @Override
        public void onStart() {
        }

        @Override
        public void onStop() {
        }
    }

    final class ExternalPresentation extends Presentation {
        private FrameLayout externalHost;
        private TextView externalOverlay;
        private StimulusSurface externalSurface;
        private StimulusMode displayedMode;
        private String displayedSource;
        private long displayedStartedAtMs = -1L;

        ExternalPresentation(Context outerContext, Display display) {
            super(outerContext, display);
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            FrameLayout root = new FrameLayout(getContext());
            root.setBackgroundColor(Color.BLACK);

            externalHost = new FrameLayout(getContext());
            root.addView(externalHost, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));

            externalOverlay = new TextView(getContext());
            externalOverlay.setTextColor(Color.WHITE);
            externalOverlay.setTypeface(Typeface.MONOSPACE);
            externalOverlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            externalOverlay.setBackgroundColor(0xA6000000);
            int padding = dp(getContext(), 10);
            externalOverlay.setPadding(padding, padding, padding, padding);
            FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.START
            );
            overlayParams.setMargins(dp(getContext(), 12), dp(getContext(), 12), dp(getContext(), 12), dp(getContext(), 12));
            root.addView(externalOverlay, overlayParams);

            setContentView(root);
        }

        void updateContent(StimulusMode mode, String source, long startedAtMs) {
            if (externalHost == null || externalOverlay == null) {
                return;
            }
            if (externalSurface == null || displayedMode != mode || displayedStartedAtMs != startedAtMs) {
                if (externalSurface != null) {
                    externalSurface.onStop();
                }
                externalHost.removeAllViews();
                externalSurface = createSurface(getContext(), mode);
                externalHost.addView(externalSurface.asView(), new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));
                externalSurface.onStart();
                displayedMode = mode;
                displayedStartedAtMs = startedAtMs;
            }
            displayedSource = source;
            long elapsedMs = Math.max(0L, SystemClock.elapsedRealtime() - startedAtMs);
            externalOverlay.setText(
                    "external_display\n" +
                            "mode=" + mode.wireValue() + "\n" +
                            "source=" + source + "\n" +
                            "elapsed_ms=" + elapsedMs
            );
        }

        @Override
        public void dismiss() {
            if (externalSurface != null) {
                externalSurface.onStop();
                externalSurface = null;
            }
            super.dismiss();
        }
    }
}
