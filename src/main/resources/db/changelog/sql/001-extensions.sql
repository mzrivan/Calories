--liquibase formatted sql

--changeset calories:001-extensions
create extension if not exists pg_trgm;
create extension if not exists vector;
