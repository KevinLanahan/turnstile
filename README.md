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
| M4a | Double-entry ledger, balanced transfers, refunds | done |
| M4b | Transactional outbox, idempotent consumer, reconciliation | done |
| M4c | Fake payment gateway and payment state machine | planned |

The demo UI and the reconciliation job were both pulled forward out of later
milestones, so those rows are smaller than they look.
| M5a | Chaos tests: relay crash, Redis outage | done |
| M5b | k6 end-to-end load test | planned |
| M6 | Architecture diagram, README graphs (seat-map UI landed early, see below) | planned |

See [DESIGN.md](DESIGN.md) for the full design and rationale.

## Running it

Requires **JDK 21** and **Docker** (the test suite starts real Postgres containers).

Postgres is published on host port **5434**, not 5432, because 5432 is so often
already taken. Set `TURNSTILE_PG_PORT` to move it - the same variable is read by
`docker-compose.yml` and by the application.

```bash
# infrastructure - check both containers actually came up
docker compose up -d
docker compose ps

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

## Chaos tests (M5a)

Two dependencies, deliberately broken, with the system asserted to degrade rather
than fail. These are the tests that turn design claims into evidence.

### The relay dies at the worst possible moment

There is one unavoidable window in the outbox pattern: the relay publishes an
event, then dies before it can record that it did. On restart it publishes again.
Closing that window would require a distributed transaction across the database
and the broker - the exact thing the outbox exists to avoid.

So the design does not prevent duplicates, it makes them harmless.
`RelayCrashChaosTest` forces the window open on **every single event**:

| | Result |
|---|--:|
| Bookings | 40 |
| Forced crashes after publish | 40 |
| Events lost | **0** |
| Deliveries handed to the consumer | 120 |
| Side effects performed | **40** |

Three deliveries per event, one confirmation per customer. A consumer without the
`consumed_events` claim would have sent all 120.

### Redis disappears mid-flight

The M2 fast path is an optimisation, not a source of truth, and everything rests
on that distinction. A cache that failed **closed** would turn a Redis outage into
a total outage - every seat looking unavailable, legitimate customers rejected,
while a perfectly healthy database sat idle.

`RedisOutageChaosTest` supplies a comprehensively dead Redis as a `@Primary` bean,
so the real booking path runs with no cache underneath it:

| | Result |
|---|--:|
| Bookings attempted with Redis down | 50 |
| Bookings that succeeded | **50** |
| Requests that fell through to Postgres | 50 |
| Threads contending for one seat | 200 |
| Winners | **1** |
| Oversells | **0** |

The second half is the one that matters. **With the cache entirely gone, 200
concurrent threads still produce exactly one winner** - because correctness never
lived in Redis. It lives in a conditional `UPDATE` in Postgres, and the cache was
only ever saving connections.

An outage costs throughput. It does not cost correctness, and it does not reject a
customer the database would have accepted.

### How the faults are injected

At the client boundary - a publisher that throws after delivering, a Redis template
that errors - rather than by severing real network connections with something like
Toxiproxy. The assertions are identical, because what is under test is this
system's reaction rather than the driver's. A network-level version would be more
faithful and is the obvious next step, listed here rather than glossed over.

### What these tests found

Writing them surfaced a real bug: `OutboxRelay` logged a full stack trace on every
publish failure. Forty forced crashes produced forty stack traces, and a genuine
broker outage would have produced one per event per retry pass indefinitely -
taking the log pipeline down alongside the broker. It now logs the first failure in
full and one summary line per hundred, matching the Redis fast path, which had the
identical bug found the identical way.

## The outbox and reconciliation (M4b)

### Telling another system what happened

The oldest problem in distributed systems: you must change your database *and*
tell something else about it, and they are different systems so you cannot do both
atomically.

| Approach | Failure |
|---|---|
| Write DB, then publish | Crash in between and the event is lost forever |
| Publish, then write DB | Crash in between and you announced something that never happened |
| Two-phase commit | A cure worse than the disease |

The outbox sidesteps it. The event is written to **this** database in the **same
transaction** as the business change - so if the booking commits the event exists,
and if the booking rolls back the event does too. A separate relay then reads the
table and publishes.

`OutboxTest.aFailedBookingWritesNoEvent` is the test that matters: it kills a hold
so confirmation loses the race, and asserts the outbox is empty afterwards. A
"write then publish" design would already have told the world about a booking that
never happened.

### At-least-once, not exactly-once

The relay publishes, then marks the row delivered - two statements, with a gap. A
crash in that gap means the event is published again on restart. **That window
cannot be closed**; closing it is the distributed transaction we were avoiding in
the first place.

So delivery is at-least-once and the consumer is made idempotent instead.
`BookingNotifier` claims the event id and performs its side effect in one
transaction:

```sql
INSERT INTO consumed_events (consumer, event_id)
VALUES (?, ?) ON CONFLICT (consumer, event_id) DO NOTHING
```

A single atomic test-and-set: the first delivery inserts one row, every redelivery
conflicts and gets zero. Checking "have I seen this?" and then inserting would be
two statements with a gap, and two threads handed the same redelivered event would
both slip through it.

**At-least-once delivery plus an idempotent consumer is what people mean when they
say exactly-once.** There is no exactly-once delivery; there is only
exactly-once *effect*.

### Reconciliation

Every invariant reconciliation checks is already enforced by a constraint, a
trigger, or a transaction boundary. Checking again is not redundancy for its own
sake: enforcement gets bypassed, and **the failure mode of a ledger is silence.**
Nothing crashes, the numbers are just wrong, and nobody finds out until someone
counts. This is the thing that counts. Banks run it nightly and treat a non-empty
report as an incident.

`ReconciliationTest` proves it works by breaking things on purpose - the only way
such a job is worth testing. A reconciler that has only ever seen healthy data is
not evidence of anything.

Writing those tests turned up something worth recording. The first attempt to
inject a global imbalance **failed, because Postgres refused to let it happen** -
the M4a balance trigger caught it at commit. Which makes the arithmetic obvious: if
every transfer balances, the global sum is necessarily zero, so a global imbalance
is *unreachable* while that trigger holds. The check stays anyway, because triggers
get disabled for bulk loads, skipped by logical replication, or dropped by an
unreviewed migration - and the test now simulates exactly that by disabling the
trigger before injecting the drift.

That is the same lesson as M1's seat guard, arrived at from the other direction:
**defence in depth makes systems safer and tests weaker.** Test each layer at its
own seam, and be explicit about which failure each one actually defends against.

### Known weakness

`checkBookedSeatsMatchSales` compares global *counts* rather than joining each
booking to its transfer, because there is no foreign key between them - the only
link is the `"hold:{id}"` idempotency-key convention. So reconciliation can tell you
the totals disagree but not **which** booking is wrong, which is exactly what you
want at 2am. The fix is a real column on `transfers` pointing at the hold. It is
listed here rather than quietly left out.

## The ledger (M4a)

Confirming a hold books the seat *and* moves the money, in one transaction. There
is no interleaving in which a customer is charged for a seat they did not get, or
walks away with a seat nobody was charged for.

Three rules, all enforced by Postgres rather than by Java, because application code
is where bugs live and this is the part where a bug is measured in dollars.

### 1. Every transfer balances

Money is never created or destroyed, only moved. A purchase debits the customer and
credits the event by the same amount, and the entries sum to zero.

This cannot be a `CHECK` constraint - a `CHECK` sees one row, and balance is a
property of a *set* of rows. It is a **deferred constraint trigger** instead,
running at `COMMIT` rather than per statement:

```sql
CREATE CONSTRAINT TRIGGER trg_ledger_entries_must_balance
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_transfer_balances();
```

Deferred matters. Real posting code writes legs one statement at a time, so the
books only have to balance at the end. The payoff is that code writing one side of
a transfer does not produce a subtly wrong balance to be discovered in an audit
months later - it fails to commit. `LedgerInvariantsTest` writes exactly that
half-a-transfer and asserts the database refuses it.

Balance is checked per currency, since a transfer moving +100 USD and -100 EUR sums
to zero and is nonsense.

### 2. Entries are append-only

`UPDATE` and `DELETE` on `ledger_entries` are blocked by triggers. A ledger you can
edit is a spreadsheet, not a ledger. Mistakes are corrected by posting a reversing
transfer, which leaves both the error and the correction visible - that is what
makes it an audit trail. A transfer can be reversed at most once, enforced by a
partial unique index on `reverses_id`.

*Caveat worth knowing:* `TRUNCATE` does not fire row-level `DELETE` triggers, so it
slips past this. In a real deployment the application role simply would not hold
`TRUNCATE` on the table. The tests use it deliberately for cleanup.

### 3. Balances are derived, never stored

There is no `balance` column anywhere. Balances come from summing entries through
the `account_balances` view, so there is nothing that can drift out of sync with the
entries that produced it. The seat map's revenue figure is read from that view - if
it ever disagrees with what was actually sold, the entries are wrong and you want to
know.

### Money is `BIGINT` cents

Never a float. `0.1 + 0.2` is not `0.3`, and a ledger is the last place to discover
that.

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
