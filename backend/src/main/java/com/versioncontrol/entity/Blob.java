package com.versioncontrol.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

@Entity
@Table(name = "blobs", uniqueConstraints = @UniqueConstraint(name = "uk_blobs_object_hash", columnNames = "object_hash"))
public class Blob {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Pattern(regexp = "[0-9a-f]{64}")
    @Column(name = "object_hash", nullable = false, length = 64,
            columnDefinition = "CHAR(64) COLLATE ascii_bin")
    private String objectHash;

    @PositiveOrZero
    @Column(nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long size;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Blob() { }

    public Blob(String objectHash, long size) {
        if (objectHash == null || !objectHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("object hash must be 64 lowercase hexadecimal characters");
        }
        if (size < 0) throw new IllegalArgumentException("blob size cannot be negative");
        this.objectHash = objectHash;
        this.size = size;
    }

    public Long getId() { return id; }
    public String getObjectHash() { return objectHash; }
    public Long getSize() { return size; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
