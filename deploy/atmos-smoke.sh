#!/usr/bin/env bash
#
# One real ATMOS transaction, end to end, from the SERVER.
#
#     cd ~/minipolya/minipolyaBackend/deploy
#     ./atmos-smoke.sh
#
# It asks for a card, the SMS code ATMOS sends you, and then: buys an access token, binds
# the card, takes a small payment, asks ATMOS what became of it, gives it back in full, and
# unbinds the card. One line per step with ATMOS's own result code and OK or FAILED.
#
# Why a script and not a test: ATMOS answers only calls from an IP address it has allowed,
# which is this server. Nothing of this can be run from a laptop, and the sandbox is the
# only place the real answers — a real SMS, a real decline, a real commission — can be seen.
#
# WHAT IT NEVER PRINTS: the card number, the expiry, the SMS code, the card token, the
# access token, the consumer key or secret, or any response body. The card and the code are
# read without echo and handed to the container on standard input, so they never appear in
# `ps`, in your shell history or in a file. The card token never leaves the container at
# all: everything that needs it happens in one call inside it.
#
# WHERE IT RUNS: the curl calls run INSIDE the backend container, which is where the ATMOS
# credentials already are (docker-compose.yml passes them). The host never sees them, and
# you need no .env access to run this.
#
# WHAT IT COSTS: one payment of ATMOS_SMOKE_AMOUNT tiyin (500 so'm by default), reversed in
# full at the last step. The money goes to ATMOS_STORE_ID — our own store, never a stadium
# owner — so nothing lands in anybody else's account even if the reverse fails.
#
#     ATMOS_SMOKE_AMOUNT=100000 ./atmos-smoke.sh     # 1 000 so'm instead of 500
#
# PRODUCTION: it refuses to run when the backend says ATMOS_ENVIRONMENT=production, because
# that would take real money from a real card. If you really mean to (one real payment,
# reversed), run it as:
#
#     ATMOS_SMOKE_ALLOW_PRODUCTION=yes ./atmos-smoke.sh
#
# Test cards for the sandbox are in the ATMOS docs ("Песочница"), not here.

set -euo pipefail

cd "$(dirname "$0")"

# ---------------------------------------------------------------------------------------
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

bisect=no
for arg in "$@"; do
  case "$arg" in
    --bisect) bisect=yes ;;
    -h|--help) sed -n '2,38p;45,75p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) printf 'Unknown option: %s (try --bisect or --help)\n' "$arg" >&2; exit 2 ;;
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

# What the backend itself would send, printed so the run can be read against the app's.
recipient_field=$(docker compose exec -T backend printenv ATMOS_RECIPIENT_FIELD 2>/dev/null | tr -d '\r' || true)
recipient_field=${recipient_field:-store_id}

say "ATMOS smoke test${bisect:+}"
say "  environment : $environment"
say "  profiles    : $profiles"
say "  amount      : $amount tiyin ($((amount / 100)) so'm), reversed at the end"
say "  app sends   : ATMOS_RECIPIENT_FIELD=$recipient_field"
if [ "$bisect" = yes ]; then
  say "  mode        : --bisect, one payment per difference from the app's request"
fi
say ""

# ---------------------------------------------------------------------------------------
# The two halves of the run, as they are executed inside the container.
#
# Both read their secrets from standard input, never from an argument, and print nothing
# but a step line each. Neither uses jq (the image has none): the few fields that are read
# are pulled out with sed, which is enough for scalars and is why this is a smoke test and
# not a client.
# ---------------------------------------------------------------------------------------

