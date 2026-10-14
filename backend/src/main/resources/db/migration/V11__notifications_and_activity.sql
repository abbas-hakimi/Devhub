CREATE TABLE user_notifications (
    id BIGINT NOT NULL AUTO_INCREMENT,
    recipient_id BIGINT NOT NULL,
    actor_id BIGINT NULL,
    repository_id BIGINT NULL,
    event_type VARCHAR(48) NOT NULL,
    title VARCHAR(255) NOT NULL,
    message VARCHAR(500) NOT NULL,
    target_type VARCHAR(40) NOT NULL,
    target_id BIGINT NOT NULL,
    deduplication_key VARCHAR(190) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    read_at TIMESTAMP(6) NULL,
    CONSTRAINT pk_user_notifications PRIMARY KEY (id),
    CONSTRAINT uk_user_notifications_recipient_dedupe UNIQUE (recipient_id, deduplication_key),
    CONSTRAINT fk_user_notifications_recipient FOREIGN KEY (recipient_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_notifications_actor FOREIGN KEY (actor_id) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT fk_user_notifications_repository FOREIGN KEY (repository_id) REFERENCES repositories(id) ON DELETE CASCADE
);
CREATE INDEX idx_user_notifications_recipient_created ON user_notifications(recipient_id, created_at, id);
CREATE INDEX idx_user_notifications_recipient_read_created ON user_notifications(recipient_id, read_at, created_at, id);
CREATE INDEX idx_user_notifications_repository ON user_notifications(repository_id);

CREATE TABLE repository_activities (
    id BIGINT NOT NULL AUTO_INCREMENT,
    repository_id BIGINT NOT NULL,
    actor_id BIGINT NULL,
    event_type VARCHAR(48) NOT NULL,
    resource_type VARCHAR(40) NOT NULL,
    resource_id BIGINT NOT NULL,
    summary VARCHAR(500) NOT NULL,
    deduplication_key VARCHAR(190) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_repository_activities PRIMARY KEY (id),
    CONSTRAINT uk_repository_activities_dedupe UNIQUE (repository_id, deduplication_key),
    CONSTRAINT fk_repository_activities_repository FOREIGN KEY (repository_id) REFERENCES repositories(id) ON DELETE CASCADE,
    CONSTRAINT fk_repository_activities_actor FOREIGN KEY (actor_id) REFERENCES users(id) ON DELETE SET NULL
);
CREATE INDEX idx_repository_activities_page ON repository_activities(repository_id, created_at, id);
CREATE INDEX idx_repository_activities_resource ON repository_activities(resource_type, resource_id);
