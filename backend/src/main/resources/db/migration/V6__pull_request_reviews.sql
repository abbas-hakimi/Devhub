ALTER TABLE pull_requests
    ADD CONSTRAINT uk_pull_requests_repository_id_id UNIQUE (repository_id, id);

CREATE TABLE pull_request_reviews (
    id BIGINT NOT NULL AUTO_INCREMENT,
    repository_id BIGINT NOT NULL,
    pull_request_id BIGINT NOT NULL,
    reviewer_id BIGINT NOT NULL,
    state VARCHAR(24) NOT NULL,
    body TEXT NULL,
    reviewed_head_commit_id BIGINT NOT NULL,
    reviewed_head_commit_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_pull_request_reviews PRIMARY KEY (id),
    CONSTRAINT uk_pull_request_reviews_pr_id UNIQUE (pull_request_id, id),
    CONSTRAINT ck_pull_request_reviews_state CHECK (state IN ('APPROVED', 'CHANGES_REQUESTED', 'COMMENTED')),
    CONSTRAINT fk_pull_request_reviews_pr FOREIGN KEY (repository_id, pull_request_id)
        REFERENCES pull_requests (repository_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_pull_request_reviews_reviewer FOREIGN KEY (reviewer_id)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_pull_request_reviews_head FOREIGN KEY (repository_id, reviewed_head_commit_id)
        REFERENCES commits (repository_id, id) ON DELETE RESTRICT
);
CREATE INDEX idx_pull_request_reviews_pr_created ON pull_request_reviews (pull_request_id, created_at, id);
CREATE INDEX idx_pull_request_reviews_reviewer ON pull_request_reviews (reviewer_id, pull_request_id, created_at);
CREATE INDEX idx_pull_request_reviews_state ON pull_request_reviews (pull_request_id, state, created_at);
CREATE INDEX idx_pull_request_reviews_head ON pull_request_reviews (reviewed_head_commit_id);

CREATE TABLE pull_request_review_comments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    repository_id BIGINT NOT NULL,
    pull_request_id BIGINT NOT NULL,
    review_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    path VARCHAR(1024) NOT NULL,
    line_number INT NOT NULL,
    side VARCHAR(8) NOT NULL,
    body TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_pull_request_review_comments PRIMARY KEY (id),
    CONSTRAINT ck_pull_request_review_comments_line CHECK (line_number > 0),
    CONSTRAINT ck_pull_request_review_comments_side CHECK (side IN ('OLD', 'NEW')),
    CONSTRAINT fk_pull_request_review_comments_pr FOREIGN KEY (repository_id, pull_request_id)
        REFERENCES pull_requests (repository_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_pull_request_review_comments_review FOREIGN KEY (pull_request_id, review_id)
        REFERENCES pull_request_reviews (pull_request_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_pull_request_review_comments_author FOREIGN KEY (author_id)
        REFERENCES users (id) ON DELETE RESTRICT
);
CREATE INDEX idx_pull_request_review_comments_pr_created ON pull_request_review_comments (pull_request_id, created_at, id);
CREATE INDEX idx_pull_request_review_comments_review ON pull_request_review_comments (review_id, id);
CREATE INDEX idx_pull_request_review_comments_author ON pull_request_review_comments (author_id);
