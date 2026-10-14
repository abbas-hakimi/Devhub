ALTER TABLE commits
    ADD COLUMN second_parent_commit_id BIGINT NULL AFTER parent_commit_id;

ALTER TABLE commits
    ADD CONSTRAINT fk_commits_second_parent_same_repository
        FOREIGN KEY (repository_id, second_parent_commit_id)
        REFERENCES commits (repository_id, id) ON DELETE RESTRICT;

CREATE INDEX idx_commits_repository_second_parent
    ON commits (repository_id, second_parent_commit_id);
