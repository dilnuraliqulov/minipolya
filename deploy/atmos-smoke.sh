#!/usr/bin/env bash
#
# Real ATMOS calls, end to end, from the SERVER.
#
#     cd ~/minipolya/minipolyaBackend/deploy
#     ./atmos-smoke.sh              # one payment: create, pre-apply, apply, get, reverse
#     ./atmos-smoke.sh --hold       # the holds: single, cancelled, multiple, partial capture
#     ./atmos-smoke.sh --all        # both, on one card binding, everything given back
#     ./atmos-smoke.sh --bisect     # one payment per difference from what the app sends
#
# It asks for a card and the SMS code ATMOS sends you, then buys an access token, binds the
# card, does the work, and gives back / releases everything it took. Every step prints its
# METHOD, its PATH, the HTTP status, ATMOS's own result code and OK or FAILED.
#
# Why a script and not a test: ATMOS answers only calls from an IP address it has allowed,
# which is this server. Nothing of this can be run from a laptop, and the sandbox is the
# only place the real answers — a real SMS, a real decline, a real commission, a real
# HTTP 403 — can be seen.
#
# WHAT IT NEVER PRINTS: the card number, the expiry, the SMS code, the card token, the
# access token, the consumer key or secret, or a cardholder's phone number (only its last
# two digits). The card and the code are read without echo and handed to the container on
# standard input, so they never appear in `ps`, in your shell history or in a file. The card
# token never leaves the container at all: everything that needs it happens in one call
# inside it. Response bodies are NOT printed — only the result code and its description.
#
# WHERE IT RUNS: the curl calls run INSIDE the backend container, which is where the ATMOS
# credentials already are (docker-compose.yml passes them). The host never sees them, and
# you need no .env access to run this.
#
# WHAT IT COSTS: payments of ATMOS_SMOKE_AMOUNT tiyin (500 so'm by default), each reversed
# in full, and holds of the same amount, each released or captured-and-reversed. The money
# goes to ATMOS_STORE_ID — our own store, never a stadium owner — so nothing lands in
# anybody else's account even if a give-back fails.
#
#     ATMOS_SMOKE_AMOUNT=100000 ./atmos-smoke.sh          # 1 000 so'm instead of 500
#     ATMOS_SMOKE_HOLD_MINUTES=30 ./atmos-smoke.sh --hold # hold for 30 minutes, not 10
#
# PRODUCTION: it refuses to run when the backend says ATMOS_ENVIRONMENT=production, because
# that would take real money from a real card. If you really mean to (real payments, all
# reversed), run it as:
#
#     ATMOS_SMOKE_ALLOW_PRODUCTION=yes ./atmos-smoke.sh
#
# Test cards for the sandbox are in the ATMOS docs ("Песочница"), not here. The OTP for
# every test card is 111111, and so is the OTP for any payment or hold made with a card
# token — which is why the hold steps need no SMS of their own.

set -euo pipefail

cd "$(dirname "$0")"

