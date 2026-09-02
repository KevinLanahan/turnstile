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
| M2 | Redis fast path (Lua), before/after benchmark | done |
| M3 | Hold expiry, state machine, confirm-vs-expire race test | done |
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

## The expiry race (M3)

Holds expire after five minutes so an abandoned checkout does not remove a seat
from sale forever. That creates the nastiest correctness problem in the project:
a payment confirming at the exact millisecond the sweeper decides the hold is dead.

Done naively you either charge a card and then hand the seat to somebody else, or
release a seat that has just been paid for. Both produce a support ticket rather
than a stack trace, which is the worst kind of bug.

**The race is arbitrated on the hold, not the seat.** Confirmation and expiry are
both conditional updates against the same `holds` row:

```sql
-- confirm
UPDATE holds SET state = 'CONFIRMED'
 WHERE id = ? AND state = 'ACTIVE' AND expires_at >  now();

-- expire
UPDATE holds SET state = 'EXPIRED'
 WHERE id = ? AND state = 'ACTIVE' AND expires_at <= now();
```

Postgres serialises them on that row's lock, so whichever arrives second
re-evaluates `state = 'ACTIVE'` against the committed result of the first and
matches nothing. Same mechanism as the seat claim in M1, pointed at a different
row. Only after winning the hold does either side touch the seat, inside the same
transaction, so a loser changes nothing at all.

`now()` is deliberately evaluated by Postgres rather than by the application. Two
app servers with skewed clocks would otherwise disagree about whether a hold is
dead, and the seat would be both sold and released.

### Three legal outcomes, not two

`HoldExpiryRaceTest` runs 200 races with the deadline placed before, after, and
exactly at the moment both operations fire. It deliberately does **not** assert
that exactly one side wins, because that is false:

1. **Confirm wins** - hold `CONFIRMED`, seat `BOOKED`.
2. **Expiry wins** - hold `EXPIRED`, seat `AVAILABLE`.
3. **Neither wins** - the sweeper ran a hair early (hold not yet due, matches
   nothing) *and* the confirmation landed a hair late (deadline passed, matches
   nothing). The hold is untouched, stays `ACTIVE`, and the next sweep collects it.

The third case is real, correct, and easy to miss. A test asserting
exactly-one-winner would fail intermittently against a perfectly healthy system.

What the test actually asserts is the invariant that must never break: **hold state
and seat status always agree.** A `CONFIRMED` hold whose seat is `AVAILABLE`, or an
`EXPIRED` hold whose seat is `BOOKED`, means money and inventory have diverged. It
also asserts both winners are reachable across the run, so the suite can never
quietly stop exercising one branch.

### The sweeper

One transaction per hold, not one per batch. Sweeping a thousand holds in a single
transaction would hold a thousand row locks for its duration - blocking live buyers
on every one of those seats - and one failure would roll back the lot. The Redis
availability mark is dropped only after the expiring transaction commits; dropping
it first would let the fast path wave through requests for a seat whose expiry then
rolled back.

## Performance: what the Redis fast path bought (M2)

Both runs used the same harness on the same laptop, minutes apart, with one
variable changed. Reproduce with `mvn test -Pbenchmark`.

Java 25, 8 cores, Postgres 16 and Redis 7 in Docker, Hikari pool 32, 500 virtual
threads.

**Before — Postgres only**

| Scenario | Attempts | PG requests | Won | Lost | Wall (ms) | Throughput/s | p50 | p95 | p99 |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| SELLOUT (20k seats, 500 x 40) | 20,000 | 20,000 | 12,616 | 7,384 | 10,764 | 1,858 | 19.3 ms | 1271.3 ms | 2539.0 ms |
| DOOMED (1 held seat, 500 x 200) | 100,000 | 100,000 | 0 | 100,000 | 41,412 | 2,415 | 11.9 ms | 991.9 ms | 2658.7 ms |

**After — Redis fast path in front**

| Scenario | Attempts | PG requests | Shed by Redis | Fail-opens | Won | Lost | Wall (ms) | Throughput/s | p50 | p95 | p99 |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| SELLOUT (20k seats, 500 x 40) | 20,000 | 12,654 | 7,346 | 0 | 12,654 | 7,346 | 12,948 | 1,545 | 294.4 ms | 925.2 ms | 1478.8 ms |
| DOOMED (1 held seat, 500 x 200) | 100,000 | **0** | 100,000 | 0 | 0 | 100,000 | 2,790 | 35,838 | 6.4 ms | 46.2 ms | 131.1 ms |

### What this actually shows

**Under pure contention the fast path is transformative.** All 100,000 doomed
requests were rejected without touching Postgres at all - not fewer round trips,
none. Throughput rose 14.8x (2,415 to 35,838/s) and p99 fell from 2.66 s to
131 ms. The database was never the bottleneck for those requests; the connection
pool was, and requests that never ask for a connection never queue for one.

**Under a sellout it is a net loss on throughput, and that is not a bug.**
Throughput fell 17% and median latency went from 19 ms to 294 ms. In SELLOUT
about 63% of requests are winners who must reach Postgres regardless, so they now
pay a Redis round trip *on top of* the full database cost. A cache only pays for
itself on the requests it can reject.

Note that the tail still improved even there - p95 down 27%, p99 down 42% -
because the 37% that were shed stopped competing for connections. Median gets
worse, tail gets better. Both are true at once.

**So the fast path is a bet on the doom ratio.** It is the right bet for a ticket
on-sale, where a popular event has thousands of people chasing each seat and
almost every request is hopeless. It is the wrong bet for steady-state browsing
of a half-empty venue. A production version would enable it per event, or adapt
to the observed rejection rate, rather than applying it unconditionally as this
one does.

### On the numbers

The **PG requests** and **shed** columns are counts, so they reproduce on any
machine. The millisecond figures do not - they belong to this laptop, running
Postgres and Redis inside Docker Desktop's VM, and they include time queued for a
connection (pool size 32, offered concurrency 500). Quote the ratio, never the
absolute.

**Fail-opens must be 0 for a run to count.** A non-zero value means Redis was
unhealthy and requests fell through to Postgres, which would make the shed column
understate a working fast path. The column exists because an early run silently
did exactly that - a 200 ms Redis command timeout under 500-way concurrency
produced a flood of timeouts, every one of them correctly failing open, and the
resulting table looked entirely plausible.

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
