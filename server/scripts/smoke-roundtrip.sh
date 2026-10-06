#!/usr/bin/env bash
# Exercise a running gateway: pair a device, send a command, wait for the mock
# bot, then deliver an unsolicited event with post-bot-result.sh.
set -euo pipefail

BASE="${GATEWAY_URL:-http://127.0.0.1:8080}"
BOOT="${GATEWAY_BOOTSTRAP_TOKEN:-dev-bootstrap-token-change-me}"
SECRET="${BOT_INBOUND_SECRET:-dev-inbound-secret-change-me}"
ROOT="$(cd "$(dirname "$0")" && pwd)"

echo "Waiting for ${BASE}/health"
ready=0
for _ in $(seq 1 40); do
  if curl -fsS "${BASE}/health" >/dev/null; then
    ready=1
    break
  fi
  sleep 1
done
if [[ "$ready" != 1 ]]; then
  echo "Gateway did not become healthy" >&2
  exit 1
fi

IDEM="$(python3 -c 'import uuid; print(uuid.uuid4())')"
EVENT_ID="$(python3 -c 'import uuid; print(uuid.uuid4())')"

python3 - "$BASE" "$BOOT" "$IDEM" <<'PY'
import json, sys, time, urllib.request
base, boot, idem = sys.argv[1:]

def call(method, path, body=None, token=None):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Content-Type", "application/json; charset=utf-8")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req) as resp:
        raw = resp.read()
        return resp.status, json.loads(raw) if raw else None

_, issued = call("POST", "/v1/pairing-codes", token=boot)
_, paired = call("POST", "/v1/devices/pair", {"code": issued["code"], "device_name": "smoke"})
token = paired["device_token"]
text = "заметка из smoke"
_, created = call("POST", "/v1/commands", {
    "idempotency_key": idem,
    "text": text,
    "source": "text",
}, token)
status = None
for _ in range(50):
    _, got = call("GET", "/v1/commands/" + created["id"], token=token)
    status = got["status"]
    if status in ("completed", "failed"):
        if status != "completed":
            raise SystemExit("command failed: " + json.dumps(got, ensure_ascii=False))
        if text not in (got.get("result") or {}).get("reply_text", ""):
            raise SystemExit("unexpected reply: " + json.dumps(got, ensure_ascii=False))
        break
    time.sleep(0.2)
else:
    raise SystemExit("command stayed " + str(status))
_, notes = call("GET", "/v1/notes", token=token)
if not any(note["body"] == text for note in notes["notes"]):
    raise SystemExit("note was not stored")
print("command round-trip ok", created["id"])
PY

BODY="$(python3 - "$EVENT_ID" <<'PY'
import json, sys
print(json.dumps({
    "event_id": sys.argv[1],
    "kind": "event",
    "reply_text": "Сводка на сегодня",
}, ensure_ascii=False, separators=(",", ":")))
PY
)"

BOT_INBOUND_SECRET="$SECRET" GATEWAY_URL="$BASE" bash "$ROOT/post-bot-result.sh" "$BODY" >/tmp/goga-smoke-event.json
python3 - <<'PY'
import json
body = json.load(open("/tmp/goga-smoke-event.json"))
assert body["status"] == "accepted", body
print("unsolicited event ok", body["result_id"])
PY
