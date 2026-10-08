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

@Entity
@Table(name = "trees", uniqueConstraints = @UniqueConstraint(name = "uk_trees_tree_hash", columnNames = "tree_hash"))
public class Tree {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Pattern(regexp = "[0-9a-f]{64}")
    @Column(name = "tree_hash", nullable = false, length = 64,
            columnDefinition = "CHAR(64) COLLATE ascii_bin")
    private String treeHash;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Tree() { }

    public Tree(String treeHash) {
        if (treeHash == null || !treeHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("tree hash must be 64 lowercase hexadecimal characters");
        }
        this.treeHash = treeHash;
    }

    public Long getId() { return id; }
    public String getTreeHash() { return treeHash; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
