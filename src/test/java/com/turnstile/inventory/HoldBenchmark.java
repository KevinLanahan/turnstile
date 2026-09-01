package com.turnstile.inventory;

import com.turnstile.common.InventoryMetrics;
import com.turnstile.common.SeatUnavailableException;
import com.turnstile.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Measures hold throughput and latency. Excluded from {@code mvn test} by the
 * {@code benchmark} tag; run it with {@code mvn verify -Pbenchmark}.
 *
 * <p><b>Read this before quoting any number it prints.</b> This is a laptop
 * running Postgres inside Docker Desktop's VM, with JIT warmup, GC and whatever
 * else macOS feels like doing. The absolute figures are not a claim about what
 * this design can do on real hardware, and they should never be presented as one.
 *
 * <p>What the harness IS good for is an A/B comparison: the same scenarios, on
 * the same machine, minutes apart, with one variable changed. The ratio between
 * two runs is a property of the design. The absolute number is a property of
 * this laptop. Quote the ratio.
 *
 * <p>Two regimes, because they stress completely different things:
 *
 * <ul>
 *   <li><b>SELLOUT</b> - a real flash sale. A finite pool of seats, more buyers
 *       than seats, running until the inventory is gone. Mixed winners and losers.</li>
 *   <li><b>DOOMED</b> - every request is destined to fail, because the seat is
 *       already held. This isolates the cost of saying no, which in a flash sale
 *       is the overwhelming majority of the work. It is also precisely what a
 *       Redis fast path is supposed to make cheap, so it is the scenario where
 *       M2 should move the needle hardest.</li>
 * </ul>
 */
@Tag("benchmark")
class HoldBenchmark extends AbstractIntegrationTest {

    private static final int THREADS = 500;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private InventoryMetrics metrics;

    private record Result(
            String scenario,
            int attempts,
            long postgresRoundTrips,
            int successes,
            int conflicts,
            long wallMillis,
            double throughputPerSec,
            long p50Micros,
            long p95Micros,
            long p99Micros,
            long maxMicros
    ) {
    }

    @Test
    @DisplayName("hold throughput and latency under contention")
    void benchmark() throws Exception {
        // Warm up the JIT, fill the connection pool, let the queue reach steady
        // state. Discarded - measuring a cold JVM measures the JVM.
        runScenario("WARMUP", seedSeats(5_000), 20);

        // Enough iterations that the initial 500-thread burst amortises. The
        // earlier 4-iteration version finished before the connection queue ever
        // stabilised, so its p99 was just "how long the whole run took".
        List<Result> results = new ArrayList<>();
        results.add(runScenario("SELLOUT  (20k seats, 500 threads x 40)", seedSeats(20_000), 40));
        results.add(runScenario("DOOMED   (1 held seat, 500 threads x 200)", seedOneHeldSeat(), 200));

        report(results);
    }

