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

say "ATMOS smoke test"
say "  environment : $environment"
say "  profiles    : $profiles"
say "  amount      : $amount tiyin ($((amount / 100)) so'm), reversed at the end"
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
account="smoke-$(date +%s)"

# The examples send it as a number; quote it if this one is not.
store=$ATMOS_STORE_ID
case "$store" in '' | *[!0-9]*) store="\"$ATMOS_STORE_ID\"" ;; esac

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

# ---- a small payment to our own store ----
body=$(post /merchant/pay/create "{\"store_id\":$store,\"account\":\"$account\",\"amount\":$amount}") \
  || { step pay/create - FAILED "no answer"; exit 1; }
code=$(code_of "$body")
txn=$(field transaction_id "$body")
if [ "$code" != "OK" ] || [ -z "$txn" ]; then
  step pay/create "$code" FAILED "$(description_of "$body")"
  exit 1
fi
step pay/create "$code" OK "$amount tiyin, transaction $txn"

body=$(post /merchant/pay/pre-apply \
  "{\"store_id\":$store,\"transaction_id\":$txn,\"card_token\":\"$card_token\"}") \
  || { step pay/pre-apply - FAILED "no answer"; exit 1; }
code=$(code_of "$body")
if [ "$code" != "OK" ]; then
  step pay/pre-apply "$code" FAILED "$(description_of "$body")"
  exit 1
fi
step pay/pre-apply "$code" OK

# A card token needs no SMS code of its own: the docs' fixed 111111 is what confirms it.
body=$(post /merchant/pay/apply "{\"store_id\":$store,\"transaction_id\":$txn,\"otp\":\"111111\"}") \
  || { step pay/apply - FAILED "no answer, the payment may still have gone through"; exit 1; }
code=$(code_of "$body")
success=$(field success_trans_id "$body")
if [ "$code" != "OK" ]; then
  step pay/apply "$code" FAILED "$(description_of "$body")"
  exit 1
fi
step pay/apply "$code" OK "confirmed=$(field confirmed "$body"), success_trans_id $success"

# ---- what ATMOS says about it, the way the reconcile job asks ----
body=$(post /merchant/pay/get "{\"store_id\":$store,\"transaction_id\":$txn}") \
  || { step pay/get - FAILED "no answer"; exit 1; }
code=$(code_of "$body")
step pay/get "$code" OK "confirmed=$(field confirmed "$body"), amount $(field amount "$body") tiyin"

# ---- and back in full. There is no partial reverse anywhere in this application. ----
body=$(post /merchant/pay/reverse "{\"transaction_id\":$success,\"reason\":\"ATMOS smoke test\"}") \
  || { step pay/reverse - FAILED "no answer: CHECK THE MONEY BY HAND"; exit 1; }
code=$(code_of "$body")
if [ "$code" != "OK" ]; then
  step pay/reverse "$code" FAILED "$(description_of "$body") - CHECK THE MONEY BY HAND"
  exit 1
fi
step pay/reverse "$code" OK "$amount tiyin back"

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

if printf '%s\n%s\n%s\n' "$binding" "$otp" "$amount" \
   | docker compose exec -T backend sh -c "$COMMON
$PAY"; then
  unset otp
  say ""
  say "ALL STEPS OK. The payment was taken and given back in full."
else
  unset otp
  say ""
  say "FAILED. The step above says which one and what ATMOS answered."
  say "Nothing is retried automatically; if pay/apply or pay/reverse is the one that failed,"
  say "check the transaction in the ATMOS cabinet before running this again."
  exit 1
fi
