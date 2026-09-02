package com.turnstile.chaos;

import com.turnstile.common.SeatUnavailableException;
import com.turnstile.inventory.InventoryService;
import com.turnstile.inventory.SeatAvailabilityCache;
import com.turnstile.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens when Redis goes away mid-flight.
 *
 * <p>The fast path added in M2 is an optimisation, not a source of truth, and the
 * whole design rests on that distinction. A cache that failed CLOSED would turn a
 * Redis outage into a total outage: every seat would look unavailable and the
 * system would reject legitimate customers while a perfectly healthy database sat
 * idle. Failing OPEN means an outage costs throughput and nothing else.
 *
 * <p>The broken cache is supplied as a {@code @Primary} bean, so the real
 * {@link InventoryService} - the same one production wires - runs the whole booking
 * path with a dead cache underneath it. Spring gives this class its own application
 * context, so the outage is scoped here and cannot leak into other test classes.
 *
 * <p><b>How the fault is injected:</b> at the Redis client boundary rather than by
 * severing a real network connection with something like Toxiproxy. The assertions
 * are identical either way, because what is under test is this system's reaction
 * rather than Lettuce's. A network-level version would be more faithful and is the
 * obvious upgrade.
 */
@Import(RedisOutageChaosTest.DeadRedisConfig.class)
class RedisOutageChaosTest extends AbstractIntegrationTest {

    private static final int CONTENDERS = 200;

    /** A Redis client that is comprehensively down. */
    static class DeadRedisTemplate extends StringRedisTemplate {
        @Override
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            throw new RedisConnectionFailureException("Redis is down");
        }
    }

    @TestConfiguration
    static class DeadRedisConfig {
        @Bean
        @Primary
        SeatAvailabilityCache deadCache() {
            return new SeatAvailabilityCache(new DeadRedisTemplate());
        }
    }

    @Autowired
    private InventoryService inventory;

    @Autowired
    private SeatAvailabilityCache cache;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID eventId;

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE consumed_events, outbox CASCADE");
        jdbc.execute("TRUNCATE holds, seats, events CASCADE");
        jdbc.execute("TRUNCATE ledger_entries, transfers, accounts CASCADE");

        eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO events (id, name, venue, starts_at, sales_open_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                eventId, "Redis Outage", "The Void",
                Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)),
                Timestamp.from(Instant.now()));
    }

    @Test
    @DisplayName("with Redis down, every legitimate booking still succeeds")
    void bookingsSucceedWithRedisDown() {
        long failOpensBefore = cache.failOpenCount();

        int booked = 0;
        for (int i = 0; i < 50; i++) {
            inventory.hold(seat(i, 5_000L), UUID.randomUUID(), UUID.randomUUID().toString());
            booked++;
        }

        assertThat(booked)
                .as("a Redis outage must never reject a customer the database would accept")
                .isEqualTo(50);
        assertThat(cache.failOpenCount() - failOpensBefore)
                .as("and every one of them went through the fail-open path")
                .isEqualTo(50);
    }

    @Test
    @DisplayName("with Redis down, contention is still resolved correctly")
    void noOversellWithRedisDown() throws Exception {
        UUID seatId = seat(999, 12_500L);

        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < CONTENDERS; i++) {
                futures.add(executor.submit(() -> {
                    try {
                        gate.await();
                        inventory.hold(seatId, UUID.randomUUID(), UUID.randomUUID().toString());
                        wins.incrementAndGet();
                    } catch (SeatUnavailableException expected) {
                        conflicts.incrementAndGet();
                    } catch (Throwable t) {
                        unexpected.add(t);
                    }
                }));
            }
            gate.countDown();
            for (Future<?> f : futures) {
                f.get(120, TimeUnit.SECONDS);
            }
        }

        // THE POINT. Correctness never depended on Redis. With the cache entirely
        // gone, the conditional UPDATE in Postgres still admits exactly one winner -
        // which is the whole argument for keeping correctness in the database and
        // treating the cache as a performance detail.
        assertThat(unexpected).isEmpty();
        assertThat(wins.get())
                .as("exactly one winner, with no working cache at all")
                .isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(CONTENDERS - 1);
        assertThat(jdbc.queryForObject("SELECT status FROM seats WHERE id = ?", String.class, seatId))
                .isEqualTo("HELD");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM holds WHERE seat_id = ? AND state = 'ACTIVE'",
                Integer.class, seatId))
                .isEqualTo(1);
    }

    private UUID seat(int number, long priceCents) {
        UUID seatId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO seats (id, event_id, section, row_label, seat_number, price_cents, status)
                VALUES (?, ?, 'ORCH', 'A', ?, ?, 'AVAILABLE')
                """, seatId, eventId, number, priceCents);
        return seatId;
    }
}
