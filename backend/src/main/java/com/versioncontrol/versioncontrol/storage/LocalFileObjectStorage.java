package com.versioncontrol.versioncontrol.storage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.util.StringUtils;

import com.versioncontrol.versioncontrol.hash.Sha256Hasher;

/** Local immutable object storage using a two-character SHA-256 fanout. */
public final class LocalFileObjectStorage implements ObjectStorage {
    private static final int BUFFER_SIZE = 16 * 1024;
    private static final HexFormat HEX = HexFormat.of();

    private final Path root;
    private final Path objectsDirectory;

    public LocalFileObjectStorage(String configuredRoot) {
        if (!StringUtils.hasText(configuredRoot)) {
            throw new IllegalArgumentException("version-control.storage.root must be configured (or set VERSION_CONTROL_STORAGE_ROOT)");
        }
        try {
            Path supplied = Path.of(configuredRoot);
            if (!supplied.isAbsolute()) {
                throw new IllegalArgumentException("version-control.storage.root must be an absolute path");
            }
            this.root = supplied.normalize();
            this.objectsDirectory = root.resolve("objects").normalize();
            if (!objectsDirectory.startsWith(root)) {
                throw new IllegalArgumentException("object directory must remain under the configured storage root");
            }
            if (Files.exists(root) && !Files.isDirectory(root)) {
                throw new IllegalArgumentException("version-control.storage.root must be a directory");
            }
            if (Files.exists(objectsDirectory) && !Files.isDirectory(objectsDirectory)) {
                throw new IllegalArgumentException("storage objects path must be a directory");
            }
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("version-control.storage.root is not a valid filesystem path", exception);
        }
    }

