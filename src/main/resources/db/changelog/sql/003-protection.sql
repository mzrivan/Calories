--liquibase formatted sql

--changeset calories:003-forbid-delete splitStatements:false
create function forbid_delete() returns trigger as $$
begin
    raise exception 'Physical DELETE is forbidden on %, use active = false', tg_table_name;
end;
$$ language plpgsql;

create trigger app_user_no_delete before delete on app_user
    for each row execute function forbid_delete();
create trigger dish_version_no_delete before delete on dish_version
    for each row execute function forbid_delete();
create trigger meal_entry_no_delete before delete on meal_entry
    for each row execute function forbid_delete();
create trigger daily_activity_no_delete before delete on daily_activity
    for each row execute function forbid_delete();
create trigger chat_message_no_delete before delete on chat_message
    for each row execute function forbid_delete();

--changeset calories:003-dish-version-immutable splitStatements:false
create function dish_version_immutable() returns trigger as $$
begin
    if (to_jsonb(new) - 'active' - 'deactivated_at') is distinct from (to_jsonb(old) - 'active' - 'deactivated_at') then
        raise exception 'dish_version is immutable: only active/deactivated_at may change, create a new version instead';
    end if;
    return new;
end;
$$ language plpgsql;

create trigger dish_version_immutable_trg before update on dish_version
    for each row execute function dish_version_immutable();

--changeset calories:003-app-role-grants splitStatements:false runOnChange:true
-- Роль calorie_app создаётся отдельно (scripts/db-init.sql). Права: без DELETE.
do $$
begin
    if exists (select 1 from pg_roles where rolname = 'calorie_app') then
        grant usage on schema public to calorie_app;
        grant select, insert, update on app_user, dish_version, meal_entry, chat_message, dish_draft,
            meal_draft, dish_alias, daily_activity to calorie_app;
        grant usage, select on all sequences in schema public to calorie_app;
    end if;
end
$$;
