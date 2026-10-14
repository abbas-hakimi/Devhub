CREATE TABLE workflow_definitions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    repository_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NULL,
    trigger_type VARCHAR(20) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    configuration_version INT NOT NULL DEFAULT 1,
    configuration_json TEXT NOT NULL,
    created_by BIGINT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_workflow_definitions PRIMARY KEY (id),
    CONSTRAINT ck_workflow_trigger_type CHECK (trigger_type IN ('MANUAL','PUSH','PULL_REQUEST')),
    CONSTRAINT ck_workflow_configuration_version CHECK (configuration_version = 1),
    CONSTRAINT fk_workflow_definitions_repository FOREIGN KEY (repository_id) REFERENCES repositories(id) ON DELETE CASCADE,
    CONSTRAINT fk_workflow_definitions_creator FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT uk_workflow_repository_name UNIQUE (repository_id, name),
    CONSTRAINT uk_workflow_id_repository UNIQUE (id, repository_id)
);
CREATE INDEX idx_workflow_definitions_trigger ON workflow_definitions(repository_id, enabled, trigger_type, id);

CREATE TABLE workflow_runs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workflow_id BIGINT NOT NULL,
    repository_id BIGINT NOT NULL,
    dispatch_key VARCHAR(160) NOT NULL,
    trigger_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    branch_name VARCHAR(255) NULL,
    commit_sha VARCHAR(64) NULL,
    simulated_outcome VARCHAR(16) NOT NULL DEFAULT 'SUCCESS',
    triggered_by BIGINT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    started_at TIMESTAMP(6) NULL,
    completed_at TIMESTAMP(6) NULL,
    CONSTRAINT pk_workflow_runs PRIMARY KEY (id),
    CONSTRAINT ck_workflow_runs_trigger CHECK (trigger_type IN ('MANUAL','PUSH','PULL_REQUEST')),
    CONSTRAINT ck_workflow_runs_status CHECK (status IN ('QUEUED','RUNNING','SUCCESS','FAILURE','CANCELLED')),
    CONSTRAINT ck_workflow_runs_outcome CHECK (simulated_outcome IN ('SUCCESS','FAILURE')),
    CONSTRAINT fk_workflow_runs_workflow_repository FOREIGN KEY (workflow_id, repository_id)
        REFERENCES workflow_definitions(id, repository_id) ON DELETE CASCADE,
    CONSTRAINT fk_workflow_runs_triggered_by FOREIGN KEY (triggered_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT uk_workflow_runs_dispatch UNIQUE (workflow_id, dispatch_key)
);
CREATE INDEX idx_workflow_runs_repository_created ON workflow_runs(repository_id, created_at, id);
CREATE INDEX idx_workflow_runs_workflow_created ON workflow_runs(workflow_id, created_at, id);
CREATE INDEX idx_workflow_runs_status_created ON workflow_runs(status, created_at, id);

CREATE TABLE workflow_jobs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workflow_run_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    sequence_number INT NOT NULL,
    started_at TIMESTAMP(6) NULL,
    completed_at TIMESTAMP(6) NULL,
    CONSTRAINT pk_workflow_jobs PRIMARY KEY (id),
    CONSTRAINT ck_workflow_jobs_status CHECK (status IN ('QUEUED','RUNNING','SUCCESS','FAILURE','CANCELLED')),
    CONSTRAINT ck_workflow_jobs_sequence CHECK (sequence_number >= 0),
    CONSTRAINT fk_workflow_jobs_run FOREIGN KEY (workflow_run_id) REFERENCES workflow_runs(id) ON DELETE CASCADE,
    CONSTRAINT uk_workflow_jobs_sequence UNIQUE (workflow_run_id, sequence_number),
    CONSTRAINT uk_workflow_jobs_name UNIQUE (workflow_run_id, name)
);
CREATE INDEX idx_workflow_jobs_run_status ON workflow_jobs(workflow_run_id, status, sequence_number);

CREATE TABLE workflow_job_logs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workflow_job_id BIGINT NOT NULL,
    sequence_number INT NOT NULL,
    log_level VARCHAR(10) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_workflow_job_logs PRIMARY KEY (id),
    CONSTRAINT ck_workflow_job_logs_level CHECK (log_level IN ('INFO','WARN','ERROR')),
    CONSTRAINT ck_workflow_job_logs_sequence CHECK (sequence_number >= 0),
    CONSTRAINT fk_workflow_job_logs_job FOREIGN KEY (workflow_job_id) REFERENCES workflow_jobs(id) ON DELETE CASCADE,
    CONSTRAINT uk_workflow_job_logs_sequence UNIQUE (workflow_job_id, sequence_number)
);
CREATE INDEX idx_workflow_job_logs_page ON workflow_job_logs(workflow_job_id, sequence_number, id);
