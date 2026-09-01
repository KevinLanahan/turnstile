# Turnstile — Design

**A high-contention seat reservation system with a double-entry payment ledger.**
Java 21 · Spring Boot 3 · PostgreSQL · Redis · Docker

## The one-sentence pitch

Ten thousand people want the same seat at the same instant. Exactly one gets it,
nobody's card is charged twice, and every cent is accounted for — provable under
load and under failure.

## Architecture

A **modular monolith**, not microservices. Four modules with hard boundaries —
separate packages, no cross-module entity imports, communication through
interfaces:

```
inventory/   seats, holds, availability, expiry sweeper
booking/     orchestration: hold -> pay -> confirm
payments/    fake gateway adapter, idempotency, retries
ledger/      double-entry accounts, transfers, reconciliation
outbox/      transactional outbox + relay worker
```

One deployable, one `docker compose up`. Splitting a solo project into services
buys deployment pain and no interview credit; the module boundaries are the same
seams a split would follow, and can be described as such.

## The three hard problems

### 1. Seat allocation under contention

Postgres is the source of truth and correctness is **structural**:

```sql
UPDATE seats SET status = 'HELD', hold_id = ?, version = version + 1
 WHERE id = ? AND status = 'AVAILABLE'
```

One row affected = claimed. Zero = lost, return 409. No lock to leak, no lease to
expire, no split-brain. A partial unique index on `holds (seat_id) WHERE state =
'ACTIVE'` makes a double-claim physically impossible even under buggy application
logic.

Redis enters in M2 as the **fast path, not the safety mechanism**: a Lua script
(atomic on Redis's single thread) holds the availability bitmap and sheds the
~99% of doomed requests before they reach Postgres. This is load shedding.

> Redis for throughput, a database constraint for correctness — a lock service
> cannot give you safety under a network partition.

### 2. The hold expiry race

Holds last 5 minutes. The nasty case: **payment confirms at the exact millisecond
the sweeper expires the hold.** Naive implementations either charge the card and
give the seat away, or release a seat that has been paid for.

The fix is a strict state machine where every transition is a conditional update.
Confirmation walks `AVAILABLE -> HELD -> BOOKED` in one transaction that
re-verifies the hold is still live and unexpired. The sweeper uses the same
conditional pattern, so whichever side wins, the loser matches zero rows and backs
out cleanly.

### 3. Money correctness

**Double-entry ledger.** Every transfer writes balanced entries — debit user,
credit event revenue — with a constraint enforcing that entries per transfer sum
to zero. Balances are *derived* from entries, never stored and mutated. Refunds
are new reversing transfers, never deletes, so the audit trail stays complete.

**Exactly-once payment** via idempotency keys plus the **transactional outbox**:
the payment intent and the outbox row commit in the same transaction, and a relay
worker publishes it. At-least-once delivery plus consumer dedupe gives effectively
exactly-once. This is the answer to "what if the service crashes between the write
and the message?"

**A reconciliation job** walks the ledger nightly and asserts global debits equal
credits, and that every `BOOKED` seat has exactly one settled transfer. Drift
produces a report.

## Data model

```
events(id, name, venue, starts_at, sales_open_at)
seats(id, event_id, section, row_label, seat_number, price_cents,
      status, hold_id, version)              -- AVAILABLE | HELD | BOOKED
holds(id, seat_id, user_id, expires_at, idempotency_key, state)

accounts(id, owner_type, owner_id, currency)  -- users, event revenue, fees
transfers(id, idempotency_key, state, created_at)
ledger_entries(id, transfer_id, account_id, direction, amount_cents)
   -- CHECK: signed sum per transfer_id = 0

outbox(id, aggregate_id, event_type, payload, published_at)
```

Money is `BIGINT` cents, never a float. Partial unique index for one active hold
per seat. Unique index on every idempotency key.

## Stack

| Piece | Choice | Why |
|---|---|---|
| Language | Java 21 | Virtual threads for a high-concurrency service |
| Framework | Spring Boot 3 | What the banks run |
| DB | PostgreSQL 16 + Flyway | Source of truth, versioned migrations |
| Data access | JdbcTemplate | Explicit SQL — the SQL *is* the design here |
| Cache | Redis 7 + Lua | Fast path and load shedding (M2) |
| Tests | JUnit 5 + Testcontainers | Real Postgres, no mocks lying to us |
| Load | k6 | Scripts committed, results in the README |
| Ops | Docker Compose | One command |

**Kafka is a stretch goal, not a requirement.** The outbox plus a polling relay
gives the same guarantees at a fraction of the operational weight. If the Kafka
line is wanted, the relay's sink is swapped at the end — a contained change, done
last so it cannot block anything.

## Milestones

- **M0 — Skeleton.** Repo, Docker Compose, Spring Boot, Flyway baseline,
  Testcontainers, CI.
- **M1 — The failing test first.** The 500-thread contention test written
  *before* the feature, then the conditional-update path until it passes.
- **M2 — Redis fast path.** Lua hold script, availability bitmap, before/after
  benchmark. The delta is the resume figure.
- **M3 — Expiry + state machine.** Sweeper, plus the race test hammering
  confirm-vs-expire at the same instant.
- **M4 — Money.** Fake gateway (configurable latency and failure rate),
  idempotency, double-entry ledger, outbox + relay.
- **M5 — Proof.** k6 load test with p99 report, chaos tests, reconciliation job.
- **M6 — Polish.** README graphs, architecture diagram, thin seat-map UI.

M1–M3 alone is already portfolio-worthy. That is deliberate.

## What makes this not a listicle project

Ticketing clones are everywhere. These four are why this one is different, and
they are roughly 15% of the work:

1. **The contention test runs in CI** and the README shows it green. Proof, not
   claims.
2. **Chaos tests**: kill Redis mid-booking, kill the relay mid-transfer; assert
   the system degrades but never oversells and never loses money.
3. **A committed load-test report** — real p99s, real throughput, and an honest
   note on where it broke and why.
4. **The reconciliation job** catching a deliberately injected inconsistency.

## Explicitly out of scope

Microservices. CQRS or event sourcing. Kubernetes. A real payment provider.
Auth beyond a minimal JWT. A seat-map editor. Mobile.

If it does not serve the one-sentence pitch, it is out. A tight finished project
is worth a great deal; a sprawling half-finished one is worth nothing.
