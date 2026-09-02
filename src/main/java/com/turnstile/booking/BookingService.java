package com.turnstile.booking;

import com.turnstile.common.HoldNoLongerActiveException;
import com.turnstile.common.HoldNotFoundException;
import com.turnstile.inventory.Hold;
import com.turnstile.inventory.HoldRepository;
import com.turnstile.inventory.Seat;
import com.turnstile.inventory.SeatRepository;
import com.turnstile.ledger.LedgerService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.turnstile.outbox.OutboxRepository;
import com.turnstile.ledger.Transfer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private static final String CURRENCY = "USD";

    private final HoldRepository holds;
    private final SeatRepository seats;
    private final LedgerService ledger;
    private final OutboxRepository outbox;
    private final ObjectMapper json;

    public BookingService(HoldRepository holds, SeatRepository seats,
                          LedgerService ledger, OutboxRepository outbox, ObjectMapper json) {
        this.holds = holds;
        this.seats = seats;
        this.ledger = ledger;
        this.outbox = outbox;
        this.json = json;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // Serialising a map of strings and a long cannot fail. If it somehow
            // does, failing the booking is correct - an event we cannot describe
            // is an event we should not promise to deliver.
            throw new IllegalStateException("Could not serialise outbox payload", e);
        }
    }

    /**
     * The result of a successful confirmation: a booked seat and the transfer that
     * paid for it.
     */
    public record Booking(Hold hold, Transfer transfer, long amountCents) {
    }

    /**
     * Turns a live hold into a booked seat and the money that paid for it.
     *
     * <p><b>One transaction, three things.</b> Confirming the hold, booking the
     * seat, and posting the ledger transfer all commit together or not at all.
     * That is the property worth defending: there is no interleaving in which a
     * customer is charged for a seat they did not get, or receives a seat nobody
     * was charged for. Split these across transactions and such a window exists,
     * however small, and at scale a small window is a daily occurrence.
     *
     * <p>Ordering within the transaction still matters. The hold is confirmed
     * FIRST, because that row is what this operation and the expiry sweeper
     * contend for; winning it decides the race before any money moves.
     *
     * @throws HoldNotFoundException       if no such hold exists
     * @throws HoldNoLongerActiveException if the sweeper got there first
     */
    @Transactional
    public Booking confirm(UUID holdId) {
        Hold hold = holds.findById(holdId).orElseThrow(() -> new HoldNotFoundException(holdId));

        // 1. Win the hold row, or lose the race cleanly. Nothing below runs if this
        //    fails, so a lost race never touches money.
        if (holds.tryConfirm(holdId) == 0) {
            throw new HoldNoLongerActiveException(holdId);
        }

        Seat seat = seats.findById(hold.seatId()).orElseThrow(() ->
                new IllegalStateException("Hold " + holdId + " references missing seat " + hold.seatId()));

        // 2. Book the seat, guarded on hold_id so this cannot book a seat that has
        //    been released and re-claimed by someone else.
        if (seats.book(hold.seatId(), holdId) == 0) {
            throw new IllegalStateException(
                    "Hold " + holdId + " was confirmed but seat " + hold.seatId()
                    + " is not held by it - inventory has drifted");
        }

        // 3. Move the money. The hold's idempotency key is reused as the transfer's,
        //    so a retried confirmation settles against the same transfer instead of
        //    charging twice.
        Transfer transfer = ledger.recordPurchase(
                hold.userId(),
                seat.eventId(),
                seat.priceCents(),
                CURRENCY,
                "hold:" + holdId,
                "Seat " + seat.rowLabel() + seat.seatNumber() + " (" + seat.section() + ")");

        // 4. Announce it - into our own database, in this same transaction. If
        //    anything above rolls back, this row goes with it, so we can never tell
        //    the outside world about a booking that did not happen. The relay
        //    publishes it once this commits.
        outbox.append(hold.seatId(), "SeatBooked", toJson(Map.of(
                "holdId", holdId.toString(),
                "seatId", hold.seatId().toString(),
                "userId", hold.userId().toString(),
                "eventId", seat.eventId().toString(),
                "transferId", transfer.id().toString(),
                "amountCents", seat.priceCents())));

        log.debug("Hold {} confirmed, seat {} booked, transfer {} posted for {} cents",
                holdId, hold.seatId(), transfer.id(), seat.priceCents());

        Hold confirmed = holds.findById(holdId).orElseThrow(() -> new HoldNotFoundException(holdId));
        return new Booking(confirmed, transfer, seat.priceCents());
    }
}
