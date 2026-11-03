#!/usr/bin/env bash
# Бэкап БД с ротацией. Cron: 0 3 * * * /opt/calorie-bot/scripts/backup.sh
# Переменные: PGHOST, PGPORT, PGUSER, PGPASSWORD (или ~/.pgpass), BACKUP_DIR, KEEP_DAYS
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/var/backups/calorie-bot}"
KEEP_DAYS="${KEEP_DAYS:-14}"
DB_NAME="${DB_NAME:-calories}"

mkdir -p "$BACKUP_DIR"
file="$BACKUP_DIR/${DB_NAME}_$(date +%Y%m%d_%H%M%S).sql.gz"
pg_dump --no-owner "$DB_NAME" | gzip > "$file"
echo "Backup written: $file"

find "$BACKUP_DIR" -name "${DB_NAME}_*.sql.gz" -mtime +"$KEEP_DAYS" -delete
