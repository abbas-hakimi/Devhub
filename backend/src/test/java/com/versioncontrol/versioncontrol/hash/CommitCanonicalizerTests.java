package com.versioncontrol.versioncontrol.hash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class CommitCanonicalizerTests {
    private static final String ROOT = "1".repeat(64);
    private static final String PARENT = "2".repeat(64);
    private static final Instant TIME = Instant.parse("2025-01-02T03:04:05.123456Z");

    @Test
    void initialCommitEncodingIsDeterministicAndContainsExplicitAbsentParentMarker() {
        byte[] first = CommitCanonicalizer.canonicalize(7, null, ROOT, 11, TIME, "Initial");
        assertThat(first).isEqualTo(CommitCanonicalizer.canonicalize(7, null, ROOT, 11, TIME, "Initial"));
        ByteBuffer in = ByteBuffer.wrap(first).order(ByteOrder.BIG_ENDIAN);
        byte[] prefix = new byte["VC-COMMIT\0v1".length()]; in.get(prefix);
        assertThat(prefix).isEqualTo("VC-COMMIT\0v1".getBytes(StandardCharsets.US_ASCII));
        assertThat(in.getLong()).isEqualTo(7L);
        assertThat(Byte.toUnsignedInt(in.get())).isZero();
        byte[] root = new byte[32]; in.get(root);
        assertThat(root).isEqualTo(Sha256Hasher.hashBytesFromHex(ROOT));
        assertThat(in.getLong()).isEqualTo(11L);
        assertThat(in.getLong()).isEqualTo(Instant.parse("2025-01-02T03:04:05.123456Z").getEpochSecond() * 1_000_000L + 123456L);
        assertThat(in.getInt()).isEqualTo(7);
        byte[] message = new byte[7]; in.get(message);
        assertThat(message).isEqualTo("Initial".getBytes(StandardCharsets.UTF_8));
        assertThat(in.hasRemaining()).isFalse();
    }

    @Test
    void everyCommitInputFieldContributesToIdentity() {
        String hash = CommitCanonicalizer.hash(7, null, ROOT, 11, TIME, "Initial");
        assertThat(CommitCanonicalizer.hash(7, PARENT, ROOT, 11, TIME, "Initial")).isNotEqualTo(hash);
        assertThat(CommitCanonicalizer.hash(7, "3".repeat(64), ROOT, 11, TIME, "Initial")).isNotEqualTo(hash);
        assertThat(CommitCanonicalizer.hash(8, null, ROOT, 11, TIME, "Initial")).isNotEqualTo(hash);
        assertThat(CommitCanonicalizer.hash(7, null, ROOT, 12, TIME, "Initial")).isNotEqualTo(hash);
        assertThat(CommitCanonicalizer.hash(7, null, "4".repeat(64), 11, TIME, "Initial")).isNotEqualTo(hash);
        assertThat(CommitCanonicalizer.hash(7, null, ROOT, 11, TIME.plusSeconds(1), "Initial")).isNotEqualTo(hash);
        assertThat(CommitCanonicalizer.hash(7, null, ROOT, 11, TIME, "initial")).isNotEqualTo(hash);
    }

    @Test
    void mergeCommitEncodingIsDeterministicAndEveryFieldIncludingBothParentsContributes() {
        String secondParent = "3".repeat(64);
        byte[] encoded = CommitCanonicalizer.canonicalizeMerge(7, PARENT, secondParent, ROOT, 11, TIME, "Merge");
        assertThat(encoded).isEqualTo(CommitCanonicalizer.canonicalizeMerge(7, PARENT, secondParent, ROOT, 11, TIME, "Merge"));
        assertThat(CommitCanonicalizer.hashMerge(7, PARENT, secondParent, ROOT, 11, TIME, "Merge"))
                .isEqualTo(Sha256Hasher.hashBytes(encoded));
        String hash = CommitCanonicalizer.hashMerge(7, PARENT, secondParent, ROOT, 11, TIME, "Merge");
        assertThat(CommitCanonicalizer.hashMerge(7, "4".repeat(64), secondParent, ROOT, 11, TIME, "Merge")).isNotEqualTo(hash);
        assertThat(CommitCanonicalizer.hashMerge(7, PARENT, "4".repeat(64), ROOT, 11, TIME, "Merge")).isNotEqualTo(hash);
        assertThat(CommitCanonicalizer.hashMerge(7, PARENT, secondParent, "4".repeat(64), 11, TIME, "Merge")).isNotEqualTo(hash);
        assertThat(CommitCanonicalizer.hashMerge(8, PARENT, secondParent, ROOT, 11, TIME, "Merge")).isNotEqualTo(hash);
        assertThat(CommitCanonicalizer.hashMerge(7, PARENT, secondParent, ROOT, 12, TIME, "Merge")).isNotEqualTo(hash);
        assertThat(CommitCanonicalizer.hashMerge(7, PARENT, secondParent, ROOT, 11, TIME.plusSeconds(1), "Merge")).isNotEqualTo(hash);
        assertThat(CommitCanonicalizer.hashMerge(7, PARENT, secondParent, ROOT, 11, TIME, "merge")).isNotEqualTo(hash);
    }

    @Test
    void mergeCommitEncodingRejectsMissingOrRepeatedParents() {
        assertThatIllegalArgumentException().isThrownBy(() -> CommitCanonicalizer.hashMerge(7, null, PARENT, ROOT, 11, TIME, "Merge"));
        assertThatIllegalArgumentException().isThrownBy(() -> CommitCanonicalizer.hashMerge(7, PARENT, PARENT, ROOT, 11, TIME, "Merge"));
    }

    @Test
    void utf8MessageBytesAreExactAndTimestampIsDeterministicAtMicrosecondPrecision() {
        String composed = CommitCanonicalizer.hash(7, null, ROOT, 11, TIME, "café");
        String decomposed = CommitCanonicalizer.hash(7, null, ROOT, 11, TIME, "cafe\u0301");
        assertThat(composed).isNotEqualTo(decomposed);
        assertThat(CommitCanonicalizer.hash(7, null, ROOT, 11, TIME.plusNanos(999), "Initial"))
                .isEqualTo(CommitCanonicalizer.hash(7, null, ROOT, 11, TIME, "Initial"));
        assertThat(CommitCanonicalizer.hash(7, null, ROOT, 11, TIME.plusNanos(1_000), "Initial"))
                .isNotEqualTo(CommitCanonicalizer.hash(7, null, ROOT, 11, TIME, "Initial"));

        byte[] encoded = CommitCanonicalizer.canonicalize(7, null, ROOT, 11, TIME, "café");
        ByteBuffer in = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
        in.position("VC-COMMIT\0v1".getBytes(StandardCharsets.US_ASCII).length + 8 + 1 + 32 + 8 + 8);
        assertThat(in.getInt()).isEqualTo("café".getBytes(StandardCharsets.UTF_8).length);
        byte[] messageBytes = new byte[in.remaining()]; in.get(messageBytes);
        assertThat(messageBytes).isEqualTo("café".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsInvalidCommitInputs() {
        assertThatIllegalArgumentException().isThrownBy(() -> CommitCanonicalizer.hash(0, null, ROOT, 1, TIME, "x"));
        assertThatIllegalArgumentException().isThrownBy(() -> CommitCanonicalizer.hash(1, null, ROOT, 0, TIME, "x"));
        assertThatIllegalArgumentException().isThrownBy(() -> CommitCanonicalizer.hash(1, null, "A".repeat(64), 1, TIME, "x"));
        assertThatIllegalArgumentException().isThrownBy(() -> CommitCanonicalizer.hash(1, null, ROOT, 1, null, "x"));
        assertThatIllegalArgumentException().isThrownBy(() -> CommitCanonicalizer.hash(1, null, ROOT, 1, TIME, null));
    }
}
