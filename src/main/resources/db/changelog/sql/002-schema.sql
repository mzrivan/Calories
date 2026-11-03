--liquibase formatted sql

--changeset calories:002-app-user
create table app_user (
    id             bigserial primary key,
    telegram_id    bigint      not null unique,
    name           text        not null,
    timezone       text        not null default 'Asia/Almaty',
    goal_kcal      numeric(8,1),
    goal_protein   numeric(7,1),
    goal_fat       numeric(7,1),
    goal_carbs     numeric(7,1),
    goal_fiber     numeric(7,1),
    created_at     timestamptz not null default now(),
    active         boolean     not null default true,
    deactivated_at timestamptz
);

--changeset calories:002-dish-version
create table dish_version (
    id                 bigserial primary key,
    name               text         not null,
    normalized_name    text         not null,
    embedding          vector(${embeddingDimensions}),
    kind               text         not null check (kind in ('RECIPE', 'PRODUCT')),
    kcal               numeric(7,1) not null check (kcal >= 0),
    protein_g          numeric(6,1) not null check (protein_g >= 0),
    fat_g              numeric(6,1) not null check (fat_g >= 0),
    carbs_g            numeric(6,1) not null check (carbs_g >= 0),
    fiber_g            numeric(6,1) check (fiber_g >= 0),
    cooked_weight_g    numeric(9,1) check (cooked_weight_g > 0),
    source_text        text,
    method             text,
    confidence         text         not null default 'MEDIUM' check (confidence in ('LOW', 'MEDIUM', 'HIGH')),
    assumptions        text,
    created_by_user_id bigint       not null references app_user (id),
    created_at         timestamptz  not null default now(),
    active             boolean      not null default true,
    deactivated_at     timestamptz
);

--changeset calories:002-meal-entry
create table meal_entry (
    id              bigserial primary key,
    user_id         bigint       not null references app_user (id),
    eaten_at        timestamptz  not null,
    name            text         not null,
    dish_version_id bigint       references dish_version (id),
    grams           numeric(8,1) not null check (grams > 0),
    kcal            numeric(8,1) not null check (kcal >= 0),
    protein_g       numeric(7,1) not null check (protein_g >= 0),
    fat_g           numeric(7,1) not null check (fat_g >= 0),
    carbs_g         numeric(7,1) not null check (carbs_g >= 0),
    fiber_g         numeric(7,1) check (fiber_g >= 0),
    confidence      text         not null default 'MEDIUM' check (confidence in ('LOW', 'MEDIUM', 'HIGH')),
    assumptions     text,
    created_at      timestamptz  not null default now(),
    active          boolean      not null default true,
    deactivated_at  timestamptz
);

--changeset calories:002-chat-message
create table chat_message (
    id         bigserial primary key,
    user_id    bigint      not null references app_user (id),
    role       text        not null check (role in ('USER', 'ASSISTANT')),
    content    text        not null,
    created_at timestamptz not null default now()
);

--changeset calories:002-dish-draft
create table dish_draft (
    id         bigserial primary key,
    user_id    bigint      not null references app_user (id),
    payload    jsonb       not null,
    status     text        not null default 'PENDING'
               check (status in ('PENDING', 'CONFIRMED', 'CANCELLED', 'SUPERSEDED', 'EXPIRED')),
    created_at timestamptz not null default now(),
    expires_at timestamptz not null
);

--changeset calories:002-indexes
create index dish_version_name_trgm_idx on dish_version using gin (normalized_name gin_trgm_ops);
create index dish_version_embedding_idx on dish_version using hnsw (embedding vector_cosine_ops) where active;
create index dish_version_latest_idx on dish_version (normalized_name, created_at desc) where active;
create index meal_entry_user_time_idx on meal_entry (user_id, eaten_at) where active;
create index chat_message_user_time_idx on chat_message (user_id, created_at desc);
create index dish_draft_user_pending_idx on dish_draft (user_id) where status = 'PENDING';
