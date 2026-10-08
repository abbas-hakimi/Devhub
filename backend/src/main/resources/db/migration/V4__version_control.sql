CREATE TABLE blobs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    object_hash CHAR(64) COLLATE ascii_bin NOT NULL,
    size BIGINT UNSIGNED NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_blobs PRIMARY KEY (id),
    CONSTRAINT uk_blobs_object_hash UNIQUE (object_hash),
    CONSTRAINT ck_blobs_object_hash_format CHECK (object_hash REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_blobs_size_nonnegative CHECK (size >= 0)
);

CREATE TABLE trees (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tree_hash CHAR(64) COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_trees PRIMARY KEY (id),
    CONSTRAINT uk_trees_tree_hash UNIQUE (tree_hash),
    CONSTRAINT ck_trees_tree_hash_format CHECK (tree_hash REGEXP '^[0-9a-f]{64}$')
);

CREATE TABLE tree_entries (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tree_id BIGINT NOT NULL,
    name VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    entry_type VARCHAR(16) NOT NULL,
    blob_id BIGINT NULL,
    child_tree_id BIGINT NULL,
    CONSTRAINT pk_tree_entries PRIMARY KEY (id),
    CONSTRAINT uk_tree_entries_tree_name UNIQUE (tree_id, name),
    CONSTRAINT ck_tree_entries_type_target CHECK (
        (entry_type = 'FILE' AND blob_id IS NOT NULL AND child_tree_id IS NULL)
        OR (entry_type = 'DIRECTORY' AND blob_id IS NULL AND child_tree_id IS NOT NULL)
    ),
    CONSTRAINT ck_tree_entries_type CHECK (entry_type IN ('FILE', 'DIRECTORY')),
    CONSTRAINT ck_tree_entries_name CHECK (
        name <> ''
        AND name NOT IN ('.', '..')
        AND LOCATE('/', name) = 0
        AND LOCATE(CHAR(92), name) = 0
    ),
    CONSTRAINT fk_tree_entries_tree FOREIGN KEY (tree_id) REFERENCES trees (id) ON DELETE CASCADE,
    CONSTRAINT fk_tree_entries_blob FOREIGN KEY (blob_id) REFERENCES blobs (id) ON DELETE RESTRICT,
    CONSTRAINT fk_tree_entries_child_tree FOREIGN KEY (child_tree_id) REFERENCES trees (id) ON DELETE RESTRICT
);
CREATE INDEX idx_tree_entries_blob ON tree_entries (blob_id);
CREATE INDEX idx_tree_entries_child_tree ON tree_entries (child_tree_id);

CREATE TABLE commits (
    id BIGINT NOT NULL AUTO_INCREMENT,
    repository_id BIGINT NOT NULL,
    commit_hash CHAR(64) COLLATE ascii_bin NOT NULL,
    message TEXT NOT NULL,
    author_id BIGINT NOT NULL,
    root_tree_id BIGINT NOT NULL,
    parent_commit_id BIGINT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_commits PRIMARY KEY (id),
    CONSTRAINT uk_commits_commit_hash UNIQUE (commit_hash),
    CONSTRAINT uk_commits_repository_id_id UNIQUE (repository_id, id),
    CONSTRAINT ck_commits_commit_hash_format CHECK (commit_hash REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT fk_commits_repository FOREIGN KEY (repository_id) REFERENCES repositories (id) ON DELETE RESTRICT,
    CONSTRAINT fk_commits_author FOREIGN KEY (author_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_commits_root_tree FOREIGN KEY (root_tree_id) REFERENCES trees (id) ON DELETE RESTRICT,
    CONSTRAINT fk_commits_parent_same_repository FOREIGN KEY (repository_id, parent_commit_id)
        REFERENCES commits (repository_id, id) ON DELETE RESTRICT
);
CREATE INDEX idx_commits_repository_created_id ON commits (repository_id, created_at, id);
CREATE INDEX idx_commits_repository_parent ON commits (repository_id, parent_commit_id);

CREATE TABLE branches (
    id BIGINT NOT NULL AUTO_INCREMENT,
    repository_id BIGINT NOT NULL,
    name VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    head_commit_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_branches PRIMARY KEY (id),
    CONSTRAINT uk_branches_repository_name UNIQUE (repository_id, name),
    CONSTRAINT uk_branches_repository_id_id UNIQUE (repository_id, id),
    CONSTRAINT fk_branches_repository FOREIGN KEY (repository_id) REFERENCES repositories (id) ON DELETE RESTRICT,
    CONSTRAINT fk_branches_head_same_repository FOREIGN KEY (repository_id, head_commit_id)
        REFERENCES commits (repository_id, id) ON DELETE RESTRICT
);
CREATE INDEX idx_branches_head_commit ON branches (head_commit_id);
