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
| M1 | Atomic seat claim, 500-thread contention test, repository contract tests | done |
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

### Troubleshooting: "Could not find a valid Docker environment"

Testcontainers talks to `/var/run/docker.sock` directly. Recent Docker Desktop
releases ship with *Allow the default Docker socket to be used* turned off, so
that path exists but is not wired to the engine — it answers with an empty
HTTP 400, while the `docker` CLI works fine because it follows the Docker
context instead.

Either enable **Settings -> Advanced -> Allow the default Docker socket to be
used**, or point the JVM at the real socket:

```bash
export DOCKER_HOST=unix://$HOME/.docker/run/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

The second variable is needed because Ryuk (the Testcontainers cleanup sidecar)
mounts the socket into its own container.

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

Two test classes, deliberately aimed at different seams.

**`SeatContentionTest`** — the system-level property. 500 virtual threads behind a
`CountDownLatch`, released at once against a single seat, asserting:

- exactly **1** winner
- exactly **499** clean `SeatUnavailableException`s
- **0** unexpected exceptions
- the seat is `HELD` in the database
- exactly **1** hold row exists — losing transactions roll back completely, leaving
  no orphans

**`SeatRepositoryTest`** — the mechanism-level property, and the reason it exists
is worth stating plainly. Delete the `AND status = 'AVAILABLE'` guard from
`tryHold` and `SeatContentionTest` *still passes*: the partial unique index
rejects the surplus holds, the service maps those rejections to the same 409 the
test counts, and the oversell never happens. Two independent safety layers, and
the system-level test cannot tell which one saved it.

So `SeatRepositoryTest` calls the repository directly, where no index can cover
for a missing guard, and asserts the query's contract: a seat that is not
`AVAILABLE` must match zero rows. With the guard removed that test goes red
immediately — verified, not assumed.

The general lesson: **defence in depth makes systems safer and tests weaker.**
Each layer needs a test at its own seam.

Both run against real Postgres via Testcontainers. Not H2, not a mock. The project
turns on exact Postgres locking semantics, and a database with different
concurrency behaviour would happily let broken code pass.

## Performance baseline (M2, database only)

Measured before Redis existed, so the M2 comparison has something honest to be
measured against. Run it yourself with `mvn test -Pbenchmark`.

Java 25, 8 cores, Postgres 16 in Docker, Hikari pool 32, 500 virtual threads.

| Scenario | Attempts | PG requests | Won | Lost | Wall (ms) | Throughput/s | p50 | p95 | p99 |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| SELLOUT (20k seats, 500 threads x 40) | 20,000 | 20,000 | 12,616 | 7,384 | 10,764 | 1,858 | 19.3 ms | 1271.3 ms | 2539.0 ms |
| DOOMED (1 held seat, 500 threads x 200) | 100,000 | 100,000 | 0 | 100,000 | 41,412 | 2,415 | 11.9 ms | 991.9 ms | 2658.7 ms |

**Read the PG requests column, not the milliseconds.** It is one-to-one with
attempts: every request, including the 100,000 that were doomed from the start,
consumed one of 32 pooled database connections in order to be told no. That is
the waste the Redis fast path targets, and because it is a count rather than a
duration it reproduces on any machine. The timings do not - they belong to this
laptop.

The latency distribution is worth a note of its own: p50 of 11.9 ms against a p99
of 2.66 s. That spread is not the database. It is HikariCP's thread-local
connection cache under 500-way oversubscription of a 32-connection pool - a thread
that has just released a connection frequently reacquires it immediately, while
others starve at the back of the queue. The pool is fast, not fair.

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
