package com.versioncontrol.versioncontrol.hash;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Canonical v1 commit encoding. No clock or persistence access is performed here. */
public final class CommitCanonicalizer {
    private static final byte[] PREFIX = "VC-COMMIT\0v1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MERGE_PREFIX = "VC-COMMIT\0v2-merge".getBytes(StandardCharsets.US_ASCII);

    private CommitCanonicalizer() { }

    /**
     * Builds the exact commit input. IDs and epoch microseconds are signed 64-bit big-endian
     * fields (IDs must be positive); message length is a 32-bit big-endian byte count. Timestamp
     * precision is normalized by truncating to microseconds, matching MySQL TIMESTAMP(6). The
     * caller must persist that same truncated Instant alongside the resulting commit hash.
     */
    public static byte[] canonicalize(long repositoryId, String parentCommitHash, String rootTreeHash,
            long authorId, Instant createdAt, String message) {
        if (repositoryId <= 0) throw new IllegalArgumentException("repository ID must be positive");
        if (authorId <= 0) throw new IllegalArgumentException("author ID must be positive");
        if (rootTreeHash == null) throw new IllegalArgumentException("root tree hash is required");
        byte[] rootHash = Sha256Hasher.hashBytesFromHex(rootTreeHash);
        byte[] parentHash = parentCommitHash == null ? null : Sha256Hasher.hashBytesFromHex(parentCommitHash);
        if (createdAt == null) throw new IllegalArgumentException("creation timestamp is required");
        if (message == null) throw new IllegalArgumentException("commit message is required");
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
        Instant preciseTime = createdAt.truncatedTo(ChronoUnit.MICROS);
        long epochMicros;
        try {
            epochMicros = Math.addExact(Math.multiplyExact(preciseTime.getEpochSecond(), 1_000_000L),
                    preciseTime.getNano() / 1_000L);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("timestamp is outside supported epoch-microsecond range", overflow);
        }

        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.write(PREFIX);
            out.writeLong(repositoryId);
            out.writeByte(parentHash == null ? 0x00 : 0x01);
            if (parentHash != null) out.write(parentHash);
            out.write(rootHash);
            out.writeLong(authorId);
            out.writeLong(epochMicros);
            out.writeInt(messageBytes.length);
            out.write(messageBytes);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory commit encoding failed", impossible);
        }
    }

    public static String hash(long repositoryId, String parentCommitHash, String rootTreeHash,
            long authorId, Instant createdAt, String message) {
        return Sha256Hasher.hashBytes(canonicalize(repositoryId, parentCommitHash, rootTreeHash, authorId, createdAt, message));
    }

    /** Canonical v2 encoding used only for true two-parent merge commits. */
    public static byte[] canonicalizeMerge(long repositoryId, String firstParentHash, String secondParentHash,
            String rootTreeHash, long authorId, Instant createdAt, String message) {
        if (repositoryId <= 0 || authorId <= 0) throw new IllegalArgumentException("repository and author IDs must be positive");
        if (firstParentHash == null || secondParentHash == null || firstParentHash.equals(secondParentHash)) {
            throw new IllegalArgumentException("two distinct parent hashes are required");
        }
        byte[] firstParent = Sha256Hasher.hashBytesFromHex(firstParentHash);
        byte[] secondParent = Sha256Hasher.hashBytesFromHex(secondParentHash);
        byte[] root = Sha256Hasher.hashBytesFromHex(rootTreeHash);
        if (createdAt == null || message == null) throw new IllegalArgumentException("timestamp and message are required");
        Instant precise = createdAt.truncatedTo(ChronoUnit.MICROS);
        long micros;
        try {
            micros = Math.addExact(Math.multiplyExact(precise.getEpochSecond(), 1_000_000L), precise.getNano() / 1_000L);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("timestamp is outside supported epoch-microsecond range", overflow);
        }
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.write(MERGE_PREFIX);
            out.writeLong(repositoryId);
            out.write(firstParent);
            out.write(secondParent);
            out.write(root);
            out.writeLong(authorId);
            out.writeLong(micros);
            out.writeInt(messageBytes.length);
            out.write(messageBytes);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory merge commit encoding failed", impossible);
        }
    }

    public static String hashMerge(long repositoryId, String firstParentHash, String secondParentHash,
            String rootTreeHash, long authorId, Instant createdAt, String message) {
        return Sha256Hasher.hashBytes(canonicalizeMerge(repositoryId, firstParentHash, secondParentHash,
                rootTreeHash, authorId, createdAt, message));
    }
}
