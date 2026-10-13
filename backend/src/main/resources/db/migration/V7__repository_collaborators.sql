ALTER TABLE repositories ADD CONSTRAINT uk_repositories_id_owner_collaborators UNIQUE (id, owner_id);

CREATE TABLE repository_collaborators (
    id BIGINT NOT NULL AUTO_INCREMENT,
    repository_id BIGINT NOT NULL,
    repository_owner_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    invited_by_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    accepted_at TIMESTAMP(6) NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_repository_collaborators PRIMARY KEY (id),
    CONSTRAINT uk_repository_collaborators_repository_user UNIQUE (repository_id, user_id),
    CONSTRAINT ck_repository_collaborators_role CHECK (role IN ('READ', 'WRITE', 'MAINTAIN')),
    CONSTRAINT ck_repository_collaborators_status CHECK (status IN ('PENDING', 'ACCEPTED')),
    CONSTRAINT ck_repository_collaborators_not_owner CHECK (user_id <> repository_owner_id),
    CONSTRAINT fk_repository_collaborators_repository FOREIGN KEY (repository_id) REFERENCES repositories (id) ON DELETE RESTRICT,
    CONSTRAINT fk_repository_collaborators_repository_owner FOREIGN KEY (repository_id, repository_owner_id)
        REFERENCES repositories (id, owner_id) ON DELETE RESTRICT,
    CONSTRAINT fk_repository_collaborators_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_repository_collaborators_invited_by FOREIGN KEY (invited_by_id) REFERENCES users (id) ON DELETE RESTRICT
);
CREATE INDEX idx_repository_collaborators_user_status ON repository_collaborators (user_id, status, created_at);
CREATE INDEX idx_repository_collaborators_repository_status ON repository_collaborators (repository_id, status, role);
