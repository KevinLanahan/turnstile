package com.turnstile.inventory;

import com.turnstile.common.InventoryMetrics;
import com.turnstile.common.SeatUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Entry point for taking a hold on a seat.
 *
 * <p>Two layers, with very different jobs:
 *
 * <ol>
 *   <li>{@link SeatAvailabilityCache} - advisory, in Redis, deliberately not
 *       authoritative. It exists to reject hopeless requests cheaply. In a flash
 *       sale most requests are hopeless, and each one that reaches Postgres burns
 *       a pooled connection to be told no.</li>
 *   <li>{@link HoldWriter} - authoritative, in Postgres, inside a transaction.
 *       Nothing is true until this layer says so.</li>
 * </ol>
 *
 * <p>Note that this method is <b>not</b> {@code @Transactional}. That is the
 * point: the transaction, and with it a pooled database connection, must not be
 * acquired until after the fast path has had its say.
 */
@Service
public class InventoryService {

    private final SeatAvailabilityCache availability;
    private final HoldWriter holdWriter;
    private final InventoryMetrics metrics;
    private final Duration holdTtl;

    public InventoryService(SeatAvailabilityCache availability,
                            HoldWriter holdWriter,
                            InventoryMetrics metrics,
                            @Value("${turnstile.hold.ttl:PT5M}") Duration holdTtl) {
        this.availability = availability;
        this.holdWriter = holdWriter;
        this.metrics = metrics;
        this.holdTtl = holdTtl;
    }

    /**
     * @throws SeatUnavailableException if the seat is already spoken for
     */
    public Hold hold(UUID seatId, UUID userId, String idempotencyKey) {
        ClaimVerdict verdict = availability.tryClaim(seatId, idempotencyKey, holdTtl);

        if (verdict == ClaimVerdict.UNAVAILABLE) {
            metrics.recordFastPathRejection();
            throw new SeatUnavailableException(seatId);
        }

        // From here on this request owns a pooled database connection for the
        // duration of the transaction. That is the scarce resource the fast path
        // above exists to protect, so it is counted at this boundary.
        metrics.recordPostgresRequest();

        try {
            return holdWriter.claim(seatId, userId, idempotencyKey, holdTtl);
        } catch (RuntimeException ex) {
            // Postgres disagreed with the cache, or the write failed. Either way the
            // mark we may have just placed is not backed by a real hold, so drop it
            // rather than shedding legitimate traffic until its TTL expires.
            availability.release(seatId, idempotencyKey);
            throw ex;
        }
    }
}
