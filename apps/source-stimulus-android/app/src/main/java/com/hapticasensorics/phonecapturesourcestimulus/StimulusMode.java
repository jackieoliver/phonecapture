package com.hapticasensorics.phonecapturesourcestimulus;

import java.util.Locale;

public enum StimulusMode {
    TEXT("text"),
    UI("ui"),
    ANIMATION("animation"),
    TIMER("timer"),
    TEST_PATTERN("test_pattern");

    private final String wireValue;

    StimulusMode(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static StimulusMode fromWireValue(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return TEXT;
        }
        String normalized = raw.trim().toLowerCase(Locale.US)
                .replace('-', '_')
                .replace(' ', '_');
        if ("high_contrast".equals(normalized) || "highcontrast".equals(normalized)) {
            normalized = "ui";
        } else if ("pattern".equals(normalized) || "test".equals(normalized)
                || "color".equals(normalized) || "geometry".equals(normalized)) {
            normalized = "test_pattern";
        }
        for (StimulusMode value : values()) {
            if (value.wireValue.equals(normalized) || value.name().toLowerCase(Locale.US).equals(normalized)) {
                return value;
            }
        }
        return TEXT;
    }
}
