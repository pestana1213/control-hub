--liquibase formatted sql

--changeset joao:001-create-applications

CREATE TABLE applications (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    namespace VARCHAR(100) NOT NULL,
    description TEXT,
    repository_url VARCHAR(500),
    argocd_application VARCHAR(200),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT uk_applications_name_namespace
        UNIQUE (name, namespace)
);

--changeset joao:002-create-operations

CREATE TABLE operations (
    id UUID PRIMARY KEY,
    application_id UUID NOT NULL,
    type VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    error TEXT,

    CONSTRAINT fk_operations_application
        FOREIGN KEY (application_id)
        REFERENCES applications(id)
);

CREATE INDEX idx_operations_application_id
    ON operations(application_id);

CREATE INDEX idx_operations_status
    ON operations(status);

--changeset joao:003-create-audit-log

CREATE TABLE audit_log (
    id UUID PRIMARY KEY,
    operation_id UUID NOT NULL,
    action VARCHAR(100) NOT NULL,
    target VARCHAR(255) NOT NULL,
    result VARCHAR(50) NOT NULL,
    details JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_audit_log_operation
        FOREIGN KEY (operation_id)
        REFERENCES operations(id)
);

CREATE INDEX idx_audit_log_operation_id
    ON audit_log(operation_id);

CREATE INDEX idx_audit_log_created_at
    ON audit_log(created_at);