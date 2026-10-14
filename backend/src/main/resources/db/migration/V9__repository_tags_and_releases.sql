CREATE TABLE repository_tags (
    id BIGINT NOT NULL AUTO_INCREMENT,
    repository_id BIGINT NOT NULL,
    commit_id BIGINT NOT NULL,
    name VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    created_by_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_repository_tags PRIMARY KEY (id),
    CONSTRAINT uk_repository_tags_repository_name UNIQUE (repository_id, name),
    CONSTRAINT uk_repository_tags_repository_id_id UNIQUE (repository_id, id),
    CONSTRAINT ck_repository_tags_name_length CHECK (CHAR_LENGTH(name) BETWEEN 1 AND 255),
    CONSTRAINT fk_repository_tags_repository FOREIGN KEY (repository_id)
        REFERENCES repositories (id) ON DELETE RESTRICT,
    CONSTRAINT fk_repository_tags_commit_same_repository FOREIGN KEY (repository_id, commit_id)
        REFERENCES commits (repository_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_repository_tags_created_by FOREIGN KEY (created_by_id)
        REFERENCES users (id) ON DELETE RESTRICT
);
CREATE INDEX idx_repository_tags_commit ON repository_tags (repository_id, commit_id);
CREATE INDEX idx_repository_tags_created_at ON repository_tags (repository_id, created_at);

CREATE TABLE repository_releases (
    id BIGINT NOT NULL AUTO_INCREMENT,
    repository_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT NULL,
    draft BOOLEAN NOT NULL DEFAULT TRUE,
    prerelease BOOLEAN NOT NULL DEFAULT FALSE,
    created_by_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at TIMESTAMP(6) NULL,
    CONSTRAINT pk_repository_releases PRIMARY KEY (id),
    CONSTRAINT uk_repository_releases_repository_tag UNIQUE (repository_id, tag_id),
    CONSTRAINT uk_repository_releases_repository_id_id UNIQUE (repository_id, id),
    CONSTRAINT ck_repository_releases_title_length CHECK (CHAR_LENGTH(title) BETWEEN 1 AND 255),
    CONSTRAINT ck_repository_releases_publish_time CHECK (
        (draft = TRUE AND published_at IS NULL) OR (draft = FALSE AND published_at IS NOT NULL)
    ),
    CONSTRAINT fk_repository_releases_repository FOREIGN KEY (repository_id)
        REFERENCES repositories (id) ON DELETE RESTRICT,
    CONSTRAINT fk_repository_releases_tag_same_repository FOREIGN KEY (repository_id, tag_id)
        REFERENCES repository_tags (repository_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_repository_releases_created_by FOREIGN KEY (created_by_id)
        REFERENCES users (id) ON DELETE RESTRICT
);
CREATE INDEX idx_repository_releases_repository_publication
    ON repository_releases (repository_id, draft, published_at, id);
