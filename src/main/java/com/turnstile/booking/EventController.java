package com.turnstile.booking;

import com.turnstile.common.EventNotFoundException;
import com.turnstile.inventory.Event;
import com.turnstile.inventory.EventRepository;
import com.turnstile.inventory.Seat;
import com.turnstile.inventory.SeatRepository;
import com.turnstile.ledger.AccountKind;
import com.turnstile.ledger.LedgerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The read path. Browsing availability is by far the highest-volume operation in a
 * ticketing system - orders of magnitude more people look at a seat map than buy
 * from it - and it deliberately touches nothing that can block a writer.
 */
@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventRepository events;
    private final SeatRepository seats;
    private final LedgerService ledger;

    public EventController(EventRepository events, SeatRepository seats, LedgerService ledger) {
        this.events = events;
        this.seats = seats;
        this.ledger = ledger;
    }

    public record EventSummary(
            UUID id, String name, String venue, Instant startsAt,
            long seatCount, long availableCount) {
    }

    public record SeatView(
            UUID id, String section, String row, int number,
            long priceCents, String status) {
    }

    public record SeatMap(
            UUID eventId, String name, String venue,
            long revenueCents, List<SeatView> seats) {
    }

    @GetMapping
    public List<EventSummary> list() {
        return events.findAll().stream()
                .map(e -> new EventSummary(
                        e.id(), e.name(), e.venue(), e.startsAt(),
                        events.countSeats(e.id()),
                        events.countAvailableSeats(e.id())))
                .toList();
    }

    @GetMapping("/{eventId}/seats")
    public SeatMap seatMap(@PathVariable UUID eventId) {
        Event event = events.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));

        List<SeatView> view = seats.findByEvent(eventId).stream()
                .map(seat -> new SeatView(
                        seat.id(), seat.section(), seat.rowLabel(), seat.seatNumber(),
                        seat.priceCents(), seat.status().name()))
                .toList();

        // Read straight off the ledger, not off a stored total. If this number is
        // wrong, the entries are wrong, and that is exactly what we want to see.
        long revenue = ledger.balanceOf(AccountKind.EVENT_REVENUE, eventId, "USD");

        return new SeatMap(event.id(), event.name(), event.venue(), revenue, view);
    }
}
