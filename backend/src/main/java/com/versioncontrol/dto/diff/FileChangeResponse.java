package com.versioncontrol.dto.diff;

public record FileChangeResponse(String path, String status, String oldHash, String newHash,
        boolean binary, String patch, int additions, int deletions, boolean patchTruncated) { }
