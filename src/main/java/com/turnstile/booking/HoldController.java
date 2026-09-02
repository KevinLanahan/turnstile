package com.turnstile.booking;

import com.turnstile.inventory.Hold;
import com.turnstile.inventory.InventoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    public HoldController(InventoryService inventory, BookingService booking) {
        this.inventory = inventory;
        this.booking = booking;
    }

    public record HoldRequest(@NotNull UUID seatId, @NotNull UUID userId) {
    }

    public record HoldResponse(UUID holdId, UUID seatId, Instant expiresAt) {
    }

    public record ConfirmResponse(UUID holdId, UUID seatId, String state) {
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
        Hold confirmed = booking.confirm(holdId);
        return new ConfirmResponse(confirmed.id(), confirmed.seatId(), confirmed.state().name());
    }
}
