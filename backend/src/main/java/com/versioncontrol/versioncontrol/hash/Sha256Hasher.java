package com.versioncontrol.versioncontrol.hash;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 helpers. Blob hashes cover precisely the caller-provided bytes. */
public final class Sha256Hasher {
    private static final HexFormat HEX = HexFormat.of();

    private Sha256Hasher() { }

    public static String hashBytes(byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("bytes are required");
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    /** Encodes text as UTF-8 without trimming, newline conversion, or Unicode normalization. */
    public static String hashUtf8(String value) {
        if (value == null) throw new IllegalArgumentException("value is required");
        return hashBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    /** Parses a canonical lowercase SHA-256 hex value into its 32 raw bytes. */
    public static byte[] hashBytesFromHex(String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("hash must be 64 lowercase hexadecimal characters");
        }
        return HEX.parseHex(hash);
    }
}
