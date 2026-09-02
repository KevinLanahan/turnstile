-- Turnstile V2: double-entry ledger.
--
-- The rules this schema exists to make unbreakable:
--
--   1. Every transfer balances. Money is never created or destroyed, only moved.
--   2. Entries are append-only. History is corrected by posting a reversing
--      transfer, never by editing or deleting what was already recorded.
--   3. Balances are DERIVED from entries. There is no stored balance column to
--      drift out of sync with the entries that produced it.
--
-- All three are enforced by the database rather than by application code, because
-- application code is where bugs live and this is the part where bugs cost money.

CREATE TABLE accounts (
    id         UUID        PRIMARY KEY,
    kind       TEXT        NOT NULL CHECK (kind IN ('CUSTOMER', 'EVENT_REVENUE')),
    owner_id   UUID        NOT NULL,
    currency   CHAR(3)     NOT NULL DEFAULT 'USD',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- One account per (owner, kind, currency). Two accounts for the same customer
    -- in the same currency is how a balance quietly goes missing.
    CONSTRAINT uq_account_identity UNIQUE (kind, owner_id, currency)
);

CREATE TABLE transfers (
    id              UUID        PRIMARY KEY,
    kind            TEXT        NOT NULL CHECK (kind IN ('PURCHASE', 'REFUND')),
    idempotency_key TEXT        NOT NULL,
    reverses_id     UUID        REFERENCES transfers (id),
    memo            TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- A refund reverses exactly one earlier transfer; a purchase reverses nothing.
    CONSTRAINT ck_refund_reverses CHECK (
        (kind = 'REFUND' AND reverses_id IS NOT NULL)
        OR (kind = 'PURCHASE' AND reverses_id IS NULL)
    )
);

-- Replaying a payment must never move money twice.
CREATE UNIQUE INDEX uq_transfers_idempotency_key ON transfers (idempotency_key);

-- A given transfer can be reversed at most once.
CREATE UNIQUE INDEX uq_transfers_one_reversal ON transfers (reverses_id) WHERE reverses_id IS NOT NULL;

CREATE TABLE ledger_entries (
    id           BIGSERIAL   PRIMARY KEY,
    transfer_id  UUID        NOT NULL REFERENCES transfers (id),
    account_id   UUID        NOT NULL REFERENCES accounts (id),

    -- Signed cents. Negative is money leaving the account, positive is money
    -- arriving. BIGINT, never a float: 0.1 + 0.2 is not 0.3 and a ledger is the
    -- last place to discover that.
    amount_cents BIGINT      NOT NULL CHECK (amount_cents <> 0),
    currency     CHAR(3)     NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ledger_entries_account ON ledger_entries (account_id);
CREATE INDEX idx_ledger_entries_transfer ON ledger_entries (transfer_id);


-- ---------------------------------------------------------------------------
-- Rule 1: every transfer balances, per currency.
-- ---------------------------------------------------------------------------
-- This cannot be a CHECK constraint, because a CHECK sees one row and this is a
-- property of a SET of rows. It is a DEFERRED constraint trigger instead, which
-- means it runs at COMMIT rather than after each INSERT - so a transaction is
-- free to write the debit leg and the credit leg in separate statements, as long
-- as the books balance by the time it tries to commit.
--
-- The effect: application code that writes only one side of a transfer does not
-- produce a subtly wrong balance to be discovered during an audit months later.
-- It fails to commit.
--
-- Balance is checked per currency, because a transfer moving 100 USD and -100 EUR
-- sums to zero and is nonsense.

CREATE OR REPLACE FUNCTION assert_transfer_balances() RETURNS TRIGGER AS $$
DECLARE
    unbalanced_currency CHAR(3);
    imbalance           BIGINT;
BEGIN
    SELECT currency, SUM(amount_cents)
      INTO unbalanced_currency, imbalance
      FROM ledger_entries
     WHERE transfer_id = NEW.transfer_id
     GROUP BY currency
    HAVING SUM(amount_cents) <> 0
     LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION
            'Transfer % does not balance in %: off by % cents',
            NEW.transfer_id, unbalanced_currency, imbalance
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_ledger_entries_must_balance
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_transfer_balances();


-- ---------------------------------------------------------------------------
-- Rule 2: entries are append-only.
-- ---------------------------------------------------------------------------
-- A ledger you can edit is not a ledger, it is a spreadsheet. Mistakes are
-- corrected by posting a reversing transfer, which leaves both the error and the
-- correction visible - that is the whole point of an audit trail.

CREATE OR REPLACE FUNCTION reject_ledger_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'ledger_entries is append-only; post a reversing transfer instead of a % ',
        TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entries_no_update
    BEFORE UPDATE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

CREATE TRIGGER trg_ledger_entries_no_delete
    BEFORE DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();


-- ---------------------------------------------------------------------------
-- Rule 3: balances are derived, never stored.
-- ---------------------------------------------------------------------------
CREATE VIEW account_balances AS
SELECT a.id AS account_id,
       a.kind,
       a.owner_id,
       a.currency,
       COALESCE(SUM(e.amount_cents), 0) AS balance_cents,
       count(e.id)                      AS entry_count
  FROM accounts a
  LEFT JOIN ledger_entries e ON e.account_id = a.id
 GROUP BY a.id, a.kind, a.owner_id, a.currency;
