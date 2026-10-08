package com.versioncontrol.versioncontrol.storage;

public final class ObjectCorruptionException extends ObjectStorageException {
    public ObjectCorruptionException(String message) { super(message); }
    public ObjectCorruptionException(String message, Throwable cause) { super(message, cause); }
}
