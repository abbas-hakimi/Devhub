CREATE TABLE issues (
    id BIGINT NOT NULL AUTO_INCREMENT,
    repository_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    assignee_id BIGINT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    closed_at TIMESTAMP(6) NULL,
    CONSTRAINT pk_issues PRIMARY KEY (id),
    CONSTRAINT ck_issues_status CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT fk_issues_repository FOREIGN KEY (repository_id) REFERENCES repositories (id) ON DELETE RESTRICT,
    CONSTRAINT fk_issues_author FOREIGN KEY (author_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_issues_assignee FOREIGN KEY (assignee_id) REFERENCES users (id) ON DELETE RESTRICT
);
CREATE INDEX idx_issues_repository ON issues (repository_id);
CREATE INDEX idx_issues_author ON issues (author_id);
CREATE INDEX idx_issues_assignee ON issues (assignee_id);
CREATE INDEX idx_issues_repository_status ON issues (repository_id, status);

CREATE TABLE issue_comments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    issue_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    body TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_issue_comments PRIMARY KEY (id),
    CONSTRAINT fk_issue_comments_issue FOREIGN KEY (issue_id) REFERENCES issues (id) ON DELETE CASCADE,
    CONSTRAINT fk_issue_comments_author FOREIGN KEY (author_id) REFERENCES users (id) ON DELETE RESTRICT
);
CREATE INDEX idx_issue_comments_issue ON issue_comments (issue_id);
CREATE INDEX idx_issue_comments_author ON issue_comments (author_id);

CREATE TABLE labels (
    id BIGINT NOT NULL AUTO_INCREMENT,
    repository_id BIGINT NOT NULL,
    name VARCHAR(50) NOT NULL,
    description VARCHAR(255) NULL,
    color VARCHAR(7) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_labels PRIMARY KEY (id),
    CONSTRAINT uk_labels_repository_name UNIQUE (repository_id, name),
    CONSTRAINT fk_labels_repository FOREIGN KEY (repository_id) REFERENCES repositories (id) ON DELETE RESTRICT
);
CREATE INDEX idx_labels_repository ON labels (repository_id);

CREATE TABLE issue_labels (
    id BIGINT NOT NULL AUTO_INCREMENT,
    issue_id BIGINT NOT NULL,
    label_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_issue_labels PRIMARY KEY (id),
    CONSTRAINT uk_issue_labels_issue_label UNIQUE (issue_id, label_id),
    CONSTRAINT fk_issue_labels_issue FOREIGN KEY (issue_id) REFERENCES issues (id) ON DELETE CASCADE,
    CONSTRAINT fk_issue_labels_label FOREIGN KEY (label_id) REFERENCES labels (id) ON DELETE CASCADE
);
CREATE INDEX idx_issue_labels_issue ON issue_labels (issue_id);
CREATE INDEX idx_issue_labels_label ON issue_labels (label_id);
