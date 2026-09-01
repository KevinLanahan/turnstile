package com.turnstile.booking;

import com.turnstile.inventory.Hold;
import com.turnstile.inventory.InventoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    public HoldController(InventoryService inventory) {
        this.inventory = inventory;
    }

    public record HoldRequest(@NotNull UUID seatId, @NotNull UUID userId) {
    }

    public record HoldResponse(UUID holdId, UUID seatId, Instant expiresAt) {
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
}
