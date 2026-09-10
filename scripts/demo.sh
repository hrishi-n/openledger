#!/usr/bin/env bash
# Runs an end-to-end walkthrough against a running instance.
set -euo pipefail

BASE="${BASE:-http://localhost:9000}"
hdr=(-H "Content-Type: application/json" -H "X-Tenant-Id: 11111111-1111-1111-1111-111111111111")

acct() {
  curl -sf "${hdr[@]}" -X POST "$BASE/accounts" \
    -d "{\"type\":\"$1\",\"currency\":\"USD\",\"ownerRef\":\"$2\",\"minBalance\":$3}" | jq -r .id
}

echo "creating chart of accounts..."
OPENING=$(acct EQUITY    "equity:opening" -100000000)
BANK=$(acct    ASSET     "cash:bank"      -100000000)
WALLET_A=$(acct LIABILITY "user:alice"    0)
WALLET_B=$(acct LIABILITY "user:bob"      0)
FEES=$(acct    REVENUE   "revenue:fees"   -100000000)

echo "funding alice with \$100.00 (opening balance entry)..."
curl -sf "${hdr[@]}" -X POST "$BASE/journal-entries" -d "{
  \"description\": \"Alice opening balance\",
  \"postings\": [
    {\"accountId\": \"$OPENING\",  \"direction\": \"DEBIT\",  \"amount\": 10000, \"currency\": \"USD\"},
    {\"accountId\": \"$WALLET_A\", \"direction\": \"CREDIT\", \"amount\": 10000, \"currency\": \"USD\"}
  ]
}" | jq -c '{entry: .id, status}'

echo "alice sends bob \$30.00 with a \$1.00 fee (retried twice, same Idempotency-Key)..."
for i in 1 2; do
  curl -sf "${hdr[@]}" -H "Idempotency-Key: alice-to-bob-001" -X POST "$BASE/transfers" -d "{
    \"sourceAccountId\": \"$WALLET_A\",
    \"destinationAccountId\": \"$WALLET_B\",
    \"amount\": 3100,
    \"currency\": \"USD\",
    \"feeAmount\": 100,
    \"feeAccountId\": \"$FEES\",
    \"reference\": \"invoice-42\"
  }" | jq -c "{attempt: $i, entry: .id}"
done

echo "balances:"
for a in "$WALLET_A:alice" "$WALLET_B:bob" "$FEES:fees"; do
  id="${a%%:*}"; name="${a##*:}"
  bal=$(curl -sf "${hdr[@]}" "$BASE/accounts/$id/balance" | jq -r .balance)
  printf '  %-6s %s\n' "$name" "$bal"
done

echo "reconciliation:"
curl -s "${hdr[@]}" -X POST "$BASE/reconciliation/run" | jq -c '{clean, drifts: (.balanceDrifts | length), trialBalance}'
