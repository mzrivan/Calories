# Calorie bot

Telegram-бот для подсчёта КБЖУ + клетчатки для двух человек. Сообщение сначала разбирает код, и только если он не справился, оно уходит в LLM (Gemini через LangChain4j): 
1. команды и ключевые слова — вес, шаги, профиль, цель, итоги, баланс;
2. список «блюдо + вес» («Хлеб 13, картошка 300, мандарин 60») — поиск в справочнике (точное имя → алиас → pg_trgm), точные совпадения записываются сразу, примерные — после подтверждения кнопкой;
3. незнакомые блюда из списка уходят в LLM ОДНИМ запросом, ответ (КБЖУ на 100 г) подтверждается и сохраняется в справочник;
4. всё остальное (рецепты, КБЖУ в тексте, правки, вопросы) — LLM-агент с инструментами.

Числа считает и суммирует код, блоки «Итого за сегодня» и «Расход / Баланс» дописывает тоже он. Физически данные не удаляются.

Стек: Java 21, Spring Boot 3.4, Gradle, PostgreSQL 16 + pg_trgm, Liquibase (SQL), telegrambots (long polling), LangChain4j + Gemini.

Подробности решений — в [docs/decisions.md](docs/decisions.md), исходное ТЗ — `calorie-bot-claude-code-prompt.md`.

## Структура

```
src/main/java/kz/calories
  agent/       AgentService (цикл, rate limit, retry), AgentTools (инструменты LLM), системный промпт
  bot/         CalorieBot (Telegram API), MessageRouter (команды, кнопки, whitelist)
  parse/       MealTextParser (список «блюдо + вес»), IntentParser (ключевые слова) — чистые функции
  llm/         AgentLlm (диалог с инструментами + пакетная оценка блюд), реализация на Gemini
  dish/        версии блюд, черновики, DishMatcher (точное имя → алиас → trgm)
  meal/        дневник, итоги дня, MealBatchService (запись пачки, подтверждение, отмена)
  energy/      вес, шаги, расход калорий (Миффлин–Сан-Жеор + шаги), баланс
  validation/  Nutrition, проверки (Атуотер, веса, диапазоны)
  user/        whitelist, цели, профиль (рост, возраст, пол)
src/main/resources/db/changelog   Liquibase (formatted SQL; 004 — справочник базовых продуктов)
src/main/resources/db/seed        products.csv, aliases.csv — базовые продукты на 100 г и алиасы
```

## 1. Локальная установка PostgreSQL 16

Ubuntu: `sudo apt install -y postgresql-16` (pg_trgm входит в postgresql-contrib).

Windows: установите PostgreSQL 16 либо запустите БД в Docker только для разработки:
`docker run -d --name pg -p 5432:5432 -e POSTGRES_PASSWORD=postgres postgres:16`.

Инициализация (суперпользователем): роли `calorie_owner` (миграции) и `calorie_app` (приложение, без `DELETE`):

```bash
psql -U postgres -v owner_password="'owner_pw'" -v app_password="'app_pw'" -f scripts/db-init.sql
```

## 2. Запуск

1. Создайте бота у @BotFather, получите ключ Gemini в Google AI Studio, узнайте telegram_id двух человек (например, @userinfobot).
2. `cp .env.example .env`, заполните. Для IDEA: Run Configuration → Environment variables → From file `.env` (или плагин EnvFile).
3. Запуск: `./gradlew bootRun` (или `CaloriesApplication` из IDEA).

Миграции Liquibase применяются при старте под `DB_MIGRATION_USER`; приложение работает под `DB_USER`. Справочник базовых продуктов (`db/seed/*.csv`) загружается миграцией 004; правьте CSV до первого запуска (после применения изменения идут новой миграцией).

Тесты: `./gradlew test`. Юнит-тесты работают везде; интеграционные (`*IT`, Testcontainers с `postgres:16`) требуют Docker и без него пропускаются. LLM в тестах — мок.

## 3. Использование

Пишите обычным текстом:

- **Еда списком:** «Хлеб 13, картошка с курицей 300, мандарин 60», «70 йогурт, 7 карамель», «Хлеб 26 масло 8 сыр масдам 25», «хлебец на 30ккал». Точные совпадения записываются сразу, примерные («олодьи» → «оладьи») и незнакомые блюда — карточкой с кнопкой «Записать».
- **Отмена и правка:** перешлите сообщение бота ему же, затем напишите «удали», «отмени», «отмена» или опишите изменения. Можно также ответить на исходное сообщение. Простая отмена деактивирует связанные записи без LLM, остальные правки разбирает агент. После пересылки выбор действует 15 минут; при неоднозначном совпадении текста используйте ответ на исходное сообщение или id из `/today`.
- **Расход калорий:** один раз «рост 178, возраст 35, пол м»; затем «вес 82,5», «шагов 9500» (можно «вчера шагов 8000»; значения за день обновляются). «баланс» или `/balance` — съедено минус потрачено; расход показывается и в `/today`.
- **Через LLM:** «вот рецепт борща: … готового вышло 3 кг», «творог Простоквашино 5%: 121 ккал, Б 16, Ж 5, У 3», «не 400, а 300 г», «удали последнюю запись», «цель 2000 ккал, белка 120 г».

Команды: `/start`, `/help`, `/today`, `/balance`, `/weight`, `/steps`, `/profile`, `/dishes [поиск]`. Формулы расхода и коэффициенты (`app.energy.*`) описаны в [docs/decisions.md](docs/decisions.md).

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
