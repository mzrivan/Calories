--liquibase formatted sql

--changeset calories:005-activity-entry
-- Произвольные активности (уборка, тренировка и т.п.) с оценкой расхода; kcal — ЧИСТЫЙ расход сверх основного обмена.
create table activity_entry (
    id             bigserial primary key,
    user_id        bigint       not null references app_user (id),
    day            date         not null,
    name           text         not null,
    minutes        numeric(6,1) not null check (minutes > 0 and minutes <= 1440),
    met            numeric(4,1) check (met >= 1 and met <= 20),
    kcal           numeric(7,1) not null check (kcal >= 0),
    confidence     text         not null default 'MEDIUM' check (confidence in ('LOW', 'MEDIUM', 'HIGH')),
    assumptions    text,
    created_at     timestamptz  not null default now(),
    active         boolean      not null default true,
    deactivated_at timestamptz
);
create index activity_entry_user_day_idx on activity_entry (user_id, day) where active;

--changeset calories:005-activity-entry-protection splitStatements:false
create trigger activity_entry_no_delete before delete on activity_entry
    for each row execute function forbid_delete();

do $$
begin
    if exists (select 1 from pg_roles where rolname = 'calorie_app') then
        grant select, insert, update on activity_entry to calorie_app;
        grant usage, select on all sequences in schema public to calorie_app;
    end if;
end
$$;
