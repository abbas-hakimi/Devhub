package com.versioncontrol.dto.git;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CommitChangeRequest(
        @NotBlank @Size(max = 4096) String path,
        @Size(max = 1_000_000) String content,
        @Size(max = 1_333_336) String contentBase64
) {
    public boolean hasExactlyOneContentSource() {
        return (content != null) != (contentBase64 != null);
    }
}
