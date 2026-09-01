-- Turnstile V1: inventory core.
--
-- Design notes that matter:
--   * Money is BIGINT cents. Never a float, never a double.
--   * Seat status is TEXT + CHECK rather than a Postgres ENUM, because
--     altering an ENUM is a migration headache and buys nothing here.
--   * Correctness of "one seat, one winner" is enforced by the DATABASE,
--     not by application code. See the partial unique index at the bottom.

CREATE TABLE events (
    id            UUID        PRIMARY KEY,
    name          TEXT        NOT NULL,
    venue         TEXT        NOT NULL,
    starts_at     TIMESTAMPTZ NOT NULL,
    sales_open_at TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE seats (
    id          UUID        PRIMARY KEY,
    event_id    UUID        NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    section     TEXT        NOT NULL,
    row_label   TEXT        NOT NULL,
    seat_number INT         NOT NULL,
    price_cents BIGINT      NOT NULL CHECK (price_cents >= 0),
    status      TEXT        NOT NULL DEFAULT 'AVAILABLE'
                            CHECK (status IN ('AVAILABLE', 'HELD', 'BOOKED')),
    hold_id     UUID,
    version     BIGINT      NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_seat_position UNIQUE (event_id, section, row_label, seat_number),

    -- A seat that is not AVAILABLE must point at the hold that claimed it,
    -- and an AVAILABLE seat must not.
    CONSTRAINT ck_seat_hold_consistency CHECK (
        (status = 'AVAILABLE' AND hold_id IS NULL)
        OR (status <> 'AVAILABLE' AND hold_id IS NOT NULL)
    )
);

-- Availability lookups for an event are the hottest read path.
CREATE INDEX idx_seats_event_status ON seats (event_id, status);

CREATE TABLE holds (
    id              UUID        PRIMARY KEY,
    seat_id         UUID        NOT NULL REFERENCES seats (id) ON DELETE CASCADE,
    user_id         UUID        NOT NULL,
    state           TEXT        NOT NULL DEFAULT 'ACTIVE'
                                CHECK (state IN ('ACTIVE', 'CONFIRMED', 'EXPIRED', 'RELEASED')),
    idempotency_key TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_hold_expiry_after_creation CHECK (expires_at > created_at)
);

-- Replaying the same request must never create a second hold.
CREATE UNIQUE INDEX uq_holds_idempotency_key ON holds (idempotency_key);

-- THE BACKSTOP.
-- Even if every line of application logic were wrong, this partial unique index
-- makes it physically impossible for one seat to carry two live holds. The
-- conditional UPDATE in SeatRepository is the primary mechanism; this is
-- defence in depth, and it is what turns "we tested it and it seemed fine"
-- into "the database will not permit it".
CREATE UNIQUE INDEX uq_one_active_hold_per_seat ON holds (seat_id) WHERE state = 'ACTIVE';

-- Sweeping expired holds scans on (state, expires_at).
CREATE INDEX idx_holds_state_expires ON holds (state, expires_at);