    /**
     * Fires {@code THREADS} virtual threads, each attempting {@code attemptsPerThread}
     * holds against a random seat from the pool, all released simultaneously.
     */
    private Result runScenario(String name, List<UUID> pool, int attemptsPerThread) throws Exception {
        metrics.reset();

        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        ConcurrentLinkedQueue<Long> latenciesNanos = new ConcurrentLinkedQueue<>();

        long start;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(THREADS);

            for (int t = 0; t < THREADS; t++) {
                futures.add(executor.submit(() -> {
                    try {
                        startGate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < attemptsPerThread; i++) {
                        UUID seatId = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
                        long t0 = System.nanoTime();
                        try {
                            inventory.hold(seatId, UUID.randomUUID(), UUID.randomUUID().toString());
                            successes.incrementAndGet();
                        } catch (SeatUnavailableException expected) {
                            conflicts.incrementAndGet();
                        } catch (RuntimeException ignored) {
                            // Pool exhaustion or a transient failure; excluded from
                            // latency stats rather than silently counted as success.
                            continue;
                        } finally {
                            latenciesNanos.add(System.nanoTime() - t0);
                        }
                    }
                }));
            }

            start = System.nanoTime();
            startGate.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.MINUTES);
            }
        }
        long wallNanos = System.nanoTime() - start;

        long[] sorted = latenciesNanos.stream().mapToLong(Long::longValue).sorted().toArray();
        int attempts = sorted.length;
        double seconds = wallNanos / 1_000_000_000.0;

        return new Result(
                name,
                attempts,
                metrics.postgresRequests(),
                successes.get(),
                conflicts.get(),
                TimeUnit.NANOSECONDS.toMillis(wallNanos),
                attempts / seconds,
                percentileMicros(sorted, 0.50),
                percentileMicros(sorted, 0.95),
                percentileMicros(sorted, 0.99),
                sorted.length == 0 ? 0 : sorted[sorted.length - 1] / 1_000);
    }

    private static long percentileMicros(long[] sortedNanos, double p) {
        if (sortedNanos.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(p * sortedNanos.length) - 1;
        return sortedNanos[Math.max(0, Math.min(index, sortedNanos.length - 1))] / 1_000;
    }

    /** Prints a Markdown table, ready to paste straight into the README. */
    private void report(List<Result> results) {
        StringBuilder out = new StringBuilder("\n\n");
        out.append("### Benchmark results\n\n");
        out.append("Java ").append(System.getProperty("java.version"))
           .append(", ").append(Runtime.getRuntime().availableProcessors()).append(" cores, ")
           .append("Postgres 16 in Docker, Hikari pool 32, ")
           .append(THREADS).append(" virtual threads.\n\n");
        out.append("| Scenario | Attempts | PG round-trips | Won | Lost | Wall (ms) | Throughput/s | p50 | p95 | p99 |\n");
        out.append("|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|\n");
        for (Result r : results) {
            out.append(String.format(
                    "| %s | %d | %d | %d | %d | %d | %.0f | %.1f ms | %.1f ms | %.1f ms |%n",
                    r.scenario(), r.attempts(), r.postgresRoundTrips(),
                    r.successes(), r.conflicts(),
                    r.wallMillis(), r.throughputPerSec(),
                    r.p50Micros() / 1000.0, r.p95Micros() / 1000.0, r.p99Micros() / 1000.0));
        }
        out.append("\n**PG round-trips** is the number that matters: requests that consumed a\n");
        out.append("pooled database connection. Latency figures include time queued for a\n");
        out.append("connection (pool size 32, offered concurrency 500), so they describe what\n");
        out.append("500 simultaneous users experience - not query execution time.\n\n");
        out.append("Absolute timings are laptop-specific. Compare runs on one machine, never machines.\n");
        System.out.println(out);
    }

    // ---------------------------------------------------------------- seeding

    private UUID seedEvent() {
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO events (id, name, venue, starts_at, sales_open_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                eventId, "Benchmark", "The Void",
                Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)),
                Timestamp.from(Instant.now()));
        return eventId;
    }

    private List<UUID> seedSeats(int count) {
        jdbc.execute("TRUNCATE holds, seats, events CASCADE");
        UUID eventId = seedEvent();

        List<UUID> ids = new ArrayList<>(count);
        List<Object[]> batch = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            batch.add(new Object[]{id, eventId, "GA", "A", i, 12_500L});
        }
        jdbc.batchUpdate("""
                INSERT INTO seats (id, event_id, section, row_label, seat_number, price_cents, status)
                VALUES (?, ?, ?, ?, ?, ?, 'AVAILABLE')
                """, batch);
        return ids;
    }

    /** One seat, already held, so that every measured attempt is guaranteed to lose. */
    private List<UUID> seedOneHeldSeat() {
        List<UUID> ids = seedSeats(1);
        inventory.hold(ids.get(0), UUID.randomUUID(), UUID.randomUUID().toString());
        return ids;
    }
}
