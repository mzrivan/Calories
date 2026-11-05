--liquibase formatted sql

--changeset calories:006-meal-type
-- Приём пищи: BREAKFAST / LUNCH / DINNER; всё, что без ключевого слова, — OTHER («остальное»).
alter table meal_entry
    add column meal_type text not null default 'OTHER'
        check (meal_type in ('BREAKFAST', 'LUNCH', 'DINNER', 'OTHER'));