# ---------------------------------------------------------------------------------------
# --hold: the holding endpoints, which the app uses for a team game's creating team and
# which answered HTTP 403 to every POST /hold/multiple/create while merchant/pay/* worked
# with the same token. Three things it settles that the docs do not say:
#
#   * whether /hold/create and /hold/multiple/create are forbidden alike. If only the
#     multiple one is, the endpoint is the problem and not the credentials.
#   * whether capture and cancel take the multi-hold's parent_id or each item's hold_id
#     (scenario c captures by item id, then falls back to the parent).
#   * whether a captured hold can be given back, and with which id (merchant/pay/reverse
#     with the capture's success_trans_id is the only documented candidate).
#
# Every step prints the HTTP status as well as the ATMOS code, because the two say
# different things: an HTTP 403 never reaches ATMOS at all, so it carries no result code,
# and a result code of STPIMS-ERR-037 under HTTP 200 is ATMOS answering about our store.
#
# --all is --hold plus the ordinary payment, on one card binding — the whole surface the
# app uses, in one run, with nothing left held or taken at the end.
#
# --bisect: WHY pay/apply answers STPIMS-ERR-041 in the app but OK here.
#
# The app's apply request is this script's apply request, field for field: the same single
# /merchant/pay/apply (an ordinary booking pays one recipient — the owner — so it never
# takes the bulk path), the same transaction_id as a number, the same otp "111111", sent
# straight after pre-apply. What differs is what pay/CREATE named three calls earlier:
#
#   * store_id    — the app names the OWNER's ATMOS recipient there (ATMOS_RECIPIENT_FIELD=
#                   store_id, the default) while this script names our own ATMOS_STORE_ID.
#                   With ATMOS_RECIPIENT_FIELD=terminal_id the app names ours, like here.
#   * account     — the app sends the payment's UUID (36 characters); this script sends
#                   "smoke-<epoch>". The ATMOS parameter table says account "takes a numeric
#                   value" while every example in the docs is text.
#   * ofd_items   — the app sends fiscal lines when ATMOS_OFD_CODE_RENTAL and
#                   ATMOS_OFD_CODE_SERVICE are both set; this script never does.
#
# --bisect binds the card ONCE and then takes the payment several times over, adding one of
# those differences each time and reversing every one in full. The first variant whose
# pay/apply fails names the cause, and the backend log line "ATMOS single payment:" says
# which shape that server actually sends.
#
#     ./atmos-smoke.sh --bisect
#     ATMOS_SMOKE_RECIPIENT_ID=<an owner's atmos_recipient_id> ./atmos-smoke.sh --bisect
#
# The recipient variant is skipped unless ATMOS_SMOKE_RECIPIENT_ID is set (take it from
# owner_legal_details.atmos_recipient_id — the one the failing booking's owner has), and the
# receipts variant unless both OFD codes are configured in the backend.
# ---------------------------------------------------------------------------------------

mode=single
for arg in "$@"; do
  case "$arg" in
    --bisect) mode=bisect ;;
    --hold)   mode=hold ;;
    --all)    mode=all ;;
    -h|--help)
      cat <<'USAGE'
atmos-smoke.sh — real ATMOS calls from this server.

  ./atmos-smoke.sh            one payment: pay/create, pre-apply, apply, get, reverse
  ./atmos-smoke.sh --hold     the holds:
                                a) hold -> apply -> get -> capture in full -> reverse
                                b) hold -> apply -> cancel (send_cancel_sms=false) -> get
                                c) multiple hold, 2 items -> apply -> capture each -> reverse
                                d) hold -> apply -> capture PART of it -> get -> reverse
  ./atmos-smoke.sh --all      the payment and the holds, on one card binding
  ./atmos-smoke.sh --bisect   one payment per difference from what the app sends

Every step prints METHOD, PATH, the HTTP status, ATMOS's result code and OK/FAILED.

Settings (all optional):
  ATMOS_SMOKE_AMOUNT=50000           how much to pay / hold, in tiyin (500 so'm)
  ATMOS_SMOKE_HOLD_MINUTES=10        how long to hold it for
  ATMOS_SMOKE_RECIPIENT_ID=<id>      --bisect: also try an owner's recipient id
  ATMOS_SMOKE_ALLOW_PRODUCTION=yes   allow a run against ATMOS production (real money)

It never prints a card number, an expiry, an SMS code, a card token, an access token, a
credential, a response body or a full phone number. It runs the calls inside the backend
container, where the credentials already are; you need no .env access.
USAGE
      exit 0 ;;
    *) printf 'Unknown option: %s (try --hold, --all, --bisect or --help)\n' "$arg" >&2; exit 2 ;;
  esac
done

say() { printf '%s\n' "$*"; }
die() { printf '\n%s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------------------
# Before anything: is there a backend, is it talking to ATMOS, and is it the sandbox?
# ---------------------------------------------------------------------------------------

command -v docker >/dev/null 2>&1 || die "docker is not on this machine. This script runs on the SERVER."
docker compose ps --status running --services 2>/dev/null | grep -qx backend \
  || die "The backend container is not running. Start it first: docker compose up -d backend"

profiles=$(docker compose exec -T backend printenv SPRING_PROFILES_ACTIVE 2>/dev/null | tr -d '\r' || true)
case ",$profiles," in
  *,atmos,*) ;;
  *) die "The backend is running with SPRING_PROFILES_ACTIVE=${profiles:-<unset>}, so no ATMOS client exists.
