package com.versioncontrol.versioncontrol.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.versioncontrol.versioncontrol.hash.Sha256Hasher;

class LocalFileObjectStorageTests {
    @TempDir
    Path tempDir;

    private Path root;
    private LocalFileObjectStorage storage;

    @BeforeEach
    void setUp() {
        root = tempDir.resolve("content-store");
        storage = new LocalFileObjectStorage(root.toString());
    }

    @Test
    void writesReadsAndChecksExactBytesAtCanonicalFanoutPath() throws Exception {
        byte[] bytes = {0, 1, 2, (byte) 0xff, 10, 13, 0};
        String hash = Sha256Hasher.hashBytes(bytes);

        storage.write(hash, new ByteArrayInputStream(bytes), bytes.length);

        Path object = root.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash.substring(2));
        assertThat(Files.readAllBytes(object)).isEqualTo(bytes);
        assertThat(storage.exists(hash)).isTrue();
        try (InputStream read = storage.read(hash)) {
            assertThat(read.readAllBytes()).isEqualTo(bytes);
        }
    }

    @Test
    void missingObjectReadFailsClearlyAndExistsIsFalse() {
        String hash = "a".repeat(64);
        assertThat(storage.exists(hash)).isFalse();
        assertThatThrownBy(() -> storage.read(hash)).isInstanceOf(ObjectNotFoundException.class)
                .hasMessageContaining(hash);
    }

    @Test
    void rejectsInvalidUppercaseAndTraversalHashesBeforePathUse() {
        for (String invalid : Arrays.asList(null, "", "A".repeat(64), "g".repeat(64), "a".repeat(63),
                "../" + "a".repeat(61), "..\\" + "a".repeat(62), "C:/" + "a".repeat(61), "a".repeat(63) + "/")) {
            assertThatIllegalArgumentException().isThrownBy(() -> storage.exists(invalid));
            assertThatIllegalArgumentException().isThrownBy(() -> storage.read(invalid));
        }
    }

    @Test
    void rejectsMissingRelativeAndNonDirectoryStorageRoots() throws Exception {
        assertThatIllegalArgumentException().isThrownBy(() -> new LocalFileObjectStorage(null));
        assertThatIllegalArgumentException().isThrownBy(() -> new LocalFileObjectStorage("relative/path"));
        Path file = tempDir.resolve("not-a-directory");
        Files.writeString(file, "file");
        assertThatIllegalArgumentException().isThrownBy(() -> new LocalFileObjectStorage(file.toString()));
    }

    @Test
    void rejectsNegativeOrMismatchedSizesWithoutPublishingFinalObject() {
        byte[] bytes = {4, 5, 6};
        String hash = Sha256Hasher.hashBytes(bytes);
        assertThatIllegalArgumentException().isThrownBy(() -> storage.write(hash, new ByteArrayInputStream(bytes), -1));
        assertThatThrownBy(() -> storage.write(hash, new ByteArrayInputStream(bytes), bytes.length + 1))
                .isInstanceOf(ObjectIntegrityException.class).hasMessageContaining("size");
        assertThat(storage.exists(hash)).isFalse();
        assertThatThrownBy(() -> storage.write(hash, new ByteArrayInputStream(bytes), bytes.length - 1))
                .isInstanceOf(ObjectIntegrityException.class).hasMessageContaining("size");
        assertThat(storage.exists(hash)).isFalse();
    }

    @Test
    void rejectsContentThatDoesNotMatchRequestedHashAndLeavesNoPartialObject() {
        byte[] bytes = {7, 8, 9};
        String wrongHash = Sha256Hasher.hashBytes(new byte[] {9, 8, 7});
        assertThatThrownBy(() -> storage.write(wrongHash, new ByteArrayInputStream(bytes), bytes.length))
                .isInstanceOf(ObjectIntegrityException.class).hasMessageContaining("hash");
        assertThat(storage.exists(wrongHash)).isFalse();
        assertThat(Files.exists(root.resolve("objects").resolve(wrongHash.substring(0, 2)).resolve(wrongHash.substring(2))))
                .isFalse();
    }

    @Test
    void sameObjectWriteIsIdempotentAndExistingCorruptionIsNotOverwritten() throws Exception {
        byte[] bytes = {0, (byte) 0xff, 3};
        String hash = Sha256Hasher.hashBytes(bytes);
        storage.write(hash, new ByteArrayInputStream(bytes), bytes.length);
        storage.write(hash, new ByteArrayInputStream(bytes), bytes.length);
        Path object = root.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash.substring(2));
        assertThat(Files.readAllBytes(object)).isEqualTo(bytes);

        Files.write(object, new byte[] {1, 2, 3});
        assertThatThrownBy(() -> storage.write(hash, new ByteArrayInputStream(bytes), bytes.length))
                .isInstanceOf(ObjectCorruptionException.class).hasMessageContaining("corrupt");
        assertThat(Files.readAllBytes(object)).containsExactly(1, 2, 3);
    }

    @Test
    void streamsLargeContentAcrossShortReads() throws Exception {
        byte[] bytes = new byte[700_123];
        new Random(12345).nextBytes(bytes);
        String hash = Sha256Hasher.hashBytes(bytes);
        InputStream shortReads = new ByteArrayInputStream(bytes) {
            @Override
            public synchronized int read(byte[] buffer, int offset, int length) {
                return super.read(buffer, offset, Math.min(length, 29));
            }
        };

        storage.write(hash, shortReads, bytes.length);

        try (InputStream read = storage.read(hash)) {
            assertThat(read.readAllBytes()).isEqualTo(bytes);
        }
    }

    @Test
    void failedInputStreamDoesNotLeavePublishedObjectOrTemporaryFile() throws Exception {
        byte[] prefix = {1, 2, 3};
        String hash = Sha256Hasher.hashBytes(prefix);
        InputStream failureAfterPrefix = new InputStream() {
            private int index;
            @Override public int read() throws IOException {
                if (index < prefix.length) return prefix[index++] & 0xff;
                throw new IOException("test stream failure");
            }
        };
        assertThatThrownBy(() -> storage.write(hash, failureAfterPrefix, prefix.length))
                .isInstanceOf(ObjectStorageException.class).hasMessageContaining("Unable to write object");
        Path fanout = root.resolve("objects").resolve(hash.substring(0, 2));
        assertThat(storage.exists(hash)).isFalse();
        try (var files = Files.list(fanout)) {
            assertThat(files).isEmpty();
        }
    }
}
