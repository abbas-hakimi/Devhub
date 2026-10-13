CREATE TABLE pull_requests (
    id BIGINT NOT NULL AUTO_INCREMENT,
    repository_id BIGINT NOT NULL,
    number BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT NULL,
    author_id BIGINT NOT NULL,
    source_branch_id BIGINT NOT NULL,
    target_branch_id BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    source_head_commit_id_at_creation BIGINT NOT NULL,
    target_head_commit_id_at_creation BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    closed_at TIMESTAMP(6) NULL,
    merged_at TIMESTAMP(6) NULL,
    merged_by_id BIGINT NULL,
    CONSTRAINT pk_pull_requests PRIMARY KEY (id),
    CONSTRAINT uk_pull_requests_repository_number UNIQUE (repository_id, number),
    CONSTRAINT ck_pull_requests_number_positive CHECK (number > 0),
    CONSTRAINT ck_pull_requests_state CHECK (state IN ('OPEN', 'CLOSED', 'MERGED')),
    CONSTRAINT ck_pull_requests_distinct_branches CHECK (source_branch_id <> target_branch_id),
    CONSTRAINT fk_pull_requests_repository FOREIGN KEY (repository_id)
        REFERENCES repositories (id) ON DELETE RESTRICT,
    CONSTRAINT fk_pull_requests_author FOREIGN KEY (author_id)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_pull_requests_merged_by FOREIGN KEY (merged_by_id)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_pull_requests_source_branch FOREIGN KEY (repository_id, source_branch_id)
        REFERENCES branches (repository_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_pull_requests_target_branch FOREIGN KEY (repository_id, target_branch_id)
        REFERENCES branches (repository_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_pull_requests_source_creation_head FOREIGN KEY (repository_id, source_head_commit_id_at_creation)
        REFERENCES commits (repository_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_pull_requests_target_creation_head FOREIGN KEY (repository_id, target_head_commit_id_at_creation)
        REFERENCES commits (repository_id, id) ON DELETE RESTRICT
);

CREATE INDEX idx_pull_requests_repository_state ON pull_requests (repository_id, state, number);
CREATE INDEX idx_pull_requests_author ON pull_requests (author_id);
CREATE INDEX idx_pull_requests_source_branch ON pull_requests (repository_id, source_branch_id);
CREATE INDEX idx_pull_requests_target_branch ON pull_requests (repository_id, target_branch_id);
CREATE INDEX idx_pull_requests_created_at ON pull_requests (repository_id, created_at);