Set SPRING_PROFILES_ACTIVE=staging,atmos in .env and 'docker compose up -d backend' first (step 6.4)." ;;
esac

environment=$(docker compose exec -T backend printenv ATMOS_ENVIRONMENT 2>/dev/null | tr -d '\r' || true)
environment=${environment:-sandbox}
if [ "$environment" = "production" ] && [ "${ATMOS_SMOKE_ALLOW_PRODUCTION:-}" != "yes" ]; then
  die "This backend is on ATMOS production. A real card would really be charged.
Run it as: ATMOS_SMOKE_ALLOW_PRODUCTION=yes $0"
fi

amount=${ATMOS_SMOKE_AMOUNT:-50000}
case "$amount" in ''|*[!0-9]*) die "ATMOS_SMOKE_AMOUNT must be a whole number of tiyin." ;; esac

hold_minutes=${ATMOS_SMOKE_HOLD_MINUTES:-10}
case "$hold_minutes" in ''|*[!0-9]*) die "ATMOS_SMOKE_HOLD_MINUTES must be a whole number of minutes." ;; esac

# What the backend itself would send, printed so the run can be read against the app's.
recipient_field=$(docker compose exec -T backend printenv ATMOS_RECIPIENT_FIELD 2>/dev/null | tr -d '\r' || true)
recipient_field=${recipient_field:-store_id}
hold_cap=$(docker compose exec -T backend printenv ATMOS_HOLD_MAX_MINUTES 2>/dev/null | tr -d '\r' || true)
if [ -z "$hold_cap" ]; then hold_cap="10080 (the default)"; fi

say "ATMOS smoke test"
say "  environment : $environment"
say "  profiles    : $profiles"
say "  amount      : $amount tiyin ($((amount / 100)) so'm), reversed or released at the end"
say "  app sends   : ATMOS_RECIPIENT_FIELD=$recipient_field, ATMOS_HOLD_MAX_MINUTES=$hold_cap"
case "$mode" in
  bisect) say "  mode        : --bisect, one payment per difference from the app's request" ;;
  hold)   say "  mode        : --hold, $hold_minutes-minute holds of $amount tiyin" ;;
  all)    say "  mode        : --all, the payment and the holds on one card binding" ;;
esac
say ""

# ---------------------------------------------------------------------------------------
# The two halves of the run, as they are executed inside the container.
#
# Both read their secrets from standard input, never from an argument, and print nothing
# but a step line each. Neither uses jq (the image has none): the few fields that are read
# are pulled out with sed, which is enough for scalars and is why this is a smoke test and
# not a client. The container's shell is sh, not bash — no arrays, no [[ ]], no local.
# ---------------------------------------------------------------------------------------