COMMON=$(cat <<'EOS'
base=${ATMOS_BASE_URL%/}

# The first "name": value of a flat JSON scalar, quoted or not.
field() {
  printf '%s' "$2" | tr ',' '\n' \
    | sed -n 's/.*"'"$1"'"[[:space:]]*:[[:space:]]*"\{0,1\}\([^",}]*\)"\{0,1\}.*/\1/p' | head -1
}

# ATMOS's own result code, out of "result":{"code":"..."}.
code_of() {
  printf '%s' "$1" | sed -n 's/.*"result"[^{]*{[^}]*"code"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1
}

description_of() {
  printf '%s' "$1" | sed -n 's/.*"result"[^{]*{[^}]*"description"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1
}

# step <name> <code> <ok|failed> [note]
step() {
  printf '  %-22s %-18s %s%s\n' "$1" "${2:--}" "$3" "${4:+   $4}"
}

post() { # post <path> <json> ; answers the body on stdout
  curl -sS --max-time 40 -X POST "$base$1" \
    -H "Authorization: Bearer $access" \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json' \
    --data-binary "$2"
}

get_token() {
  body=$(curl -sS --max-time 40 -X POST "$base/token?grant_type=client_credentials" \
    -u "$ATMOS_CONSUMER_KEY:$ATMOS_CONSUMER_SECRET" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    -d 'grant_type=client_credentials') || { step token - FAILED "no answer"; return 1; }
  access=$(field access_token "$body")
  if [ -z "$access" ]; then
    step token - FAILED "no access_token in the answer"
    return 1
  fi
  step token - OK "valid $(field expires_in "$body") s"
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

get_token || exit 1

body=$(post /partner/bind-card/init "{\"card_number\":\"$card\",\"expiry\":\"$yy$mm\"}") \
  || { step bind-card/init - FAILED "no answer"; exit 1; }
code=$(code_of "$body")
if [ "$code" != "OK" ]; then
  step bind-card/init "$code" FAILED "$(description_of "$body")"
  exit 1
fi
binding=$(field transaction_id "$body")
step bind-card/init "$code" OK "SMS sent to $(field phone "$body")"
printf 'BINDING %s\n' "$binding"
EOS
)

PAY=$(cat <<'EOS'
read -r binding
read -r otp
read -r amount
read -r mode
read -r recipient_id

# The examples send ids as numbers; quote one that is not.
id_json() {
  case "$1" in '' | *[!0-9]*) printf '"%s"' "$1" ;; *) printf '%s' "$1" ;; esac
}
store=$(id_json "$ATMOS_STORE_ID")

get_token || exit 1

# ---- the card. Its token is used below and never printed or written anywhere. ----
body=$(post /partner/bind-card/confirm "{\"transaction_id\":$binding,\"otp\":\"$otp\"}") \
  || { step bind-card/confirm - FAILED "no answer"; exit 1; }
code=$(code_of "$body")
if [ "$code" != "OK" ]; then
  step bind-card/confirm "$code" FAILED "$(description_of "$body")"
  exit 1
fi
card_token=$(field card_token "$body")
card_id=$(field card_id "$body")
step bind-card/confirm "$code" OK "card bound"

# ---- one whole payment: create, pre-apply, apply, get, reverse ----
#
# pay_once <label> <create-store-json> <terminal-id|''> <account> <ofd yes|no>
#
# Everything the app can differ by is an argument, so one run can take the payment several
# times over with one difference each (--bisect). Whatever happens to the apply it asks
# pay/get afterwards — an apply that answers "connection problem" for money ATMOS has
# already taken is the whole reason this exists — and reverses anything that did move, so
# no variant can leave money behind.
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

  body=$(post /merchant/pay/create "$create") || { step "${label}pay/create" - FAILED "no answer"; return 1; }
  code=$(code_of "$body")
  txn=$(field transaction_id "$body")
  if [ "$code" != "OK" ] || [ -z "$txn" ]; then
    step "${label}pay/create" "$code" FAILED "$(description_of "$body")"
    return 1
  fi
  step "${label}pay/create" "$code" OK "transaction $txn"

  body=$(post /merchant/pay/pre-apply \
    "{\"store_id\":$store,\"transaction_id\":$txn,\"card_token\":\"$card_token\"}") \
    || { step "${label}pay/pre-apply" - FAILED "no answer"; return 1; }
  code=$(code_of "$body")
  if [ "$code" != "OK" ]; then
    step "${label}pay/pre-apply" "$code" FAILED "$(description_of "$body")"
    return 1
  fi
  step "${label}pay/pre-apply" "$code" OK

  # A card token needs no SMS code of its own: the docs' fixed 111111 is what confirms it.
  body=$(post /merchant/pay/apply "{\"store_id\":$store,\"transaction_id\":$txn,\"otp\":\"111111\"}") \
    || { step "${label}pay/apply" - FAILED "no answer, the payment may still have gone through"; body=''; }
  apply_code=$(code_of "$body")
  success=$(field success_trans_id "$body")
  if [ "$apply_code" = "OK" ]; then
    step "${label}pay/apply" "$apply_code" OK "confirmed=$(field confirmed "$body"), success_trans_id $success"
  else
    step "${label}pay/apply" "${apply_code:--}" FAILED "$(description_of "$body")"
  fi

  # ---- what ATMOS says about it, the way the reconcile job asks ----
  body=$(post /merchant/pay/get "{\"store_id\":$store,\"transaction_id\":$txn}") || body=''
  confirmed=$(field confirmed "$body")
  [ -n "$success" ] || success=$(field success_trans_id "$body")
  step "${label}pay/get" "$(code_of "$body")" OK "confirmed=${confirmed:-?}, amount $(field amount "$body") tiyin"

  # ---- and back in full. There is no partial reverse anywhere in this application. ----
  if [ -z "$success" ] || [ "$success" = "null" ]; then
    step "${label}pay/reverse" - OK "nothing to give back"
  else
    body=$(post /merchant/pay/reverse "{\"transaction_id\":$success,\"reason\":\"ATMOS smoke test\"}") \
      || { step "${label}pay/reverse" - FAILED "no answer: CHECK THE MONEY BY HAND"; return 1; }
    code=$(code_of "$body")
    if [ "$code" = "OK" ]; then
      step "${label}pay/reverse" "$code" OK "$amount tiyin back"
    else
      step "${label}pay/reverse" "$code" FAILED "$(description_of "$body") - CHECK THE MONEY BY HAND"
      return 1
    fi
  fi

  [ "$apply_code" = "OK" ] || return 2
  return 0
}

