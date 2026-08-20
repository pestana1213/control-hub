--liquibase formatted sql

--changeset joao:004-add-updated-at-to-operations

ALTER TABLE operations
    ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE;

UPDATE operations
SET updated_at = requested_at
WHERE updated_at IS NULL;

ALTER TABLE operations
    ALTER COLUMN updated_at SET NOT NULL;