COMMON=$(cat <<'EOS'
base=${ATMOS_BASE_URL%/}

# The first "name": value of a flat JSON scalar, quoted or not.
field() {
  printf '%s' "$2" | tr ',' '\n' \
    | sed -n 's/.*"'"$1"'"[[:space:]]*:[[:space:]]*"\{0,1\}\([^",}]*\)"\{0,1\}.*/\1/p' | head -1
}

# EVERY value of a repeated flat field, one per line (the hold_ids of a multiple hold).
fields() {
  printf '%s' "$2" | tr ',' '\n' \
    | sed -n 's/.*"'"$1"'"[[:space:]]*:[[:space:]]*"\{0,1\}\([^",}]*\)"\{0,1\}.*/\1/p'
}

# ATMOS's own result code, out of "result":{"code":"..."}.
code_of() {
  printf '%s' "$1" | sed -n 's/.*"result"[^{]*{[^}]*"code"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1
}

description_of() {
  printf '%s' "$1" | sed -n 's/.*"result"[^{]*{[^}]*"description"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1
}

# A phone number never leaves the container whole, not even the one ATMOS has masked.
mask_phone() {
  printf '%s' "$1" | sed -n 's/.*\(..\)$/*********\1/p'
}

# step <label> <method> <path> <http> <code> <verdict> [note]
step() {
  printf '  %-22s %-6s %-30s %-4s %-18s %-6s %s\n' "$1" "$2" "$3" "${4:-000}" "${5:--}" "$6" "${7:-}"
}

header() {
  printf '  %-22s %-6s %-30s %-4s %-18s %-6s %s\n' STEP METHOD PATH HTTP 'ATMOS CODE' '' ''
}

# call <METHOD> <path> [json]
# Sets method, path, http, body, code, desc. Returns 1 only when there was no answer at
# all; an HTTP error or an ATMOS refusal is an answer and is left to the caller to judge.
call() {
  method=$1
  path=$2
  if [ "$#" -ge 3 ]; then
    raw=$(curl -sS --max-time 40 -X "$method" "$base$path" \
      -H "Authorization: Bearer $access" \
      -H 'Content-Type: application/json' \
      -H 'Accept: application/json' \
      -w '%{http_code}' \
      --data-binary "$3") || raw=''
  else
    raw=$(curl -sS --max-time 40 -X "$method" "$base$path" \
      -H "Authorization: Bearer $access" \
      -H 'Content-Type: application/json' \
      -H 'Accept: application/json' \
      -w '%{http_code}') || raw=''
  fi
  if [ -z "$raw" ]; then
    http=000
    body=''
    code=''
    desc='no answer'
    return 1
  fi
  http=$(printf '%s' "$raw" | tail -c 3)
  body=${raw%???}
  code=$(code_of "$body")
  desc=$(description_of "$body")
  return 0
}

# expect <label> [note] — OK when the HTTP status is 2xx AND the result code is OK.
expect() {
  case "$http" in
    2??) ;;
    *) step "$1" "$method" "$path" "$http" "${code:--}" FAILED "${desc:-HTTP $http, no ATMOS answer}"
       return 1 ;;
  esac
  if [ "$code" = "OK" ]; then
    step "$1" "$method" "$path" "$http" "$code" OK "${2:-}"
    return 0
  fi
  step "$1" "$method" "$path" "$http" "${code:--}" FAILED "$desc"
  return 1
}

# told <label> <verdict> [note] — a step whose code is the answer, not a pass or a fail.
told() {
  step "$1" "$method" "$path" "$http" "${code:--}" "$2" "${3:-$desc}"
}

get_token() {
  raw=$(curl -sS --max-time 40 -X POST "$base/token?grant_type=client_credentials" \
    -u "$ATMOS_CONSUMER_KEY:$ATMOS_CONSUMER_SECRET" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    -w '%{http_code}' \
    -d 'grant_type=client_credentials') || raw=''
  if [ -z "$raw" ]; then
    step token POST /token 000 - FAILED "no answer"
    return 1
  fi
  http=$(printf '%s' "$raw" | tail -c 3)
  body=${raw%???}
  access=$(field access_token "$body")
  if [ -z "$access" ]; then
    step token POST /token "$http" - FAILED "no access_token in the answer"
    return 1
  fi
  step token POST /token "$http" - OK "valid $(field expires_in "$body") s"
}
EOS
)

BIND=$(cat <<'EOS'
read -r card
read -r expiry
# ATMOS wants YYmm; the operator types MM/YY, as it is on the card.
mm=$(printf '%s' "$expiry" | tr -cd '0-9' | cut -c1-2)
yy=$(printf '%s' "$expiry" | tr -cd '0-9' | cut -c3-4)
card=$(printf '%s' "$card" | tr -cd '0-9')

header
get_token || exit 1

call POST /partner/bind-card/init "{\"card_number\":\"$card\",\"expiry\":\"$yy$mm\"}" || true
expect bind-card/init "SMS sent to $(mask_phone "$(field phone "$body")")" || exit 1
printf 'BINDING %s\n' "$(field transaction_id "$body")"
EOS
)

