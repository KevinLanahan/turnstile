-- Turnstile V3: transactional outbox.
--
-- The problem this solves is the oldest one in distributed systems: you need to
-- change your database AND tell another system about it, and you cannot do both
-- atomically because they are different systems.
--
--   Write DB, then publish  -> crash in between and the event is lost forever.
--   Publish, then write DB  -> crash in between and you announced something that
--                              never happened.
--   Two-phase commit        -> a cure worse than the disease.
--
-- The outbox sidesteps it. The event is written to THIS database, in the SAME
-- transaction as the business change. If the transaction commits, the event
-- exists; if it rolls back, so does the event. A separate relay then reads the
-- table and publishes.
--
-- That gives AT-LEAST-ONCE delivery, never exactly-once: the relay can publish
-- and then crash before recording that it did, and will publish again on restart.
-- Exactly-once is not achievable here, so the consumer is made idempotent instead
-- (see consumed_events). At-least-once delivery plus an idempotent consumer is
-- what people actually mean when they say exactly-once.

CREATE TABLE outbox (
    id           BIGSERIAL   PRIMARY KEY,
    aggregate_id UUID        NOT NULL,
    event_type   TEXT        NOT NULL,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    attempts     INT         NOT NULL DEFAULT 0,
    last_error   TEXT
);

-- The relay only ever asks for unpublished rows, and a partial index keeps that
-- query cheap no matter how large the published history grows.
CREATE INDEX idx_outbox_unpublished ON outbox (id) WHERE published_at IS NULL;

-- The consumer's side of the bargain. Because delivery is at-least-once, the
-- consumer must be able to recognise an event it has already handled. Recording
-- the id it processed - in the same transaction as whatever it did about the
-- event - is what turns duplicate delivery into a no-op.
CREATE TABLE consumed_events (
    consumer    TEXT        NOT NULL,
    event_id    BIGINT      NOT NULL REFERENCES outbox (id),
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (consumer, event_id)
);
