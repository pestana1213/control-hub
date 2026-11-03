--liquibase formatted sql

--changeset joao:005-create-application-desired-state

CREATE TABLE application_desired_state (
    application_id UUID PRIMARY KEY,
    image VARCHAR(500),
    replicas INTEGER,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_application_desired_state_application
        FOREIGN KEY (application_id)
        REFERENCES applications(id)
        ON DELETE CASCADE,

    CONSTRAINT ck_application_desired_state_not_empty
        CHECK (image IS NOT NULL OR replicas IS NOT NULL),

    CONSTRAINT ck_application_desired_state_replicas
        CHECK (replicas IS NULL OR replicas >= 0)
);
