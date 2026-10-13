package com.versioncontrol.controller;

import java.io.InputStream;

import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.versioncontrol.dto.git.CommitBrowseDtos.BlobMetadata;
import com.versioncontrol.dto.git.CommitBrowseDtos.CommitHistory;
import com.versioncontrol.dto.git.CommitBrowseDtos.CommitMetadata;
import com.versioncontrol.dto.git.CommitBrowseDtos.TreeListing;
import com.versioncontrol.security.UserPrincipal;
import com.versioncontrol.service.CommitBrowsingService;

import jakarta.servlet.http.HttpServletRequest;

@RestController
public class CommitBrowsingController {
    private final CommitBrowsingService browsing;

    public CommitBrowsingController(CommitBrowsingService browsing) { this.browsing = browsing; }

    @GetMapping("/api/repositories/{repositoryId}/commits")
    public CommitHistory history(@PathVariable Long repositoryId,
            @RequestParam(defaultValue = "main") String branch,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserPrincipal principal) {
        return browsing.history(repositoryId, branch, page, size, userId(principal));
    }

    @GetMapping("/api/commits/{commitHash}")
    public CommitMetadata commit(@PathVariable String commitHash,
            @AuthenticationPrincipal UserPrincipal principal) {
        return browsing.getCommit(commitHash, userId(principal));
    }

    @GetMapping("/api/commits/{commitHash}/tree")
    public TreeListing rootTree(@PathVariable String commitHash,
            @AuthenticationPrincipal UserPrincipal principal) {
        return browsing.rootTree(commitHash, userId(principal));
    }

    @GetMapping("/api/commits/{commitHash}/tree/{*path}")
    public TreeListing directory(@PathVariable String commitHash, @PathVariable String path,
            @AuthenticationPrincipal UserPrincipal principal) {
        return browsing.directory(commitHash, path, userId(principal));
    }

    @GetMapping("/api/commits/{commitHash}/blob/{*path}")
    public ResponseEntity<StreamingResponseBody> blob(@PathVariable String commitHash, @PathVariable String path,
            @AuthenticationPrincipal UserPrincipal principal, HttpServletRequest servletRequest) {
        BlobMetadata blob = browsing.blob(commitHash, path, userId(principal));
        String etag = '"' + blob.blobHash() + '"';
        ServletWebRequest webRequest = new ServletWebRequest(servletRequest);
        if (webRequest.checkNotModified(etag)) {
            return ResponseEntity.status(304).eTag(etag).build();
        }
        MediaType contentType = MediaTypeFactory.getMediaType(path).orElse(MediaType.APPLICATION_OCTET_STREAM);
        StreamingResponseBody stream = output -> {
            try (InputStream input = browsing.openBlob(blob.blobHash())) {
                input.transferTo(output);
            }
        };
        return ResponseEntity.ok().contentType(contentType).contentLength(blob.size()).eTag(etag).body(stream);
    }

    private Long userId(UserPrincipal principal) { return principal == null ? null : principal.getId(); }
}
