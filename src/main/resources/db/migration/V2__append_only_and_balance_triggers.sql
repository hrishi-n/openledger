-- Enforces ledger invariants at the database layer as a backstop.

-- Blocks UPDATE and DELETE on postings; corrections go through a reversing entry.
CREATE OR REPLACE FUNCTION openledger_forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'postings are append-only; % is not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER postings_append_only
    BEFORE UPDATE OR DELETE ON postings
    FOR EACH ROW EXECUTE FUNCTION openledger_forbid_mutation();

-- Checks that each journal entry nets to zero per currency, once per commit.
CREATE OR REPLACE FUNCTION openledger_assert_entry_balanced() RETURNS trigger AS $$
DECLARE
    bad RECORD;
BEGIN
    FOR bad IN
        SELECT currency,
               sum(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END) AS net
        FROM postings
        WHERE entry_id = NEW.entry_id
        GROUP BY currency
        HAVING sum(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END) <> 0
    LOOP
        RAISE EXCEPTION 'journal entry % is unbalanced in %: net = %',
            NEW.entry_id, bad.currency, bad.net;
    END LOOP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER postings_balanced
    AFTER INSERT ON postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION openledger_assert_entry_balanced();
