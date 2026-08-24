--liquibase formatted sql

--changeset joao:005-normalize-operation-enums
UPDATE operations SET type = UPPER(type);
UPDATE operations SET status = UPPER(status);
