CREATE TABLE repository_discussions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    repository_id BIGINT NOT NULL,
    discussion_number BIGINT NOT NULL,
    category VARCHAR(24) NOT NULL,
    title VARCHAR(255) NOT NULL,
    body TEXT NOT NULL,
    author_id BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    accepted_reply_id BIGINT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    closed_at TIMESTAMP(6) NULL,
    CONSTRAINT pk_repository_discussions PRIMARY KEY (id),
    CONSTRAINT uk_discussions_repository_number UNIQUE (repository_id, discussion_number),
    CONSTRAINT uk_discussions_repository_id_id UNIQUE (repository_id, id),
    CONSTRAINT ck_discussions_number CHECK (discussion_number > 0),
    CONSTRAINT ck_discussions_category CHECK (category IN ('GENERAL','Q_AND_A','IDEAS','ANNOUNCEMENTS')),
    CONSTRAINT ck_discussions_state CHECK (state IN ('OPEN','CLOSED')),
    CONSTRAINT ck_discussions_title_length CHECK (CHAR_LENGTH(title) BETWEEN 1 AND 255),
    CONSTRAINT fk_discussions_repository FOREIGN KEY (repository_id) REFERENCES repositories(id) ON DELETE RESTRICT,
    CONSTRAINT fk_discussions_author FOREIGN KEY (author_id) REFERENCES users(id) ON DELETE RESTRICT
);
CREATE INDEX idx_discussions_repository_state_number ON repository_discussions(repository_id, state, discussion_number);
CREATE INDEX idx_discussions_repository_created ON repository_discussions(repository_id, created_at, id);
CREATE INDEX idx_discussions_author ON repository_discussions(author_id, repository_id);

CREATE TABLE discussion_replies (
    id BIGINT NOT NULL AUTO_INCREMENT,
    discussion_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    parent_reply_id BIGINT NULL,
    body TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_discussion_replies PRIMARY KEY (id),
    CONSTRAINT uk_discussion_replies_discussion_id UNIQUE (discussion_id, id),
    -- MySQL CHECK constraints cannot reference AUTO_INCREMENT columns; the service rejects self-parenting after ID assignment.
    CONSTRAINT fk_discussion_replies_discussion FOREIGN KEY (discussion_id) REFERENCES repository_discussions(id) ON DELETE RESTRICT,
    CONSTRAINT fk_discussion_replies_author FOREIGN KEY (author_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_discussion_replies_parent_same_discussion FOREIGN KEY (discussion_id, parent_reply_id)
        REFERENCES discussion_replies(discussion_id, id) ON DELETE RESTRICT
);
CREATE INDEX idx_discussion_replies_discussion_created ON discussion_replies(discussion_id, created_at, id);
CREATE INDEX idx_discussion_replies_parent ON discussion_replies(discussion_id, parent_reply_id);
CREATE INDEX idx_discussion_replies_author ON discussion_replies(author_id, discussion_id);

ALTER TABLE repository_discussions ADD CONSTRAINT fk_discussions_accepted_reply_same_discussion
    FOREIGN KEY (id, accepted_reply_id) REFERENCES discussion_replies(discussion_id, id) ON DELETE RESTRICT;

CREATE TABLE discussion_reactions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    discussion_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    reaction_type VARCHAR(24) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_discussion_reactions PRIMARY KEY (id),
    CONSTRAINT uk_discussion_reaction_user_type UNIQUE (discussion_id, user_id, reaction_type),
    CONSTRAINT ck_discussion_reaction_type CHECK (reaction_type IN ('THUMBS_UP','HEART','CELEBRATE','ROCKET')),
    CONSTRAINT fk_discussion_reactions_discussion FOREIGN KEY (discussion_id) REFERENCES repository_discussions(id) ON DELETE CASCADE,
    CONSTRAINT fk_discussion_reactions_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT
);
CREATE INDEX idx_discussion_reactions_counts ON discussion_reactions(discussion_id, reaction_type);

CREATE TABLE discussion_reply_reactions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    reply_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    reaction_type VARCHAR(24) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_discussion_reply_reactions PRIMARY KEY (id),
    CONSTRAINT uk_discussion_reply_reaction_user_type UNIQUE (reply_id, user_id, reaction_type),
    CONSTRAINT ck_discussion_reply_reaction_type CHECK (reaction_type IN ('THUMBS_UP','HEART','CELEBRATE','ROCKET')),
    CONSTRAINT fk_discussion_reply_reactions_reply FOREIGN KEY (reply_id) REFERENCES discussion_replies(id) ON DELETE CASCADE,
    CONSTRAINT fk_discussion_reply_reactions_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT
);
CREATE INDEX idx_discussion_reply_reactions_counts ON discussion_reply_reactions(reply_id, reaction_type);
