#!/usr/bin/env bash
# Back up the Goga gateway: SQLite data, Caddy state (certificates), .env and
# deploy files into one tarball (mode 600). Run as root from deploy/:
#   ./backup.sh [output-dir]          # default: $GOGA_BACKUP_DIR or /var/backups/goga
# The default is outside the git checkout so a tarball with secrets is never committed.
#   GOGA_BACKUP_HOT=1 ./backup.sh     # do not stop the gateway (less safe)
# The gateway is stopped for a few seconds by default so the SQLite files
# (including -wal/-shm) are consistent. Caddy keeps running.
set -euo pipefail

DEPLOY_DIR="${GOGA_DEPLOY_DIR:-$(cd "$(dirname "$0")" && pwd)}"
COMPOSE_FILE="${GOGA_COMPOSE_FILE:-docker-compose.prod.yml}"
OUT_DIR="${1:-${GOGA_BACKUP_DIR:-/var/backups/goga}}"
cd "$DEPLOY_DIR"

[[ -f .env ]] || { echo "No .env in $DEPLOY_DIR" >&2; exit 1; }
env_value() { sed -n "s/^$1=//p" .env | tail -n1; }
DATA_DIR="$(env_value GOGA_DATA_DIR)"; DATA_DIR="${DATA_DIR:-./data/gateway}"
CADDY_DATA="$(env_value CADDY_DATA_DIR)"; CADDY_DATA="${CADDY_DATA:-./data/caddy}"

umask 077
mkdir -p "$OUT_DIR"
TS="$(date +%Y%m%d-%H%M%S)"
OUT="$OUT_DIR/goga-backup-$TS.tar.gz"

items=(.env "$COMPOSE_FILE" Caddyfile "$DATA_DIR")
[[ -d "$CADDY_DATA" ]] && items+=("$CADDY_DATA")
[[ -f backup.sh ]] && items+=(backup.sh)

stopped=0
restart_gateway() {
  if [[ "$stopped" == 1 ]]; then
    docker compose -f "$COMPOSE_FILE" start gateway >/dev/null
  fi
}
trap restart_gateway EXIT

if [[ "${GOGA_BACKUP_HOT:-0}" != 1 ]]; then
  docker compose -f "$COMPOSE_FILE" stop gateway >/dev/null
  stopped=1
fi

tar -czf "$OUT" --numeric-owner "${items[@]}"
git rev-parse HEAD > "$OUT.commit" 2>/dev/null && chmod 600 "$OUT.commit" || true
chmod 600 "$OUT"
restart_gateway
stopped=0

sha256sum "$OUT"
echo "Backup written: $OUT"
