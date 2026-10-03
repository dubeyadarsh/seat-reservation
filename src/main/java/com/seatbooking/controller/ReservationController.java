package com.seatbooking.controller;

import com.seatbooking.dto.reservation.ReservationResponse;
import com.seatbooking.dto.reservation.ReserveOutcome;
import com.seatbooking.dto.reservation.ReserveRequest;
import com.seatbooking.exception.ApiException;
import com.seatbooking.security.AuthenticatedUser;
import com.seatbooking.service.ReservationService;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ReservationController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final ReservationService reservationService;

    /**
     * 201 for a new reservation, 200 when an idempotency key is replayed with the same body,
     * 409 for every decline. The caller's identity comes from the token, never from the body.
     */
    @PostMapping("/shows/{showId}/reserve")
    public ResponseEntity<ReservationResponse> reserve(
            @PathVariable UUID showId,
            @Valid @RequestBody ReserveRequest request,
            @Parameter(description = "Idempotency key; may be sent here or as idempotency_key in the body")
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String headerKey,
            @AuthenticationPrincipal AuthenticatedUser user) {
        ReserveOutcome outcome = reservationService.reserve(
                showId, user.userId(), request.seats(), idempotencyKey(headerKey, request.idempotencyKey()));

        ReservationResponse reservation = outcome.reservation();
        return outcome.replayed()
                ? ResponseEntity.ok(reservation)
                : ResponseEntity.created(URI.create("/reservations/" + reservation.reservationId())).body(reservation);
    }

    @PostMapping("/reservations/{reservationId}/cancel")
    public ReservationResponse cancel(
            @PathVariable UUID reservationId, @AuthenticationPrincipal AuthenticatedUser user) {
        return reservationService.cancel(reservationId, user.userId());
    }

    /** The header wins when both are sent, matching how proxies and SDKs usually attach it. */
    private static String idempotencyKey(String headerKey, String bodyKey) {
        String key = hasText(headerKey) ? headerKey.trim() : bodyKey;
        if (!hasText(key)) {
            throw ApiException.badRequest("missing_idempotency_key",
                    "Send an idempotency key in the " + IDEMPOTENCY_KEY_HEADER + " header or as idempotency_key");
        }
        return key.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
