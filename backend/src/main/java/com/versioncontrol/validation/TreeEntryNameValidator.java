package com.versioncontrol.validation;

import java.nio.charset.StandardCharsets;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class TreeEntryNameValidator implements ConstraintValidator<ValidTreeEntryName, String> {
    @Override
    public boolean isValid(String name, ConstraintValidatorContext context) {
        return isValidName(name);
    }

    public static boolean isValidName(String name) {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.getBytes(StandardCharsets.UTF_8).length > 255) {
            return false;
        }
        for (int offset = 0; offset < name.length();) {
            int codePoint = name.codePointAt(offset);
            if (Character.isISOControl(codePoint)) return false;
            offset += Character.charCount(codePoint);
        }
        return true;
    }
}
