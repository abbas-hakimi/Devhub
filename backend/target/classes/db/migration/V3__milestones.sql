CREATE TABLE milestones (
    id BIGINT NOT NULL AUTO_INCREMENT,
    repository_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    description TEXT NULL,
    due_date DATE NULL,
    state VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    closed_at TIMESTAMP(6) NULL,
    CONSTRAINT pk_milestones PRIMARY KEY (id),
    CONSTRAINT uk_milestones_repository_title UNIQUE (repository_id, title),
    CONSTRAINT ck_milestones_state CHECK (state IN ('OPEN', 'CLOSED')),
    CONSTRAINT fk_milestones_repository FOREIGN KEY (repository_id) REFERENCES repositories (id) ON DELETE RESTRICT
);
CREATE INDEX idx_milestones_repository ON milestones (repository_id);

ALTER TABLE issues ADD COLUMN milestone_id BIGINT NULL;
ALTER TABLE issues ADD CONSTRAINT fk_issues_milestone FOREIGN KEY (milestone_id) REFERENCES milestones (id) ON DELETE SET NULL;
CREATE INDEX idx_issues_milestone ON issues (milestone_id);
