# Turnstile

**A high-contention seat reservation system with a double-entry payment ledger.**

Java 21 · Spring Boot 3 · PostgreSQL 16 · Redis 7 · Docker

---

Ten thousand people want the same seat at the same instant. Exactly one gets it,
nobody's card is charged twice, and every cent is accounted for — provable under
load and under failure.

That sentence is the entire project. Everything in this repo serves it.

## Status

| Milestone | Scope | State |
|---|---|---|
| M0 | Project skeleton, Docker Compose, migrations, Testcontainers | done |
| M1 | Atomic seat claim + the 500-thread contention test | done |
| M2 | Redis fast path (Lua), before/after benchmark | next |
| M3 | Hold expiry, state machine, confirm-vs-expire race test | planned |
| M4 | Payment ledger, idempotency, transactional outbox | planned |
| M5 | k6 load test, chaos tests, reconciliation job | planned |
| M6 | README graphs, architecture diagram, seat-map UI | planned |

See [DESIGN.md](DESIGN.md) for the full design and rationale.

## Running it

Requires **JDK 21** and **Docker** (the test suite starts real Postgres containers).

```bash
# infrastructure
docker compose up -d

# the whole suite, including the contention test
mvn test

# just the test that matters
mvn test -Dtest=SeatContentionTest

# run the service
mvn spring-boot:run
```

Claim a seat:

```bash
curl -i -X POST http://localhost:8080/api/holds \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"seatId":"<uuid>","userId":"<uuid>"}'
```

`201` means you got it. `409` means someone beat you to it.

## Why it does not oversell

The claim is a single atomic conditional update:

```sql
UPDATE seats
   SET status = 'HELD', hold_id = ?, version = version + 1
 WHERE id = ? AND status = 'AVAILABLE'
```

One row affected means you won. Zero means you lost. There is no read-then-write
window to exploit.

Under contention Postgres serialises writers on the row lock. The first
transaction updates the row; every other transaction blocks until it commits and
then **re-evaluates the `WHERE` clause against the new row version**. By that
point `status` is `'HELD'`, so they match zero rows and lose cleanly. This is
READ COMMITTED's re-check behaviour, and it is why no additional locking is
needed.

**There is deliberately no distributed lock.** A lock held outside the database —
in Redis, say — can be lost to a network partition or a long GC pause while its
holder still believes it owns the resource. A row lock held inside the same
transaction that performs the write cannot. Redis will appear in M2 as a *fast
path* for shedding doomed requests before they reach Postgres, which is a
throughput concern, not a correctness one. Correctness stays in the database.

As a backstop, a partial unique index makes a second live hold on one seat
physically impossible:

```sql
CREATE UNIQUE INDEX uq_one_active_hold_per_seat
    ON holds (seat_id) WHERE state = 'ACTIVE';
```

Even if every line of application logic were wrong, the database would refuse.

## The test

`SeatContentionTest` puts 500 virtual threads behind a `CountDownLatch`, releases
them at once against a single seat, and asserts:

- exactly **1** winner
- exactly **499** clean `SeatUnavailableException`s
- **0** unexpected exceptions
- the seat is `HELD` in the database
- exactly **1** hold row exists — losing transactions roll back completely, leaving
  no orphans

It runs against real Postgres via Testcontainers. Not H2, not a mock. The project
turns on exact Postgres locking semantics, and a database with different
concurrency behaviour would happily let broken code pass.

## Layout

```
inventory/   seats, holds, availability          (M1)
booking/     the hold -> pay -> confirm flow      (M1, grows in M3)
common/      shared errors and HTTP mapping
ledger/      double-entry accounts and transfers  (M4)
outbox/      transactional outbox + relay         (M4)
```

A modular monolith, not microservices — one deployable, hard module boundaries.
The seams are where the services would be cut if it ever needed splitting.
