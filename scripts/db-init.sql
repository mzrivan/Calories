-- Разовая инициализация БД. Выполнять суперпользователем:
--   psql -U postgres -v app_password="'secret1'" -v owner_password="'secret2'" -f scripts/db-init.sql
-- Роли: calorie_owner (миграции Liquibase) и calorie_app (приложение, БЕЗ права DELETE).
create role calorie_owner login password :owner_password;
create role calorie_app   login password :app_password;
create database calories owner calorie_owner;
\connect calories
create extension if not exists pg_trgm;
grant connect on database calories to calorie_app;
grant usage on schema public to calorie_app;
