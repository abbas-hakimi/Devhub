CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(100),
    bio TEXT,
    avatar_url VARCHAR(2048),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uk_users_username UNIQUE (username),
    CONSTRAINT uk_users_email UNIQUE (email)
);

CREATE TABLE repositories (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    visibility VARCHAR(20) NOT NULL,
    owner_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_repositories PRIMARY KEY (id),
    CONSTRAINT uk_repositories_owner_name UNIQUE (owner_id, name),
    CONSTRAINT ck_repositories_visibility CHECK (visibility IN ('PUBLIC', 'PRIVATE')),
    CONSTRAINT fk_repositories_owner FOREIGN KEY (owner_id) REFERENCES users (id)
);

CREATE INDEX idx_repositories_visibility ON repositories (visibility);

CREATE TABLE user_follows (
    id BIGINT NOT NULL AUTO_INCREMENT,
    follower_id BIGINT NOT NULL,
    following_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_user_follows PRIMARY KEY (id),
    CONSTRAINT uk_user_follows_follower_following UNIQUE (follower_id, following_id),
    CONSTRAINT ck_user_follows_no_self_follow CHECK (follower_id <> following_id),
    CONSTRAINT fk_user_follows_follower FOREIGN KEY (follower_id) REFERENCES users (id),
    CONSTRAINT fk_user_follows_following FOREIGN KEY (following_id) REFERENCES users (id)
);

CREATE INDEX idx_user_follows_following_id ON user_follows (following_id);

CREATE TABLE repository_stars (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    repository_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_repository_stars PRIMARY KEY (id),
    CONSTRAINT uk_repository_stars_user_repository UNIQUE (user_id, repository_id),
    CONSTRAINT fk_repository_stars_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_repository_stars_repository FOREIGN KEY (repository_id) REFERENCES repositories (id)
);

CREATE INDEX idx_repository_stars_repository_id ON repository_stars (repository_id);

