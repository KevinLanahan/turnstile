package com.turnstile.booking;

import com.turnstile.common.HoldNoLongerActiveException;
import com.turnstile.common.HoldNotFoundException;
import com.turnstile.inventory.Hold;
import com.turnstile.inventory.HoldRepository;
import com.turnstile.inventory.SeatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final HoldRepository holds;
    private final SeatRepository seats;

    public BookingService(HoldRepository holds, SeatRepository seats) {
        this.holds = holds;
        this.seats = seats;
    }

    /**
     * Turns a live hold into a booked seat.
     *
     * <p><b>Ordering is the whole design.</b> The hold is confirmed FIRST, because
     * the hold row is what this operation and the expiry sweeper both contend for.
     * Winning that row decides the race; the seat update is then a formality that
     * happens inside the same transaction and rolls back with it.
     *
     * <p>Doing it the other way round - book the seat, then confirm the hold -
     * would leave a window in which the sweeper could expire the hold and release
     * a seat that had just been sold.
     *
     * @throws HoldNotFoundException        if no such hold exists
     * @throws HoldNoLongerActiveException  if the sweeper got there first, or it was
     *                                      already confirmed
     */
    @Transactional
    public Hold confirm(UUID holdId) {
        Hold hold = holds.findById(holdId).orElseThrow(() -> new HoldNotFoundException(holdId));

        // 1. Win the hold row, or lose the race cleanly.
        if (holds.tryConfirm(holdId) == 0) {
            throw new HoldNoLongerActiveException(holdId);
        }

        // 2. Book the seat. Guarded on hold_id, so this cannot book a seat that has
        //    been released and re-claimed. If it ever matches zero rows the holds
        //    and seats tables have drifted apart, and failing loudly (rolling back
        //    the confirmation above) is far better than selling a seat we do not own.
        if (seats.book(hold.seatId(), holdId) == 0) {
            throw new IllegalStateException(
                    "Hold " + holdId + " was confirmed but seat " + hold.seatId()
                    + " is not held by it - inventory has drifted");
        }

        log.debug("Hold {} confirmed, seat {} booked", holdId, hold.seatId());
        return holds.findById(holdId).orElseThrow(() -> new HoldNotFoundException(holdId));
    }
}
