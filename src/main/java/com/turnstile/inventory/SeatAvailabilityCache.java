package com.turnstile.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A load-shedding fast path in front of Postgres.
 *
 * <p><b>This is not a lock.</b> It cannot be, and it is important to be precise
 * about why. A lock held outside the database can be lost to a network partition
 * or a long GC pause while its holder still believes it owns the resource - the
 * standard critique of Redlock. Turnstile therefore keeps correctness where the
 * write happens: the conditional UPDATE in {@link SeatRepository#tryHold}. What
 * this cache does is cheaper and safer to get wrong - it answers "is it even
 * worth asking Postgres?" and lets the ~99% of doomed flash-sale requests fail
 * without consuming one of a finite number of pooled connections.
 *
 * <p>Because it is advisory, every failure mode is designed to <b>fail open</b>.
 * If Redis is slow, unreachable, or returns nonsense, the request proceeds to
 * Postgres exactly as it would have without any cache. A Redis outage costs
 * throughput. It can never cost correctness, and it can never wrongly reject a
 * request forever.
 *
 * <h2>Why Lua</h2>
 *
 * <p>A bare {@code SET NX} is already atomic and would need no scripting. It is
 * also wrong here, because it cannot tell a competing request apart from a
 * <em>retry of the same request</em>: a client replaying its idempotency key
 * would be rejected from its own hold. Distinguishing them requires reading the
 * current value, comparing it, and conditionally setting - three steps that must
 * not interleave with another client. Redis executes a script atomically on its
 * single command loop, which is exactly that guarantee.
 *
 * <p>Release has the same shape. {@code GET} followed by {@code DEL} is racy: the
 * mark can expire and be re-taken by someone else between the two calls, and the
 * {@code DEL} would then delete a stranger's mark. Compare-and-delete in one
 * script closes that window.
 */
@Component
public class SeatAvailabilityCache {

    private static final Logger log = LoggerFactory.getLogger(SeatAvailabilityCache.class);

    private static final String KEY_PREFIX = "turnstile:seat:";

    /**
     * ARGV[1] = idempotency key of the caller, ARGV[2] = mark TTL in millis.
     * Returns 1 = claimed, 2 = replay by the same request, 0 = held by someone else.
     */
    private static final RedisScript<Long> CLAIM = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current == false then
                redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
                return 1
            elseif current == ARGV[1] then
                return 2
            else
                return 0
            end
            """, Long.class);

    /** Compare-and-delete. Returns 1 if this caller's mark was removed, else 0. */
    private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            else
                return 0
            end
            """, Long.class);

    private final StringRedisTemplate redis;

    /**
     * Fail-open events since startup. Used to keep logging sane: the failure mode
     * this class is built for is "Redis is unhealthy", which means failures arrive
     * in the tens of thousands, not ones. Logging a stack trace per occurrence
     * would put the logging pipeline on the floor alongside Redis - the outage
     * would be made worse by the code written to survive it.
     */
    private final AtomicLong failOpenCount = new AtomicLong();

    public SeatAvailabilityCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Asks whether it is worth going to Postgres for this seat.
     *
     * @return {@link ClaimVerdict#UNAVAILABLE} only when Redis is confident someone
     *         else holds the seat. Any doubt - including any Redis failure - yields
     *         {@link ClaimVerdict#CLAIMED} so the request proceeds to the database.
     */
    public ClaimVerdict tryClaim(UUID seatId, String idempotencyKey, Duration ttl) {
        try {
            Long result = redis.execute(
                    CLAIM,
                    List.of(key(seatId)),
                    idempotencyKey,
                    Long.toString(ttl.toMillis()));

            if (result == null) {
                return ClaimVerdict.CLAIMED;
            }
            return switch (result.intValue()) {
                case 0 -> ClaimVerdict.UNAVAILABLE;
                case 2 -> ClaimVerdict.REPLAY;
                default -> ClaimVerdict.CLAIMED;
            };
        } catch (RuntimeException ex) {
            // Fail open. Postgres remains the authority; we have simply lost the
            // optimisation for this request.
            noteFailOpen("claim", seatId, ex);
            return ClaimVerdict.CLAIMED;
        }
    }

    /**
     * Removes this caller's mark, if it is still theirs. Safe to call
     * unconditionally: a mark owned by someone else, or already expired, is left
     * untouched.
     */
    public void release(UUID seatId, String idempotencyKey) {
        try {
            redis.execute(RELEASE, List.of(key(seatId)), idempotencyKey);
        } catch (RuntimeException ex) {
            // The mark carries a TTL, so the worst case is that this seat keeps
            // shedding requests until it expires - and Postgres was always going
            // to give the correct answer anyway.
            noteFailOpen("release", seatId, ex);
        }
    }

    /**
     * Logs the first failure in full, then one summary line per thousand.
     *
     * <p>Deliberately not one line per failure. Under a Redis outage every request
     * takes this path, so per-occurrence logging turns a degraded-but-working
     * system into an unusable one - which would defeat the entire point of failing
     * open. The counter is what tells you the scale; the first stack trace is what
     * tells you the cause.
     */
    private void noteFailOpen(String operation, UUID seatId, RuntimeException ex) {
        long occurrences = failOpenCount.incrementAndGet();
        if (occurrences == 1) {
            log.warn("Redis fast path failed ({}) for seat {}; falling through to Postgres. "
                    + "Further occurrences will be summarised.", operation, seatId, ex);
        } else if (occurrences % 1_000 == 0) {
            log.warn("Redis fast path still degraded: {} fail-open events so far.", occurrences);
        }
    }

    /** Fail-open events observed since startup. */
    public long failOpenCount() {
        return failOpenCount.get();
    }

    private static String key(UUID seatId) {
        return KEY_PREFIX + seatId;
    }
}
