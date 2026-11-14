#!/usr/bin/env bash
#
# Nightly backup of the MiniPolya database, keeping the last 14 days.
#
# Run from cron on the SERVER (step 12 of DEPLOYMENT-AWS.md installs the entry):
#
#     15 2 * * * /home/ubuntu/minipolya/minipolyaBackend/deploy/backup.sh >> /home/ubuntu/minipolya/backups/backup.log 2>&1
#
# What it writes, into ~/minipolya/backups by default:
#
#     minipolya-2026-11-14-0215.sql.gz     a full pg_dump, gzipped
#
# pg_dump runs INSIDE the postgres container, so the host needs no postgres client and no
# port is published. The dump is a plain-SQL, self-contained snapshot: restoring it needs
# nothing but psql. It is taken while the app keeps running — pg_dump uses one consistent
# snapshot and never blocks a reader or a writer.
#
# It does NOT back up the stadium photos. Those live on the uploads_data volume and their
# rows point at them, so a database-only restore would show gaps where photos were. Step 12
# has the one-line command for the photos as well; on a test server they are usually worth
# less than the hour it takes to re-upload a few.

set -euo pipefail

# ---------------------------------------------------------------------------------------
# Where things are. Derived from this script's own location, so moving the checkout does not
# break the cron entry.
# ---------------------------------------------------------------------------------------
DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKUP_DIR="${BACKUP_DIR:-$(cd "$DEPLOY_DIR/../.." && pwd)/backups}"
KEEP_DAYS="${KEEP_DAYS:-14}"

# The same .env docker compose reads. Overridable (ENV_FILE=…) for a configuration kept
# outside the checkout, and so this script can be exercised against a throwaway one.
ENV_FILE="${ENV_FILE:-$DEPLOY_DIR/.env}"

if [[ ! -f "$ENV_FILE" ]]; then
	echo "$(date -Is) ERROR: no $ENV_FILE — nothing to back up" >&2
	exit 1
fi

# POSTGRES_DB and POSTGRES_USER come from it. `set -a` marks everything it defines for
# export, so pg_dump inside the container inherits them.
set -a
# shellcheck disable=SC1090,SC1091  # a runtime path, and it holds secrets
source "$ENV_FILE"
set +a

: "${POSTGRES_DB:?POSTGRES_DB is not set in .env}"
: "${POSTGRES_USER:?POSTGRES_USER is not set in .env}"

mkdir -p "$BACKUP_DIR"
# The dumps contain every phone number and every booking in the system. Only this user.
chmod 700 "$BACKUP_DIR"

STAMP="$(date +%Y-%m-%d-%H%M)"
TARGET="$BACKUP_DIR/minipolya-$STAMP.sql.gz"
TEMP="$TARGET.partial"

echo "$(date -Is) backing up $POSTGRES_DB to $TARGET"

# ---------------------------------------------------------------------------------------
# The dump.
# ---------------------------------------------------------------------------------------
# -T: no TTY, so the output is the dump and nothing else.
# --clean --if-exists: the restore drops what it is replacing first, so restoring over a
#   database that still has tables works instead of failing halfway through.
# --no-owner --no-privileges: the restore does not demand that the same role names exist,
#   which is what lets you restore into a fresh container or onto your laptop.
#
# Written to a .partial file first and moved into place only on success: a backup that was
# interrupted (the instance rebooted, the disk filled) never looks like a good one. Both
# exit statuses are checked through PIPESTATUS, because without it a pg_dump failure would
# be hidden by gzip succeeding on the empty input.
if ! docker compose --project-directory "$DEPLOY_DIR" --env-file "$ENV_FILE" exec -T postgres \
	pg_dump --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
	--clean --if-exists --no-owner --no-privileges 2>"$TEMP.err" | gzip -9 >"$TEMP"; then
	echo "$(date -Is) ERROR: pg_dump failed" >&2
	cat "$TEMP.err" >&2 || true
	rm -f "$TEMP" "$TEMP.err"
	exit 1
fi
rm -f "$TEMP.err"

# A dump of an empty-but-reachable database is still a few hundred bytes of header, so
# anything under 1 KB means something went wrong quietly.
SIZE="$(stat -c %s "$TEMP")"
if [[ "$SIZE" -lt 1024 ]]; then
	echo "$(date -Is) ERROR: the dump is only $SIZE bytes — keeping it as $TEMP for a look" >&2
	exit 1
fi

mv "$TEMP" "$TARGET"
chmod 600 "$TARGET"
echo "$(date -Is) wrote $TARGET ($(numfmt --to=iec "$SIZE" 2>/dev/null || echo "$SIZE bytes") compressed)"

# ---------------------------------------------------------------------------------------
# Retention: delete dumps older than KEEP_DAYS days.
# ---------------------------------------------------------------------------------------
# Only this script's own naming pattern, so nothing else in the directory is ever touched —
# including a dump you copied in by hand under another name, and the .partial files above.
DELETED="$(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'minipolya-*.sql.gz' \
	-mtime "+$KEEP_DAYS" -print -delete | wc -l)"
if [[ "$DELETED" -gt 0 ]]; then
	echo "$(date -Is) deleted $DELETED backup(s) older than $KEEP_DAYS days"
fi

KEPT="$(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'minipolya-*.sql.gz' | wc -l)"
echo "$(date -Is) done — $KEPT backup(s) in $BACKUP_DIR"
