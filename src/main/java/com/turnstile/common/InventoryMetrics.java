package com.turnstile.common;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters for the things that matter about inventory throughput.
 *
 * <p>The headline number for this project is deliberately not a latency figure.
 * Latency measured on a laptop, against Postgres inside a VM, says more about the
 * laptop than about the design. {@link #postgresRequests()} is a <em>count</em>:
 * how many hold requests reached Postgres at all, and therefore consumed one of a
 * finite number of pooled connections.
 *
 * <p>Under flash-sale load the overwhelming majority of requests are destined to
 * fail, and every one that reaches the database is a connection spent to say no.
 * Driving that count down is the entire purpose of the Redis fast path. A count
 * reproduces on any machine; a millisecond does not.
 *
 * <p>In a deployed service these would be Micrometer meters scraped by Prometheus.
 * Plain atomics keep the benchmark self-contained.
 */
@Component
public class InventoryMetrics {

    private final AtomicLong postgresRequests = new AtomicLong();
    private final AtomicLong fastPathRejections = new AtomicLong();

    /** One hold request got far enough to use a pooled database connection. */
    public void recordPostgresRequest() {
        postgresRequests.incrementAndGet();
    }

    /** One hold request was rejected before touching Postgres. */
    public void recordFastPathRejection() {
        fastPathRejections.incrementAndGet();
    }

    public long postgresRequests() {
        return postgresRequests.get();
    }

    public long fastPathRejections() {
        return fastPathRejections.get();
    }

    public void reset() {
        postgresRequests.set(0);
        fastPathRejections.set(0);
    }
}
