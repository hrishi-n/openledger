-- Creates the core double-entry ledger schema.

CREATE TABLE accounts (
    id          uuid PRIMARY KEY,
    tenant_id   uuid        NOT NULL,
    type        text        NOT NULL CHECK (type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    currency    char(3)     NOT NULL,
    owner_ref   text        NOT NULL,
    min_balance bigint      NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- Enforces one account per tenant, owner, and currency.
CREATE UNIQUE INDEX accounts_tenant_owner_currency ON accounts (tenant_id, owner_ref, currency);

CREATE TABLE journal_entries (
    id             uuid PRIMARY KEY,
    tenant_id      uuid        NOT NULL,
    external_id    text,
    description    text        NOT NULL,
    status         text        NOT NULL DEFAULT 'POSTED' CHECK (status IN ('POSTED', 'REVERSED')),
    reverses_entry uuid        REFERENCES journal_entries (id),
    created_by     text        NOT NULL DEFAULT 'system',
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX journal_entries_tenant_created ON journal_entries (tenant_id, created_at DESC);

CREATE TABLE postings (
    id         uuid PRIMARY KEY,
    entry_id   uuid        NOT NULL REFERENCES journal_entries (id),
    account_id uuid        NOT NULL REFERENCES accounts (id),
    direction  text        NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount     bigint      NOT NULL CHECK (amount > 0),
    currency   char(3)     NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX postings_account_created ON postings (account_id, created_at);
CREATE INDEX postings_entry ON postings (entry_id);

-- Caches each account's normalized balance for fast reads.
CREATE TABLE account_balances (
    account_id uuid PRIMARY KEY REFERENCES accounts (id),
    balance    bigint      NOT NULL DEFAULT 0,
    version    bigint      NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE idempotency_keys (
    tenant_id       uuid        NOT NULL,
    key             text        NOT NULL,
    request_hash    text        NOT NULL,
    response_status int         NOT NULL,
    response_body   jsonb       NOT NULL,
    entry_id        uuid        REFERENCES journal_entries (id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, key)
);
