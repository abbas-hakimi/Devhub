package com.versioncontrol.validation;

import java.util.Locale;

public final class TagNameValidator {
    private TagNameValidator() { }

    public static boolean isValidName(String name) {
        if (name == null || name.isEmpty() || name.length() > 255) return false;
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) return false;
        if (name.contains("..") || name.endsWith(".") || name.toLowerCase(Locale.ROOT).endsWith(".lock")) return false;
        return true;
    }
}
