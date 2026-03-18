package com.linkwork.model.enums;

import java.util.Locale;

public enum ConflictPolicy {
    REJECT,
    OVERWRITE,
    RENAME;

    public static ConflictPolicy fromString(String value) {
        if (value == null || value.isBlank()) {
            return REJECT;
        }
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return REJECT;
        }
    }
}
