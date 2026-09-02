package com.turnstile.booking;

import com.turnstile.inventory.Hold;
import com.turnstile.common.HoldNotFoundException;
import com.turnstile.inventory.HoldRepository;
import com.turnstile.inventory.InventoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/holds")
public class HoldController {

    private final InventoryService inventory;
    private final BookingService booking;
    private final HoldRepository holds;

    public HoldController(InventoryService inventory, BookingService booking, HoldRepository holds) {
        this.inventory = inventory;
        this.booking = booking;
        this.holds = holds;
    }

    public record HoldRequest(@NotNull UUID seatId, @NotNull UUID userId) {
    }

    public record HoldResponse(UUID holdId, UUID seatId, Instant expiresAt) {
    }

    public record ConfirmResponse(
            UUID holdId, UUID seatId, String state,
            UUID transferId, long amountCents) {
    }

    public record HoldStatus(UUID holdId, UUID seatId, String state, Instant expiresAt, long secondsRemaining) {
    }

    /**
     * 201 if you got the seat, 409 if you did not. Under a flash sale the 409
     * is the normal case and must stay cheap.
     */
    @PostMapping
    public ResponseEntity<HoldResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody HoldRequest request) {

        Hold hold = inventory.hold(request.seatId(), request.userId(), idempotencyKey);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(new HoldResponse(hold.id(), hold.seatId(), hold.expiresAt()));
    }

    /**
     * 200 if the seat is now booked, 409 if the hold died first.
     */
    @PostMapping("/{holdId}/confirm")
    public ConfirmResponse confirm(@PathVariable UUID holdId) {
        BookingService.Booking result = booking.confirm(holdId);
        return new ConfirmResponse(
                result.hold().id(),
                result.hold().seatId(),
                result.hold().state().name(),
                result.transfer().id(),
                result.amountCents());
    }

    /**
     * Hold status, including how long is left. Note that {@code secondsRemaining}
     * is advisory only - it is a hint for rendering a countdown, not permission to
     * confirm. Whether a hold is still live is decided by Postgres at the moment
     * the confirmation actually runs, which is the only clock that matters.
     */
    @GetMapping("/{holdId}")
    public HoldStatus status(@PathVariable UUID holdId) {
        Hold hold = holds.findById(holdId).orElseThrow(() -> new HoldNotFoundException(holdId));
        long remaining = Math.max(0, java.time.Duration.between(Instant.now(), hold.expiresAt()).toSeconds());
        return new HoldStatus(hold.id(), hold.seatId(), hold.state().name(), hold.expiresAt(), remaining);
    }
}