PAY=$(cat <<'EOS'
read -r binding
read -r otp
read -r amount
read -r mode
read -r recipient_id
read -r hold_minutes

# The payment examples send ids as numbers; quote one that is not. The HOLD examples quote
# store_id every time, and so does the app (a store id written "0001" must not become 1),
# so hold bodies use store_q and payment bodies use store.
id_json() {
  case "$1" in '' | *[!0-9]*) printf '"%s"' "$1" ;; *) printf '%s' "$1" ;; esac
}
store=$(id_json "$ATMOS_STORE_ID")
store_q="\"$ATMOS_STORE_ID\""

header
get_token || exit 1

# ---- the card. Its token is used below and never printed or written anywhere. ----
call POST /partner/bind-card/confirm "{\"transaction_id\":$binding,\"otp\":\"$otp\"}" || true
expect bind-card/confirm "card bound" || exit 1
card_token=$(field card_token "$body")
card_id=$(field card_id "$body")

failures=''
note_failure() { failures="$failures $1"; }
blank() { printf '\n'; }
account_of() { printf 'smoke-%s-%s' "$1" "$(date +%s)"; }

# ---------------------------------------------------------------------------------------
# One whole payment: create, pre-apply, apply, get, reverse.
#
# pay_once <label> <create-store-json> <terminal-id|''> <account> <ofd yes|no>
#
# Everything the app can differ by is an argument, so one run can take the payment several
# times over with one difference each (--bisect). Whatever happens to the apply it asks
# pay/get afterwards — an apply that answers "connection problem" for money ATMOS has
# already taken is the whole reason this exists — and reverses anything that did move, so
# no variant can leave money behind.
# ---------------------------------------------------------------------------------------
pay_once() {
  label=$1
  create_store=$2
  terminal=$3
  account=$4
  ofd=$5

  create="{\"store_id\":$create_store,\"account\":\"$account\",\"amount\":$amount"
  [ -n "$terminal" ] && create="$create,\"terminal_id\":\"$terminal\""
  if [ "$ofd" = yes ]; then
    create="$create,\"ofd_items\":[{\"ofd_code\":\"$ATMOS_OFD_CODE_RENTAL\",\"name\":\"Pitch rental\",\"amount\":$amount}]"
  fi
  create="$create}"

  call POST /merchant/pay/create "$create" || true
  txn=$(field transaction_id "$body")
  if ! expect "${label}pay/create" "transaction $txn" || [ -z "$txn" ]; then
    return 1
  fi

  call POST /merchant/pay/pre-apply \
    "{\"store_id\":$store,\"transaction_id\":$txn,\"card_token\":\"$card_token\"}" || true
  expect "${label}pay/pre-apply" || return 1

  # A card token needs no SMS code of its own: the docs' fixed 111111 is what confirms it.
  call POST /merchant/pay/apply "{\"store_id\":$store,\"transaction_id\":$txn,\"otp\":\"111111\"}" || true
  apply_code=$code
  success=$(field success_trans_id "$body")
  expect "${label}pay/apply" "confirmed=$(field confirmed "$body"), success_trans_id $success" || true

  # ---- what ATMOS says about it, the way the reconcile job asks ----
  call POST /merchant/pay/get "{\"store_id\":$store,\"transaction_id\":$txn}" || true
  confirmed=$(field confirmed "$body")
  [ -n "$success" ] || success=$(field success_trans_id "$body")
  told "${label}pay/get" OK "confirmed=${confirmed:-?}, amount $(field amount "$body") tiyin"

  # ---- and back in full. There is no partial reverse anywhere in this application. ----
  reverse "${label}pay/reverse" "$success" "$amount tiyin back" || return 1

  [ "$apply_code" = "OK" ] || return 2
  return 0
}

# reverse <label> <success_trans_id> <note>
reverse() {
  if [ -z "$2" ] || [ "$2" = "null" ]; then
    step "$1" '-' '-' '-' '-' OK "nothing to give back"
    return 0
  fi
  call POST /merchant/pay/reverse "{\"transaction_id\":$2,\"reason\":\"ATMOS smoke test\"}" || true
  if expect "$1" "$3"; then
    return 0
  fi
  printf '      ^ CHECK THE MONEY BY HAND: transaction %s was not given back\n' "$2"
  return 1
}

