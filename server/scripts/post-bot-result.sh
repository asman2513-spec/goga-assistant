#!/usr/bin/env bash
# Sign a bot -> gateway result and POST it.
# Signature = lowercase hex(HMAC_SHA256(secret, "<unix-seconds>.<raw body bytes>")).
#
#   BOT_INBOUND_SECRET=... GATEWAY_URL=http://127.0.0.1:8080 \
#     ./post-bot-result.sh '{"event_id":"...","kind":"event","reply_text":"Сводка"}'
#
#   ./post-bot-result.sh --file body.json
#
# GOGA_TIMESTAMP overrides the Unix seconds (10 digits). The body bytes that are
# signed are exactly the bytes that are sent.
set -euo pipefail

usage() {
  echo "Usage: BOT_INBOUND_SECRET=... GATEWAY_URL=http://127.0.0.1:8080 $0 '<json>'" >&2
  echo "       BOT_INBOUND_SECRET=... $0 --file body.json" >&2
  exit 2
}

if [[ $# -lt 1 ]]; then
  usage
fi

: "${BOT_INBOUND_SECRET:?Set BOT_INBOUND_SECRET}"
GATEWAY_URL="${GATEWAY_URL:-http://127.0.0.1:8080}"
TS="${GOGA_TIMESTAMP:-$(date +%s)}"

tmpdir=$(mktemp -d)
trap 'rm -rf "$tmpdir"' EXIT
body_file="$tmpdir/body.json"

if [[ "$1" == "--file" ]]; then
  [[ $# -eq 2 ]] || usage
  cp "$2" "$body_file"
else
  printf '%s' "$1" > "$body_file"
fi

SIG=$(
  {
    printf '%s' "${TS}."
    cat "$body_file"
  } | openssl dgst -sha256 -hmac "${BOT_INBOUND_SECRET}" -hex | awk '{print $NF}'
)

curl --fail-with-body -sS -X POST "${GATEWAY_URL%/}/v1/bot/results" \
  -H "Content-Type: application/json; charset=utf-8" \
  -H "X-Goga-Timestamp: ${TS}" \
  -H "X-Goga-Signature: ${SIG}" \
  --data-binary @"$body_file"
echo
