--liquibase formatted sql

--changeset joao:004-create-application-status-events

CREATE TABLE application_status_events (
    id UUID PRIMARY KEY,
    application_id UUID NOT NULL,
    from_status VARCHAR(50),
    to_status VARCHAR(50) NOT NULL,
    reason_code VARCHAR(50),
    message TEXT,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_application_status_events_application
        FOREIGN KEY (application_id)
        REFERENCES applications(id)
        ON DELETE CASCADE
);

CREATE INDEX idx_application_status_events_application
    ON application_status_events(application_id, occurred_at DESC);

CREATE INDEX idx_application_status_events_occurred_at
    ON application_status_events(occurred_at);
