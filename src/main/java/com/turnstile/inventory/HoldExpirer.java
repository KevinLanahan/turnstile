package com.turnstile.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class HoldExpirer {

    private static final Logger log = LoggerFactory.getLogger(HoldExpirer.class);

    private final HoldRepository holds;
    private final SeatRepository seats;

    public HoldExpirer(HoldRepository holds, SeatRepository seats) {
        this.holds = holds;
        this.seats = seats;
    }

    /**
     * Expires one overdue hold and returns its seat to the pool.
     *
     * <p>One transaction per hold, deliberately. Sweeping a thousand holds in a
     * single transaction would hold a thousand row locks for its duration - blocking
     * live buyers on every one of those seats - and a single failure would roll back
     * the whole batch.
     *
     * <p>Mirror image of {@code BookingService.confirm}: expire the hold FIRST, since
     * that row is what decides the race, then release the seat. A hold that was
     * confirmed a microsecond ago matches nothing here and its seat is left alone.
     *
     * @return true if this call expired the hold, false if it lost the race or the
     *         hold was not actually due
     */
    @Transactional
    public boolean expire(Hold hold) {
        if (holds.tryExpire(hold.id()) == 0) {
            return false;
        }

        int released = seats.release(hold.seatId(), hold.id());
        if (released == 0) {
            // The seat is no longer held by this hold. Nothing to release, and no
            // reason to fail - the hold is correctly marked dead either way.
            log.debug("Expired hold {} but seat {} was not held by it", hold.id(), hold.seatId());
        }
        return true;
    }
}
