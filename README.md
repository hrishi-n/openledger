# openledger

An append-only, double-entry ledger service - the kind of accounting core a
wallet or payments product would sit on top of. Every movement of money is a
balanced journal entry, balances are derived from those entries rather than
stored and mutated, and a reconciliation job keeps checking that the two
still agree.

Built with Spring Boot 3, Postgres 16, and plain SQL (no JPA). Apache-2.0.

## Why this exists

A `balance` column you `UPDATE` in place can't tell you why the number is
what it is, doesn't survive a concurrent double-spend cleanly, and gives you
nothing to reconstruct after a bug. A proper double-entry ledger fixes that,
so I built the smallest version that still forces you to deal with the hard
parts:

- **Zero-sum invariant** - every entry nets to zero per currency, enforced
  both in the domain code and by a deferred Postgres constraint trigger, so
  nothing sneaks past the application layer.
- **Append-only** - `postings` reject `UPDATE`/`DELETE` at the database
  level. Corrections happen as reversing entries, not edits.
- **Idempotency** - retrying `POST /transfers` with the same
  `Idempotency-Key` returns the original result exactly once, even if the
  retry races a duplicate.
- **No oversell under concurrency** - balance rows lock in a deterministic
  order (so no deadlocks), and an account can never cross its `min_balance`
  floor, no matter how many transfers hit it at once.
- **Provable correctness** - a scheduled job recomputes every balance from
  postings, checks the global trial balance, and publishes the worst drift
  it finds as a metric (`openledger_reconciliation_max_abs_drift`).
- **History** - `GET /accounts/{id}/balance?asOf=<timestamp>` reads straight
  from the immutable log, no separate audit table needed.

`docs/DESIGN.md` has the reasoning behind each of these if you want the
longer version.

## How this was built

This is a vibe-coded project - Claude wrote most of the implementation, with
me driving requirements and review rather than typing every line. It wasn't
a blind accept-all session: every non-trivial decision (plain SQL vs JPA,
pessimistic vs optimistic locking, minor units vs `BigDecimal`, how
idempotency should fail) got argued out with the trade-offs on both sides,
and I made the final call on each one. `docs/DESIGN.md` records that
reasoning, including the alternatives that were considered and rejected, not
just the choice that won.

Read the code with that in mind - it's a solid MVP, not something that's
been through years of production hardening.

## Run it

```bash
docker compose up --build          # app + postgres + prometheus + grafana
./scripts/demo.sh                  # end-to-end walkthrough (needs curl + jq)
```

- API + Swagger UI: http://localhost:9000/swagger-ui
- Metrics: http://localhost:9000/actuator/prometheus
- Grafana: http://localhost:3000 (anonymous, admin)

If you'd rather skip Docker, point a local Postgres at `localhost:5432`
(db/user/pass all `openledger`) and run `mvn spring-boot:run`.

## Test

```bash
mvn verify
```

Integration tests run against a throwaway Postgres container (Testcontainers),
so the real migrations and triggers get exercised, not a mock. The build pins
the Docker API version and disables Ryuk (see `pom.xml`) so it works on Colima
as well as Docker Desktop. **Colima users** need one extra line in
`~/.testcontainers.properties`:

```
docker.host=unix:///Users/<you>/.colima/default/docker.sock
```

Notable tests:

| Test | Proves |
|------|--------|
| `ConcurrentTransferTest` | 250 parallel transfers out of a wallet funded for 150 -> exactly 150 succeed, 100 rejected, zero drift |
| `DoubleEntryPropertyTest` | property-based: any zero-sum entry validates; any perturbation breaks it |
| `TransferIntegrationTest` | fee splits, overdraft rejection, idempotent retry, unbalanced-entry rejection |

## API

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/accounts` | create a ledger account |
| `GET` | `/accounts/{id}` | account + current balance |
| `GET` | `/accounts/{id}/balance?asOf=` | balance now, or as of a timestamp |
| `GET` | `/accounts/{id}/transactions?limit=` | statement lines |
| `POST` | `/journal-entries` | post an arbitrary balanced multi-line entry |
| `GET` | `/journal-entries/{id}` | fetch an entry with its postings |
| `POST` | `/journal-entries/{id}/reversal` | post the reversing entry |
| `POST` | `/transfers` | move money A -> B, optional fee, one balanced entry |
| `POST` | `/reconciliation/run` | run reconciliation now (200 clean / 409 drift) |
| `GET` | `/reconciliation/reports/latest` | last report |

`X-Tenant-Id` scopes every request (falls back to the configured demo tenant
if you don't set it). `Idempotency-Key` is required on `POST /transfers` and
`/journal-entries`.

All amounts are integer **minor units** (cents) - no floating point, anywhere,
on purpose.

## Status

MVP, and I know what's missing: multi-currency FX entries, mock bank-rail
deposits/withdrawals with an outbox + webhooks, per-tenant API keys, and
published load-test numbers. Roadmap-ish thoughts on those are in
`docs/DESIGN.md`.
