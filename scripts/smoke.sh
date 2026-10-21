#!/usr/bin/env bash
# End-to-end smoke test: create a link through the Java API, follow the redirect
# through the Go redirector, confirm the click was counted, delete the link and
# confirm the redirect now 404s. Exits non-zero on the first failure.
set -euo pipefail

API_URL="${API_URL:-http://localhost:8080}"
REDIRECT_URL="${REDIRECT_URL:-http://localhost:8081}"
API_KEY="${API_KEY:-smoke-key}"
TARGET="https://example.com/smoke/$(date +%s)"

step() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
fail() { printf '\033[1;31mFAIL: %s\033[0m\n' "$*" >&2; exit 1; }

step "waiting for services"
for i in $(seq 1 30); do
  if curl -sf "$API_URL/actuator/health/readiness" >/dev/null && curl -sf "$REDIRECT_URL/healthz" >/dev/null; then
    break
  fi
  [ "$i" -eq 30 ] && fail "services did not become healthy"
  sleep 2
done

step "creating short link for $TARGET"
CREATE=$(curl -sf -X POST "$API_URL/api/v1/urls" \
  -H 'Content-Type: application/json' -H "X-API-Key: $API_KEY" \
  -d "{\"longUrl\":\"$TARGET\"}")
echo "$CREATE"
CODE=$(echo "$CREATE" | sed -n 's/.*"shortCode":"\([^"]*\)".*/\1/p')
[ -n "$CODE" ] || fail "no shortCode in response"

step "following redirect for $CODE"
LOCATION=$(curl -s -o /dev/null -w '%{redirect_url}' "$REDIRECT_URL/$CODE")
[ "$LOCATION" = "$TARGET" ] || fail "expected redirect to $TARGET, got '$LOCATION'"
echo "302 -> $LOCATION"

step "checking click was counted (write-behind, polling up to 10s)"
for i in $(seq 1 10); do
  CLICKS=$(curl -sf "$API_URL/api/v1/urls/$CODE/stats" -H "X-API-Key: $API_KEY" | sed -n 's/.*"clickCount":\([0-9]*\).*/\1/p')
  [ "${CLICKS:-0}" -ge 1 ] && break
  sleep 1
done
[ "${CLICKS:-0}" -ge 1 ] || fail "click count never reached 1"
echo "clickCount=$CLICKS"

step "deleting $CODE"
curl -sf -X DELETE "$API_URL/api/v1/urls/$CODE" -H "X-API-Key: $API_KEY" -o /dev/null
STATUS=$(curl -s -o /dev/null -w '%{http_code}' "$REDIRECT_URL/$CODE")
[ "$STATUS" = "404" ] || [ "$STATUS" = "410" ] || fail "expected 404/410 after delete, got $STATUS"
echo "redirect after delete -> $STATUS"

printf '\n\033[1;32mSMOKE TEST PASSED\033[0m\n'
