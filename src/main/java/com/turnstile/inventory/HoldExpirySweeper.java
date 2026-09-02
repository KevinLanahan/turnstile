package com.turnstile.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Finds overdue holds and expires them, one transaction each.
 *
 * <p>Not itself scheduled - see {@code HoldExpiryScheduler}. Keeping the work and
 * the timer apart means tests can sweep on demand instead of sleeping, and means
 * a background timer is never racing a test that is trying to set one up.
 */
@Component
public class HoldExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(HoldExpirySweeper.class);

    private final HoldRepository holds;
    private final HoldExpirer expirer;
    private final SeatAvailabilityCache availability;
    private final int batchSize;

    public HoldExpirySweeper(HoldRepository holds,
                             HoldExpirer expirer,
                             SeatAvailabilityCache availability,
                             @Value("${turnstile.hold.sweep.batch-size:500}") int batchSize) {
        this.holds = holds;
        this.expirer = expirer;
        this.availability = availability;
        this.batchSize = batchSize;
    }

    /**
     * @return how many holds this sweep actually expired - which is not the same as
     *         how many it looked at, since a hold can be confirmed between being
     *         selected and being updated. Losing that race is normal.
     */
    public int sweep() {
        List<Hold> overdue = holds.findOverdue(batchSize);
        if (overdue.isEmpty()) {
            return 0;
        }

        int expired = 0;
        for (Hold hold : overdue) {
            if (expirer.expire(hold)) {
                expired++;
                // Only after the transaction has committed. Dropping the Redis mark
                // for a hold whose expiry then rolled back would let the fast path
                // wave through requests for a seat that is still held.
                availability.release(hold.seatId(), hold.idempotencyKey());
            }
        }

        if (expired > 0) {
            log.info("Expired {} of {} overdue holds", expired, overdue.size());
        }
        return expired;
    }
}
