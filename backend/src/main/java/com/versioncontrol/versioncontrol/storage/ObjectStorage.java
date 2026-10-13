package com.versioncontrol.versioncontrol.storage;

import java.io.InputStream;

/** Stores immutable objects addressed by their SHA-256 hash. */
public interface ObjectStorage {
    void write(String objectHash, InputStream input, long size);

    InputStream read(String objectHash);

    /** Verify an object's bytes and size using bounded-memory streaming before serving metadata. */
    void verify(String objectHash, long expectedSize);

    boolean exists(String objectHash);
}
