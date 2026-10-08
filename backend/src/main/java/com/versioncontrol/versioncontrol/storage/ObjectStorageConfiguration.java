package com.versioncontrol.versioncontrol.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObjectStorageConfiguration {
    @Bean
    public ObjectStorage objectStorage(@Value("${version-control.storage.root:}") String storageRoot) {
        return new LocalFileObjectStorage(storageRoot);
    }
}