uuid_account() {
  # The shape the app sends: its payment id, a UUID v7. Any 36-character one will do here.
  printf '0190c3a1-0000-7000-8000-%012d' "$(date +%s)"
}

failures=''
note_failure() { failures="$failures $1"; }

if [ "$mode" = bisect ]; then
  say_blank() { printf '\n'; }

  say_blank
  printf '  1. baseline: our own store_id, a short account, no ofd_items\n'
  pay_once "  " "$store" '' "smoke-$(date +%s)" no || note_failure script

  say_blank
  printf '  2. the account the app sends: a 36-character UUID\n'
  pay_once "  " "$store" '' "$(uuid_account)" no || note_failure account

  if [ -n "$recipient_id" ]; then
    say_blank
    printf '  3. and pay/create naming the OWNER recipient in store_id, as the app does\n'
    pay_once "  " "$(id_json "$recipient_id")" '' "$(uuid_account)" no || note_failure recipient-store_id

    say_blank
    printf '  4. and that recipient in terminal_id instead, our own store in store_id\n'
    pay_once "  " "$store" "$recipient_id" "$(uuid_account)" no || note_failure recipient-terminal_id
  else
    say_blank
    printf '  3. skipped: set ATMOS_SMOKE_RECIPIENT_ID to try an owner recipient id\n'
  fi

  if [ -n "${ATMOS_OFD_CODE_RENTAL:-}" ] && [ -n "${ATMOS_OFD_CODE_SERVICE:-}" ]; then
    say_blank
    printf '  5. and ofd_items, which the app sends when both OFD codes are set\n'
    pay_once "  " "$store" '' "$(uuid_account)" yes || note_failure ofd_items
  else
    say_blank
    printf '  5. skipped: no OFD codes are configured, so the app sends no ofd_items either\n'
  fi

  printf '\n'
  if [ -n "$failures" ]; then
    printf 'FAILED variants:%s\n' "$failures"
    printf 'The first one named is the difference that breaks pay/apply; 1 failing means
something other than the request shape is wrong.\n'
    exit 1
  fi
  printf 'Every variant went through. The difference is not in the request shape:\n'
  printf 'compare the backend log line "ATMOS single payment:" with the variants above.\n'
else
  pay_once '' "$store" '' "smoke-$(date +%s)" no || exit 1
fi

# ---- tidy up: the test card does not stay bound ----
body=$(post /partner/remove-card "{\"id\":$card_id,\"token\":\"$card_token\"}") || body=''
code=$(code_of "$body")
if [ "$code" = "OK" ]; then
  step remove-card "$code" OK
else
  step remove-card "${code:--}" FAILED "the card stays bound; the payment itself was fine"
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

mode=single
[ "$bisect" = yes ] && mode=bisect

if printf '%s\n%s\n%s\n%s\n%s\n' \
     "$binding" "$otp" "$amount" "$mode" "${ATMOS_SMOKE_RECIPIENT_ID:-}" \
   | docker compose exec -T backend sh -c "$COMMON
$PAY"; then
  unset otp
  say ""
  if [ "$mode" = bisect ]; then
    say "ALL VARIANTS OK. Every payment was taken and given back in full."
  else
    say "ALL STEPS OK. The payment was taken and given back in full."
  fi
else
  unset otp
  say ""
  if [ "$mode" = bisect ]; then
    say "A variant FAILED. The line above names the difference that breaks pay/apply;"
    say "the backend log line \"ATMOS single payment:\" says which shape this server sends."
  else
    say "FAILED. The step above says which one and what ATMOS answered."
    say "Nothing is retried automatically; if pay/apply or pay/reverse is the one that failed,"
    say "check the transaction in the ATMOS cabinet before running this again."
  fi
  exit 1
fi
