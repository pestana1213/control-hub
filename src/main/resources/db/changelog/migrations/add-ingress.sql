--liquibase formatted sql

--changeset joao:add-ingress

ALTER TABLE applications ADD COLUMN ingress VARCHAR(200);