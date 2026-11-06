--liquibase formatted sql

--changeset calories:007-bot-message
create table bot_message (
    id bigserial primary key,
    user_id bigint not null references app_user (id),
    chat_id bigint not null,
    message_id integer not null,
    content text not null,
    records_json jsonb not null,
    unique (user_id, chat_id, message_id)
);
create index bot_message_content_idx on bot_message (user_id, md5(content));

create table bot_message_selection (
    user_id bigint not null references app_user (id),
    chat_id bigint not null,
    bot_message_id bigint not null references bot_message (id),
    expires_at timestamptz not null,
    primary key (user_id, chat_id)
);

--changeset calories:007-bot-message-grants splitStatements:false
do $$
begin
    if exists (select 1 from pg_roles where rolname = 'calorie_app') then
        grant select, insert, update on bot_message, bot_message_selection to calorie_app;
        grant usage, select on all sequences in schema public to calorie_app;
    end if;
end
$$;