uuid_account() {
  # The shape the app sends: its payment id, a UUID v7. Any 36-character one will do here.
  printf '0190c3a1-0000-7000-8000-%012d' "$(date +%s)"
}

# ---------------------------------------------------------------------------------------
# The holds. Each scenario leaves nothing held and nothing taken.
# ---------------------------------------------------------------------------------------

# hold_create <account> <amount> -> sets hold_id
hold_create() {
  call POST /hold/create "{\"card_token\":\"$card_token\",\"store_id\":$store_q,\"account\":\"$1\",\"payment_details\":\"MiniPolya smoke\",\"amount\":\"$2\",\"duration\":\"$hold_minutes\"}" || true
  hold_id=$(field hold_id "$body")
  expect hold/create "hold_id $hold_id" || return 1
  [ -n "$hold_id" ] || return 1
  return 0
}

hold_apply() {
  call PUT "/hold/apply/$1" '{"otp":"111111"}' || true
  expect hold/apply "hold_till $(field hold_till "$body")" || return 1
  return 0
}

hold_get() {
  call GET "/hold/$1" || true
  case "$code" in
    OK) told hold/get OK "confirmed=$(field confirmed "$body"), amount $(field amount "$body") tiyin" ;;
    STPIMS-ERR-142) told hold/get OK "created, not yet applied" ;;
    STPIMS-ERR-144|STPIMS-ERR-145) told hold/get OK "gone: $desc" ;;
    *) told hold/get FAILED; note_failure hold/get ;;
  esac
}

# hold_capture <id> [amount] -> sets success
hold_capture() {
  if [ "$#" -ge 2 ]; then
    call POST "/hold/$1" "{\"amount\":$2}" || true
  else
    call POST "/hold/$1" '{}' || true
  fi
  success=$(field success_trans_id "$body")
  expect hold/capture "success_trans_id $success, amount $(field amount "$body") tiyin" || return 1
  return 0
}

# hold_cancel <id> <send_cancel_sms true|false>
hold_cancel() {
  call DELETE "/hold/$1" "{\"send_cancel_sms\":$2}" || true
  # The docs' own sample answer to the cancellation is STPIMS-ERR-145 ("холдирование
  # отменено"), so that code IS the confirmation here — as it now is in the app.
  case "$code" in
    OK|STPIMS-ERR-145|STPIMS-ERR-144) told hold/cancel OK "released (send_cancel_sms=$2)"; return 0 ;;
    *) told hold/cancel FAILED; return 1 ;;
  esac
}

# a) a hold, applied, read, captured in full, given back
hold_single() {
  blank
  printf '  a) one hold of %s tiyin for %s minutes, captured in full and reversed\n' "$amount" "$hold_minutes"
  hold_create "$(account_of a)" "$amount" || { note_failure hold/create; return 1; }
  hold_apply "$hold_id" || { note_failure hold/apply; hold_cancel "$hold_id" false || true; return 1; }
  hold_get "$hold_id"
  if hold_capture "$hold_id"; then
    reverse hold/reverse "$success" "$amount tiyin back" \
      || printf '      ^ ATMOS documents no reverse for a captured hold; this is the open question.\n'
  else
    note_failure hold/capture
    hold_cancel "$hold_id" false || true
    return 1
  fi
}

# b) a hold, applied, then cancelled — and read back to see that it is gone
hold_cancelled() {
  blank
  printf '  b) one hold, cancelled with send_cancel_sms=false, then read back\n'
  hold_create "$(account_of b)" "$amount" || { note_failure hold/create; return 1; }
  hold_apply "$hold_id" || { note_failure hold/apply; hold_cancel "$hold_id" false || true; return 1; }
  hold_cancel "$hold_id" false || { note_failure hold/cancel; return 1; }
  hold_get "$hold_id"
}

