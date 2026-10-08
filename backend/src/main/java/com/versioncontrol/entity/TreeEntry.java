package com.versioncontrol.entity;

import java.text.Normalizer;

import org.hibernate.annotations.Check;

import com.versioncontrol.validation.TreeEntryNameValidator;
import com.versioncontrol.validation.ValidTreeEntryName;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "tree_entries",
        indexes = {
                @Index(name = "idx_tree_entries_blob", columnList = "blob_id"),
                @Index(name = "idx_tree_entries_child_tree", columnList = "child_tree_id")
        },
        uniqueConstraints = @UniqueConstraint(name = "uk_tree_entries_tree_name", columnNames = {"tree_id", "name"}))
@Check(constraints = "(entry_type = 'FILE' AND blob_id IS NOT NULL AND child_tree_id IS NULL)"
        + " OR (entry_type = 'DIRECTORY' AND blob_id IS NULL AND child_tree_id IS NOT NULL)")
public class TreeEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tree_id", nullable = false)
    private Tree tree;

    @ValidTreeEntryName
    @Column(nullable = false, length = 255,
            columnDefinition = "VARCHAR(255) COLLATE utf8mb4_bin")
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 16)
    private TreeEntryType entryType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "blob_id")
    private Blob blob;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "child_tree_id")
    private Tree childTree;

    protected TreeEntry() { }

    public TreeEntry(Tree tree, String name, TreeEntryType entryType, Blob blob, Tree childTree) {
        if (tree == null) throw new IllegalArgumentException("containing tree is required");
        if (entryType == null) throw new IllegalArgumentException("entry type is required");
        String normalizedName = name == null ? null : Normalizer.normalize(name, Normalizer.Form.NFC);
        if (!TreeEntryNameValidator.isValidName(normalizedName)) {
            throw new IllegalArgumentException("tree entry name must be a valid path segment");
        }
        if (entryType == TreeEntryType.FILE && (blob == null || childTree != null)) {
            throw new IllegalArgumentException("file entries require a blob and no child tree");
        }
        if (entryType == TreeEntryType.DIRECTORY && (blob != null || childTree == null)) {
            throw new IllegalArgumentException("directory entries require a child tree and no blob");
        }
        this.tree = tree;
        this.name = normalizedName;
        this.entryType = entryType;
        this.blob = blob;
        this.childTree = childTree;
    }

    public Long getId() { return id; }
    public Tree getTree() { return tree; }
    public String getName() { return name; }
    public TreeEntryType getEntryType() { return entryType; }
    public Blob getBlob() { return blob; }
    public Tree getChildTree() { return childTree; }
}
