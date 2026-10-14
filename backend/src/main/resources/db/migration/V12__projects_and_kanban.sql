CREATE TABLE projects (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(120) NOT NULL,
    description TEXT NULL,
    scope VARCHAR(16) NOT NULL,
    owner_id BIGINT NOT NULL,
    repository_id BIGINT NULL,
    visibility VARCHAR(16) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_projects PRIMARY KEY (id),
    CONSTRAINT ck_projects_scope CHECK (scope IN ('PERSONAL','REPOSITORY')),
    CONSTRAINT ck_projects_status CHECK (status IN ('ACTIVE','ARCHIVED')),
    CONSTRAINT ck_projects_visibility CHECK (visibility IS NULL OR visibility IN ('PRIVATE','PUBLIC')),
    CONSTRAINT ck_projects_ownership CHECK ((scope = 'PERSONAL' AND repository_id IS NULL AND visibility = 'PRIVATE') OR (scope = 'REPOSITORY' AND repository_id IS NOT NULL AND visibility IS NULL)),
    CONSTRAINT fk_projects_owner FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_projects_repository_owner FOREIGN KEY (repository_id, owner_id) REFERENCES repositories(id, owner_id) ON DELETE CASCADE,
    CONSTRAINT uk_projects_personal_name UNIQUE (owner_id, scope, name),
    CONSTRAINT uk_projects_repository_name UNIQUE (repository_id, name)
);
CREATE INDEX idx_projects_owner_scope_status ON projects(owner_id, scope, status, updated_at, id);
CREATE INDEX idx_projects_repository_status ON projects(repository_id, status, updated_at, id);

CREATE TABLE project_columns (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    name VARCHAR(80) NOT NULL,
    position INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_project_columns PRIMARY KEY (id),
    CONSTRAINT ck_project_columns_position CHECK (position >= 0),
    CONSTRAINT ck_project_columns_name CHECK (CHAR_LENGTH(TRIM(name)) BETWEEN 1 AND 80),
    CONSTRAINT fk_project_columns_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    CONSTRAINT uk_project_columns_project_position UNIQUE (project_id, position),
    CONSTRAINT uk_project_columns_project_name UNIQUE (project_id, name),
    CONSTRAINT uk_project_columns_project_id UNIQUE (project_id, id)
);
CREATE INDEX idx_project_columns_order ON project_columns(project_id, position, id);

CREATE TABLE project_cards (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    column_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT NULL,
    position INT NOT NULL,
    card_type VARCHAR(20) NOT NULL,
    issue_id BIGINT NULL,
    pull_request_id BIGINT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_project_cards PRIMARY KEY (id),
    CONSTRAINT ck_project_cards_position CHECK (position >= 0),
    CONSTRAINT ck_project_cards_type CHECK (card_type IN ('NOTE','ISSUE','PULL_REQUEST')),
    CONSTRAINT ck_project_cards_link CHECK ((card_type = 'NOTE' AND issue_id IS NULL AND pull_request_id IS NULL) OR (card_type = 'ISSUE' AND issue_id IS NOT NULL AND pull_request_id IS NULL) OR (card_type = 'PULL_REQUEST' AND issue_id IS NULL AND pull_request_id IS NOT NULL)),
    CONSTRAINT fk_project_cards_column_same_project FOREIGN KEY (project_id, column_id) REFERENCES project_columns(project_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_project_cards_issue FOREIGN KEY (issue_id) REFERENCES issues(id) ON DELETE RESTRICT,
    CONSTRAINT fk_project_cards_pull_request FOREIGN KEY (pull_request_id) REFERENCES pull_requests(id) ON DELETE RESTRICT,
    CONSTRAINT uk_project_cards_position UNIQUE (project_id, column_id, position),
    CONSTRAINT uk_project_cards_issue UNIQUE (project_id, issue_id),
    CONSTRAINT uk_project_cards_pull_request UNIQUE (project_id, pull_request_id)
);
CREATE INDEX idx_project_cards_project_column_order ON project_cards(project_id, column_id, position, id);
CREATE INDEX idx_project_cards_issue ON project_cards(issue_id);
CREATE INDEX idx_project_cards_pull_request ON project_cards(pull_request_id);
