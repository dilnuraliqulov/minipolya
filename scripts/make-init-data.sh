#!/usr/bin/env bash
#
# Builds a valid Telegram Mini App initData string for a fake user, signed with
# BOT_TOKEN, so local endpoints can be exercised without the real Telegram client.
#
# Signing matches TelegramAuthValidator:
#   secret_key = HMAC-SHA256(key = "WebAppData", data = bot_token)
#   hash       = HMAC-SHA256(key = secret_key,  data = data_check_string)
# where data_check_string is every field except `hash`, sorted by key and joined
# as `key=value` with newlines, using the URL-DECODED values.
#
# Usage:
#   BOT_TOKEN=... scripts/make-init-data.sh <telegram_id> [first_name] [username] [language_code]
#
# BOT_TOKEN is read from the environment only — never hardcode it here.

set -euo pipefail

if [[ -z "${BOT_TOKEN:-}" ]]; then
  echo "BOT_TOKEN is not set in the environment" >&2
  exit 1
fi

TELEGRAM_ID="${1:?usage: make-init-data.sh <telegram_id> [first_name] [username] [language_code]}"
FIRST_NAME="${2:-Test}"
USERNAME="${3:-testuser$TELEGRAM_ID}"
LANGUAGE_CODE="${4:-uz}"
AUTH_DATE="${AUTH_DATE:-$(date +%s)}"

# Percent-encodes every byte that is not an unreserved URI character.
urlencode() {
  local s="$1" out="" i c
  for (( i = 0; i < ${#s}; i++ )); do
    c="${s:i:1}"
    case "$c" in
      [A-Za-z0-9.~_-]) out+="$c" ;;
      *) out+=$(printf '%%%02X' "'$c") ;;
    esac
  done
  printf '%s' "$out"
}

# HMAC-SHA256 where the key is given as a hex string; prints the hex digest.
hmac_hex_key() {
  openssl dgst -sha256 -mac HMAC -macopt "hexkey:$1" -hex | sed 's/^.*= *//'
}

USER_JSON=$(printf '{"id":%s,"first_name":"%s","username":"%s","language_code":"%s"}' \
  "$TELEGRAM_ID" "$FIRST_NAME" "$USERNAME" "$LANGUAGE_CODE")
QUERY_ID="AAF${TELEGRAM_ID}"

# data_check_string: keys sorted alphabetically (auth_date, query_id, user), raw values.
DATA_CHECK_STRING=$(printf 'auth_date=%s\nquery_id=%s\nuser=%s' "$AUTH_DATE" "$QUERY_ID" "$USER_JSON")

SECRET_HEX=$(printf '%s' "$BOT_TOKEN" \
  | openssl dgst -sha256 -mac HMAC -macopt key:WebAppData -hex | sed 's/^.*= *//')
HASH=$(printf '%s' "$DATA_CHECK_STRING" | hmac_hex_key "$SECRET_HEX")

printf 'auth_date=%s&query_id=%s&user=%s&hash=%s\n' \
  "$AUTH_DATE" "$(urlencode "$QUERY_ID")" "$(urlencode "$USER_JSON")" "$HASH"
