#!/bin/bash
# Выполняется один раз при первой инициализации контейнера postgres.
set -e
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" <<-EOSQL
    create extension if not exists pg_trgm;
    create extension if not exists vector;
    create role calorie_app login password '${APP_ROLE_PASSWORD}';
    grant connect on database ${POSTGRES_DB} to calorie_app;
    grant usage on schema public to calorie_app;
EOSQL
