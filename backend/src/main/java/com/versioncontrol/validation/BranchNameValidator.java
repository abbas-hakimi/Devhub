package com.versioncontrol.validation;

import java.nio.charset.StandardCharsets;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class BranchNameValidator implements ConstraintValidator<ValidBranchName, String> {
    @Override
    public boolean isValid(String name, ConstraintValidatorContext context) {
        return isValidName(name);
    }

    public static boolean isValidName(String name) {
        if (name == null || name.isEmpty() || name.equals("HEAD")
                || name.startsWith("/") || name.endsWith("/") || name.contains("//")
                || name.endsWith(".") || name.endsWith(".lock")
                || name.contains("@{")) {
            return false;
        }
        if (name.getBytes(StandardCharsets.UTF_8).length > 255) return false;

        for (String component : name.split("/", -1)) {
            if (component.equals(".") || component.equals("..")) return false;
        }
        for (int offset = 0; offset < name.length();) {
            int codePoint = name.codePointAt(offset);
            if (Character.isISOControl(codePoint) || Character.isWhitespace(codePoint)
                    || Character.isSpaceChar(codePoint) || "~^:?*[\\".indexOf(codePoint) >= 0) {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return true;
    }
}
