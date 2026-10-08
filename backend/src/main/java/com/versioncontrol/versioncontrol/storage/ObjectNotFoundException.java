package com.versioncontrol.versioncontrol.storage;

public final class ObjectNotFoundException extends ObjectStorageException {
    public ObjectNotFoundException(String objectHash) {
        super("Stored object was not found: " + objectHash);
    }
}