# c) a multiple hold of two items on our own store, each item captured by its own hold_id
hold_multiple() {
  blank
  printf '  c) a multiple hold, 2 items of %s tiyin on our own store, captured item by item\n' "$amount"
  account=$(account_of c)
  item="{\"store_id\":$store_q,\"account\":\"$account\",\"payment_details\":\"MiniPolya smoke\",\"amount\":\"$amount\"}"
  call POST /hold/multiple/create \
    "{\"card_token\":\"$card_token\",\"duration\":\"$hold_minutes\",\"items\":[$item,$item]}" || true
  parent=$(field parent_id "$body")
  item_ids=$(fields hold_id "$body")
  if ! expect hold/multiple/create "parent_id $parent, items $(printf '%s' "$item_ids" | tr '\n' ' ')"; then
    note_failure hold/multiple/create
    printf '      ^ THIS is the call that answers HTTP 403. The backend log now carries the body;\n'
    printf '        `docker compose logs backend | grep "ATMOS refused"` has the gateway fault code.\n'
    return 1
  fi

  call PUT "/hold/multiple/apply/$parent" '{"otp":"111111"}' || true
  if ! expect hold/multiple/apply "items $(printf '%s' "$(fields hold_till "$body")" | tr '\n' ' ')"; then
    note_failure hold/multiple/apply
    hold_cancel "$parent" false || true
    return 1
  fi

  # Which id the follow-up calls take is undocumented: try each item's own hold_id, and
  # fall back to the parent id if ATMOS does not know them.
  captured=''
  for one in $item_ids; do
    if hold_capture "$one"; then
      captured="$captured $success"
    else
      printf '      ^ item %s could not be captured by its own hold_id\n' "$one"
    fi
  done
  if [ -z "$captured" ]; then
    printf '      falling back to the parent id, which is what the app sends today\n'
    if hold_capture "$parent"; then
      captured=$success
    else
      note_failure hold/multiple/capture
      hold_cancel "$parent" false || true
      return 1
    fi
  fi
  for one in $captured; do
    reverse hold/reverse "$one" "$amount tiyin back" || true
  done
  hold_get "$parent"
}

# d) a partial capture: the docs allow an amount no larger than the one held
hold_partial() {
  blank
  part=$((amount / 2))
  printf '  d) one hold of %s tiyin, capturing only %s of it\n' "$amount" "$part"
  hold_create "$(account_of d)" "$amount" || { note_failure hold/create; return 1; }
  hold_apply "$hold_id" || { note_failure hold/apply; hold_cancel "$hold_id" false || true; return 1; }
  if hold_capture "$hold_id" "$part"; then
    hold_get "$hold_id"
    reverse hold/reverse "$success" "$part tiyin back" || true
  else
    printf '      ^ partial capture refused; the app never sends an amount, so this is information only\n'
    hold_cancel "$hold_id" false || true
  fi
}

hold_suite() {
  hold_single || true
  hold_cancelled || true
  hold_multiple || true
  hold_partial || true
}

# ---------------------------------------------------------------------------------------
# What this run does.
# ---------------------------------------------------------------------------------------

case "$mode" in
  bisect)
    blank
    printf '  1. baseline: our own store_id, a short account, no ofd_items\n'
    pay_once "  " "$store" '' "smoke-$(date +%s)" no || note_failure script

    blank
    printf '  2. the account the app sends: a 36-character UUID\n'
    pay_once "  " "$store" '' "$(uuid_account)" no || note_failure account

    if [ -n "$recipient_id" ]; then
      blank
      printf '  3. and pay/create naming the OWNER recipient in store_id, as the app does\n'
      pay_once "  " "$(id_json "$recipient_id")" '' "$(uuid_account)" no || note_failure recipient-store_id

      blank
      printf '  4. and that recipient in terminal_id instead, our own store in store_id\n'
      pay_once "  " "$store" "$recipient_id" "$(uuid_account)" no || note_failure recipient-terminal_id
    else
      blank
      printf '  3. skipped: set ATMOS_SMOKE_RECIPIENT_ID to try an owner recipient id\n'
    fi

    if [ -n "${ATMOS_OFD_CODE_RENTAL:-}" ] && [ -n "${ATMOS_OFD_CODE_SERVICE:-}" ]; then
      blank
      printf '  5. and ofd_items, which the app sends when both OFD codes are set\n'
      pay_once "  " "$store" '' "$(uuid_account)" yes || note_failure ofd_items
    else
      blank
      printf '  5. skipped: no OFD codes are configured, so the app sends no ofd_items either\n'
    fi
    ;;
  hold)
    hold_suite
    ;;
  all)
    blank
    printf '  the ordinary payment\n'
    pay_once "  " "$store" '' "smoke-$(date +%s)" no || note_failure payment
    hold_suite
    ;;
  *)
    pay_once '' "$store" '' "smoke-$(date +%s)" no || note_failure payment
    ;;
