# Design notes

## Data model

Five tables (`V1__core_schema.sql`):

- **`accounts`** - one row per `(tenant, owner_ref, currency)`. `type` is one of
  the five classical types and fixes the account's normal side. `min_balance` is
  the floor the account may not cross (0 for a user wallet, a large negative
  number for an internal float/equity account).
- **`journal_entries`** - the header. Immutable except `status`, which flips to
  `REVERSED` when a reversing entry is posted.
- **`postings`** - the lines. `amount` is always a **positive** `bigint` in minor
  units; `direction` (`DEBIT`/`CREDIT`) carries the sign. Append-only.
- **`account_balances`** - a materialized cache, one row per account, holding the
  balance **normalized to the account's normal side** (a bigger number always
  means "more value"). Updated in the same transaction as the postings. Not the
  source of truth - the postings are.
- **`idempotency_keys`** - `(tenant_id, key)` primary key, a hash of the request
  body, and the stored response.

### Why not JPA

A ledger wants every write to be explicit and every read to be a known query.
JPA's dirty checking, lazy loading, and first-level cache are all liabilities
here: an accidental entity mutation could rewrite history, and `postings` are
append-only so there is nothing to "update" anyway. Plain `NamedParameterJdbcTemplate`
keeps the SQL in view. jOOQ would be a reasonable upgrade for type-safe queries;
it was left out to keep the build free of a code-generation step.

### Why minor units, not BigDecimal

Integer cents are exact, cheap to sum in the database, and impossible to get
wrong with a rounding mode. Currency scale (for display) comes from
`java.util.Currency`. Cross-currency entries (not yet implemented) would balance
per currency and post the difference to an FX position account.

## The zero-sum invariant, enforced three ways

1. **Domain** - `LedgerService.validateStructure` rejects an entry that has fewer
   than two lines, a non-positive amount, or a per-currency net other than zero,
   before any row is touched.
2. **Database backstop** - `postings_balanced` is a `CONSTRAINT TRIGGER ...
   DEFERRABLE INITIALLY DEFERRED`. It runs once, at `COMMIT`, and re-checks that
   the entry nets to zero. Deferring it is what lets a multi-row entry be
   inserted line by line without tripping the check mid-way.
3. **Global reconciliation** - the trial balance (`sum(debits) - sum(credits)`
   across the entire ledger, per currency) must be exactly zero. Checked on a
   schedule and on demand.

`postings_append_only` is a separate `BEFORE UPDATE OR DELETE` trigger that
raises unconditionally - the ledger is immutable at the storage layer, not just
by convention.

## Concurrency

The risk: two transfers debiting the same wallet at the same time, each seeing
enough balance, both committing, wallet goes negative.

**Chosen approach - pessimistic, deterministic lock order.** `LedgerService`
collects the distinct account ids, sorts them, and issues
`SELECT ... FROM account_balances WHERE account_id IN (:ids) ORDER BY account_id
FOR UPDATE`. Sorting means two entries over accounts `{X, Y}` always take `X`
then `Y`, so they queue instead of deadlocking. Under the lock the service
computes the net delta per account, checks each against `min_balance`, then
writes postings and balance updates in one transaction.

**Alternatives considered:**

- *Optimistic* (`version` column, retry on conflict) - fewer locks, better under
  low contention, but a hot account (say a platform fee account credited by every
  transfer) would thrash on retries. `account_balances.version` is already in the
  schema so this can be added per-account later.
- *`SERIALIZABLE` isolation + retry on `40001`* - simplest to reason about, but
  pushes retry handling into every caller and has the same hot-row problem.
- *Per-account command queue* - the endgame for a genuinely hot account; out of
  scope here.

The `ConcurrentTransferTest` is the proof: 250 concurrent transfers against a
wallet funded for exactly 150 must yield 150 successes, 100 `INSUFFICIENT_FUNDS`,
and a clean reconciliation.

## Idempotency

`POST /transfers` and `POST /journal-entries` accept an `Idempotency-Key` header.
`LedgerService`:

1. hashes the request body (`RequestHasher`, SHA-256 over the canonical JSON);
2. looks up `(tenant_id, key)`. If found and the hash matches, returns the stored
   response. If found and the hash differs, returns `409` - the same key was
   reused for a different request;
3. otherwise does the work, then inserts the key row **inside the same
   transaction** as the entry, so a crash cannot leave a key without its entry;
4. if that insert loses a race to a concurrent duplicate (`DuplicateKeyException`),
   falls back to the winner's stored response.

## Reconciliation

`ReconciliationService.run()`:

- **Per-account drift** - recompute every account's normalized balance from
  `postings` and compare to `account_balances`. Any mismatch is drift.
- **Trial balance** - `sum(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount
  END)` grouped by currency; every currency must be zero.

The worst absolute drift and worst trial-balance net are published as gauges
(`openledger_reconciliation_max_abs_drift`,
`openledger_reconciliation_trial_balance_worst_net`) so an alert can fire on any
nonzero value. Runs every 5 minutes (`openledger.reconciliation.cron`) and via
`POST /reconciliation/run`.

## Historical balances

Because `postings` is append-only, `GET /accounts/{id}/balance?asOf=<ts>` is just
`sum(...) WHERE created_at <= :asOf`. No snapshots, no event replay machinery.

## Multi-tenancy

Every row carries `tenant_id`. `TenantFilter` resolves it from `X-Tenant-Id`
(falling back to a configured demo tenant) into a `ThreadLocal`; repositories
filter on it. In a real deployment the tenant would come from an authenticated
API key, and row-level security in Postgres would be the backstop.

## Not yet built

- Mock bank rail: `POST /deposits` / `/withdrawals` with an async settlement
  state machine, a transactional outbox, and signed status webhooks.
- Multi-currency transfers with FX position + P&L accounts.
- Per-tenant API keys and Postgres row-level security.
- Published load-test numbers (`load/transfers.js` is ready; the README table
  needs real figures).
- Terraform for an AWS deploy (RDS + ECS Fargate + ALB). For a permanent free
  instance, run the compose stack on an Oracle Cloud Always Free VM.