    @Override
    public synchronized void write(String objectHash, InputStream input, long size) {
        Path target = objectPath(objectHash);
        if (input == null) throw new IllegalArgumentException("object input stream is required");
        if (size < 0) throw new IllegalArgumentException("object size cannot be negative");

        Path temporary = null;
        try {
            Path fanout = target.getParent();
            Files.createDirectories(fanout);
            ensureSafeDirectory(fanout);
            if (Files.isSymbolicLink(target)) {
                throw new ObjectCorruptionException("object path must not be a symbolic link: " + objectHash);
            }

            temporary = Files.createTempFile(fanout, ".object-", ".tmp");
            MessageDigest digest = sha256();
            long written = 0;
            byte[] buffer = new byte[BUFFER_SIZE];
            try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(temporary))) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count == 0) continue;
                    try {
                        written = Math.addExact(written, count);
                    } catch (ArithmeticException overflow) {
                        throw new ObjectIntegrityException("object exceeds supported size");
                    }
                    if (written > size) {
                        throw new ObjectIntegrityException("supplied object size does not match streamed content");
                    }
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
            }
            if (written != size) {
                throw new ObjectIntegrityException("supplied object size does not match streamed content");
            }
            String actualHash = HEX.formatHex(digest.digest());
            if (!actualHash.equals(objectHash)) {
                throw new ObjectIntegrityException("streamed content does not match requested object hash");
            }

            if (Files.exists(target)) {
                verifyExisting(target, objectHash, size);
                return;
            }
            ensureSafeDirectory(fanout);
            moveIntoPlace(temporary, target, objectHash, size);
            temporary = null;
        } catch (ObjectStorageException | IllegalArgumentException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ObjectStorageException("Unable to write object " + objectHash, exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Keep the original write failure; an unpublished temp file is never an object.
                }
            }
        }
    }

    @Override
    public InputStream read(String objectHash) {
        Path path = objectPath(objectHash);
        try {
            ensureSafeDirectory(path.getParent());
            if (Files.isSymbolicLink(path)) {
                throw new ObjectCorruptionException("object path must not be a symbolic link: " + objectHash);
            }
            if (!Files.isRegularFile(path)) throw new ObjectNotFoundException(objectHash);
            return new VerifyingInputStream(new BufferedInputStream(Files.newInputStream(path)), objectHash);
        } catch (ObjectStorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ObjectStorageException("Unable to read object " + objectHash, exception);
        }
    }

    @Override
    public void verify(String objectHash, long expectedSize) {
        Path path = objectPath(objectHash);
        try {
            ensureSafeDirectory(path.getParent());
            if (Files.isSymbolicLink(path)) {
                throw new ObjectCorruptionException("object path must not be a symbolic link: " + objectHash);
            }
            if (!Files.isRegularFile(path)) throw new ObjectNotFoundException(objectHash);
            verifyExisting(path, objectHash, expectedSize);
        } catch (ObjectStorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ObjectStorageException("Unable to verify object " + objectHash, exception);
        }
    }

    @Override
    public boolean exists(String objectHash) {
        Path path = objectPath(objectHash);
        try {
            ensureSafeDirectory(path.getParent());
            if (Files.isSymbolicLink(path)) {
                throw new ObjectCorruptionException("object path must not be a symbolic link: " + objectHash);
            }
            return Files.isRegularFile(path);
        } catch (ObjectStorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ObjectStorageException("Unable to inspect object " + objectHash, exception);
        }
    }

    private Path objectPath(String objectHash) {
        byte[] hashBytes = Sha256Hasher.hashBytesFromHex(objectHash); // validates before path construction
        Path path = objectsDirectory.resolve(objectHash.substring(0, 2)).resolve(objectHash.substring(2)).normalize();
        if (!path.startsWith(objectsDirectory) || hashBytes.length != 32) {
            throw new IllegalArgumentException("object path must remain underneath the storage objects directory");
        }
        return path;
    }

    private void ensureSafeDirectory(Path directory) throws IOException {
        if (Files.isSymbolicLink(root) || Files.isSymbolicLink(objectsDirectory) || Files.isSymbolicLink(directory)) {
            throw new ObjectCorruptionException("storage directories must not be symbolic links");
        }
        Files.createDirectories(directory);
        Path realObjects = objectsDirectory.toRealPath();
        Path realDirectory = directory.toRealPath();
        if (!realDirectory.startsWith(realObjects)) {
            throw new ObjectCorruptionException("resolved object directory escapes the configured storage root");
        }
    }

    private void verifyExisting(Path target, String expectedHash, long expectedSize) {
        if (!Files.isRegularFile(target)) {
            throw new ObjectCorruptionException("existing object is not a regular file: " + expectedHash);
        }
        long actualSize;
        String actualHash;
        try {
            actualSize = Files.size(target);
            MessageDigest digest = sha256();
            try (InputStream existing = new BufferedInputStream(Files.newInputStream(target))) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int count;
                while ((count = existing.read(buffer)) != -1) {
                    if (count > 0) digest.update(buffer, 0, count);
                }
            }
            actualHash = HEX.formatHex(digest.digest());
        } catch (IOException exception) {
            throw new ObjectCorruptionException("unable to verify existing object: " + expectedHash, exception);
        }
        if (actualSize != expectedSize || !actualHash.equals(expectedHash)) {
            throw new ObjectCorruptionException("existing object is corrupt or conflicts with requested content: " + expectedHash);
        }
    }

    private void moveIntoPlace(Path temporary, Path target, String objectHash, long size) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new ObjectStorageException(
                    "Storage filesystem does not support atomic object publication; object was not published", unsupported);
        } catch (java.nio.file.FileAlreadyExistsException exists) {
            verifyExisting(target, objectHash, size);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    /** Detects changes after the preflight verify without buffering the object in memory. */
    private static final class VerifyingInputStream extends FilterInputStream {
        private final String expectedHash;
        private final MessageDigest digest = sha256();
        private boolean verified;

        private VerifyingInputStream(InputStream input, String expectedHash) {
            super(input);
            this.expectedHash = expectedHash;
        }

        @Override
        public int read() throws IOException {
            int value = in.read();
            if (value < 0) verifyEnd();
            else digest.update((byte) value);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = in.read(buffer, offset, length);
            if (count < 0) verifyEnd();
            else if (count > 0) digest.update(buffer, offset, count);
            return count;
        }

        @Override
        public long skip(long amount) throws IOException {
            if (amount <= 0) return 0;
            byte[] discarded = new byte[(int) Math.min(BUFFER_SIZE, amount)];
            long skipped = 0;
            while (skipped < amount) {
                int count = read(discarded, 0, (int) Math.min(discarded.length, amount - skipped));
                if (count < 0) break;
                skipped += count;
            }
            return skipped;
        }

        @Override public boolean markSupported() { return false; }
        @Override public synchronized void mark(int readLimit) { }
        @Override public synchronized void reset() throws IOException { throw new IOException("mark/reset is not supported"); }

        private void verifyEnd() {
            if (verified) return;
            verified = true;
            String actual = HEX.formatHex(digest.digest());
            if (!actual.equals(expectedHash)) {
                throw new ObjectCorruptionException("streamed object does not match its content hash: " + expectedHash);
            }
        }
    }
}