esac

# ---- tidy up: the test card does not stay bound ----
blank
call POST /partner/remove-card "{\"id\":$card_id,\"token\":\"$card_token\"}" || true
expect remove-card || printf '      ^ the card stays bound; what it was used for above is unaffected\n'

if [ -n "$failures" ]; then
  printf '\nFAILED:%s\n' "$failures"
  exit 1
fi
EOS
)

# ---------------------------------------------------------------------------------------
# Half one: the card, and the SMS code it makes ATMOS send.
# ---------------------------------------------------------------------------------------

say "The card is read without echo and goes straight into the container. Nothing is stored."
read -rsp "  Card number : " card; echo
read -rsp "  Expiry MM/YY: " expiry; echo
say ""

[ -n "$card" ] && [ -n "$expiry" ] || die "A card number and an expiry are needed."

set +e
out=$(printf '%s\n%s\n' "$card" "$expiry" \
      | docker compose exec -T backend sh -c "$COMMON
$BIND")
status=$?
set -e
unset card expiry

# Print what it said whether it worked or not: the step line is the whole point.
printf '%s\n' "$out" | grep -v '^BINDING ' || true
binding=$(printf '%s\n' "$out" | sed -n 's/^BINDING //p' | head -1)
if [ "$status" -ne 0 ] || [ -z "$binding" ]; then
  die "FAILED. The card was not bound, so nothing was charged."
fi

say ""
read -rsp "  SMS code    : " otp; echo
say ""
[ -n "$otp" ] || die "The SMS code is needed to confirm the binding."

# ---------------------------------------------------------------------------------------
# Half two: everything that touches the card token, in one call inside the container.
# ---------------------------------------------------------------------------------------

if printf '%s\n%s\n%s\n%s\n%s\n%s\n' \
     "$binding" "$otp" "$amount" "$mode" "${ATMOS_SMOKE_RECIPIENT_ID:-}" "$hold_minutes" \
   | docker compose exec -T backend sh -c "$COMMON
$PAY"; then
  unset otp
  say ""
  case "$mode" in
    bisect) say "ALL VARIANTS OK. Every payment was taken and given back in full." ;;
    hold)   say "ALL HOLD STEPS OK. Nothing is still held and nothing is still taken." ;;
    all)    say "ALL STEPS OK. The payment and every hold were given back or released." ;;
    *)      say "ALL STEPS OK. The payment was taken and given back in full." ;;
  esac
else
  unset otp
  say ""
  case "$mode" in
    bisect)
      say "A variant FAILED. The line above names the difference that breaks pay/apply;"
      say "the backend log line \"ATMOS single payment:\" says which shape this server sends." ;;
    hold|all)
      say "A step FAILED. The line above has its method, path, HTTP status and ATMOS code."
      say "For an HTTP 403 or 401 there is no ATMOS code: the gateway refused the call before"
      say "ATMOS saw it, and the reason is in the body, which the backend now logs:"
      say "    docker compose logs backend | grep 'ATMOS refused'"
      say "Check the ATMOS cabinet for anything still held before running this again." ;;
    *)
      say "FAILED. The step above says which one and what ATMOS answered."
      say "Nothing is retried automatically; if pay/apply or pay/reverse is the one that failed,"
      say "check the transaction in the ATMOS cabinet before running this again." ;;
  esac
  exit 1
fi
