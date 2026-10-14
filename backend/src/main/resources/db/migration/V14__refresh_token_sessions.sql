CREATE TABLE refresh_sessions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    token_family_id CHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at TIMESTAMP(6) NOT NULL,
    retained_until TIMESTAMP(6) NOT NULL,
    last_used_at TIMESTAMP(6) NOT NULL,
    revoked_at TIMESTAMP(6) NULL,
    revocation_reason VARCHAR(40) NULL,
    user_agent VARCHAR(255) NULL,
    CONSTRAINT pk_refresh_sessions PRIMARY KEY (id),
    CONSTRAINT uk_refresh_sessions_family UNIQUE (token_family_id),
    CONSTRAINT fk_refresh_sessions_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX idx_refresh_sessions_user_created ON refresh_sessions(user_id, created_at, id);
CREATE INDEX idx_refresh_sessions_user_active ON refresh_sessions(user_id, revoked_at, expires_at);
CREATE INDEX idx_refresh_sessions_retention ON refresh_sessions(retained_until);

CREATE TABLE refresh_token_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL,
    generation INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at TIMESTAMP(6) NOT NULL,
    used_at TIMESTAMP(6) NULL,
    revoked_at TIMESTAMP(6) NULL,
    CONSTRAINT pk_refresh_token_history PRIMARY KEY (id),
    CONSTRAINT uk_refresh_token_history_hash UNIQUE (token_hash),
    CONSTRAINT uk_refresh_token_history_generation UNIQUE (session_id, generation),
    CONSTRAINT ck_refresh_token_history_generation CHECK (generation >= 0),
    CONSTRAINT fk_refresh_token_history_session FOREIGN KEY (session_id) REFERENCES refresh_sessions(id) ON DELETE CASCADE
);
CREATE INDEX idx_refresh_token_history_session_state ON refresh_token_history(session_id, used_at, revoked_at);
CREATE INDEX idx_refresh_token_history_expiry ON refresh_token_history(expires_at);
