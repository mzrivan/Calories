# Calorie bot

Telegram-бот для подсчёта КБЖУ + клетчатки для двух человек. Любое сообщение уходит в LLM-агента (Gemini через LangChain4j), который сам вызывает инструменты: сохранить блюдо/продукт, записать приём пищи, поправить/деактивировать запись, показать итоги. Числа считает и суммирует код, блок «Итого за сегодня» дописывает тоже он. Физически данные не удаляются.

Стек: Java 21, Spring Boot 3.4, Gradle, PostgreSQL 16 + pgvector + pg_trgm, Liquibase (SQL), telegrambots (long polling), LangChain4j + Gemini.

Подробности решений — в [docs/decisions.md](docs/decisions.md), исходное ТЗ — `calorie-bot-claude-code-prompt.md`.

## Структура

```
src/main/java/kz/calories
  agent/       AgentService (цикл, rate limit, retry), AgentTools (инструменты LLM), системный промпт
  bot/         CalorieBot (Telegram API), MessageRouter (команды, кнопки, whitelist)
  llm/         AgentLlm / EmbeddingClient (свой интерфейс) + реализации на Gemini
  dish/        версии блюд, черновики, поиск (exact + trgm + pgvector)
  meal/        дневник, итоги дня, форматирование
  validation/  Nutrition, проверки (Атуотер, веса, диапазоны)
  user/        whitelist и цели
src/main/resources/db/changelog   Liquibase (formatted SQL)
```

## 1. Локальная установка PostgreSQL 16 + pgvector

Ubuntu:

```bash
sudo apt install -y postgresql-16 postgresql-16-pgvector
```

Windows: установите PostgreSQL 16 и pgvector (https://github.com/pgvector/pgvector#windows) либо запустите БД в Docker только для разработки:
`docker run -d --name pg -p 5432:5432 -e POSTGRES_PASSWORD=postgres pgvector/pgvector:pg16`.

Инициализация (суперпользователем): роли `calorie_owner` (миграции) и `calorie_app` (приложение, без `DELETE`):

```bash
psql -U postgres -v owner_password="'owner_pw'" -v app_password="'app_pw'" -f scripts/db-init.sql
```

## 2. Запуск

1. Создайте бота у @BotFather, получите ключ Gemini в Google AI Studio, узнайте telegram_id двух человек (например, @userinfobot).
2. `cp .env.example .env`, заполните. Для IDEA: Run Configuration → Environment variables → From file `.env` (или плагин EnvFile).
3. Запуск: `./gradlew bootRun` (или `CaloriesApplication` из IDEA).

Миграции Liquibase применяются при старте под `DB_MIGRATION_USER`; приложение работает под `DB_USER`. Если размерность эмбеддингов в конфиге не совпадает с БД, приложение не стартует.

Тесты: `./gradlew test`. Юнит-тесты работают везде; интеграционные (`*IT`, Testcontainers с `pgvector/pgvector:pg16`) требуют Docker и без него пропускаются. LLM в тестах — мок.

## 3. Использование

Пишите обычным текстом: «съел 300 г борща и 150 г творога», «вот рецепт борща: … готового вышло 3 кг», «творог Простоквашино 5%: 121 ккал, Б 16, Ж 5, У 3», «не 400, а 300 г», «удали последнюю запись», «цель 2000 ккал». Карточка нового блюда подтверждается кнопкой. Команды: `/start`, `/help`, `/today`, `/dishes [поиск]`.

## 4. Деплой на Ubuntu без Docker (этап 1)

```bash
./gradlew bootJar
sudo useradd -r -m caloriebot
sudo mkdir -p /opt/calorie-bot /etc/calorie-bot
sudo cp build/libs/calorie-bot-0.1.0.jar /opt/calorie-bot/calorie-bot.jar
sudo cp -r scripts /opt/calorie-bot/
sudo cp .env /etc/calorie-bot/calorie-bot.env && sudo chmod 600 /etc/calorie-bot/calorie-bot.env
sudo cp deploy/calorie-bot.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now calorie-bot
journalctl -u calorie-bot -f
```

**Ноутбук с закрытой крышкой:** в `/etc/systemd/logind.conf` поставьте `HandleLidSwitch=ignore` и `HandleLidSwitchExternalPower=ignore`, затем `sudo systemctl restart systemd-logind`; при необходимости `sudo systemctl mask sleep.target suspend.target hibernate.target hybrid-sleep.target`.

**Бэкап по cron** (`crontab -e` от пользователя с доступом к БД, пароль в `~/.pgpass`):

```
0 3 * * * PGUSER=calorie_owner PGHOST=localhost BACKUP_DIR=/var/backups/calorie-bot KEEP_DAYS=14 /opt/calorie-bot/scripts/backup.sh
```

Деактивация пользователя — только вручную: `update app_user set active=false, deactivated_at=now() where telegram_id=...;`.

## 5. Docker для прода (этап 2)

```bash
cp .env.example .env   # заполнить, включая DB_PASSWORD, DB_MIGRATION_PASSWORD
docker compose up -d --build
docker compose logs -f app
```

Приложение читает те же переменные окружения, что и без Docker; в compose переопределяется только `DB_URL` (хост `postgres`). Compose создаёт роли `calorie_owner`/`calorie_app` при первой инициализации тома (`scripts/docker-init-roles.sh`).

**Миграция с «без Docker» на compose:** остановить сервис (`systemctl stop calorie-bot`), сделать `scripts/backup.sh`, поднять `docker compose up -d postgres`, восстановить дамп: `gunzip -c backup.sql.gz | docker compose exec -T postgres psql -U calorie_owner calories`, затем `docker compose up -d app`. Дамп полный (схема + данные + `databasechangelog`), поэтому Liquibase после восстановления увидит миграции применёнными; сообщения «already exists» про расширения можно игнорировать. Роль `calorie_app` должна существовать до восстановления (её создаёт compose).

CI: `.gitlab-ci.yml` — сборка, тесты (Testcontainers через docker:dind), сборка и push образа в GitLab Registry.
