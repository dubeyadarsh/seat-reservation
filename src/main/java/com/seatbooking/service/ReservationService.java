package com.seatbooking.service;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.seatbooking.dto.reservation.ReservationResponse;
import com.seatbooking.dto.reservation.ReserveOutcome;
import com.seatbooking.exception.ApiException;
import com.seatbooking.model.Reservation;
import com.seatbooking.model.ReservationStatus;
import com.seatbooking.model.Seat;
import com.seatbooking.model.SeatStatus;
import com.seatbooking.model.Show;
import com.seatbooking.observability.ReservationMetrics;
import com.seatbooking.observability.ReservationMetrics.DeclineReason;
import com.seatbooking.repository.ReservationRepository;
import com.seatbooking.repository.SeatRepository;
import com.seatbooking.repository.ShowRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Booking is all-or-nothing (see DESIGN_DECISIONS.md): every requested seat is claimed, or none is
 * and the transaction rolls back. Declines are 409 domain outcomes, never server errors.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

    private static final String HASH_ALGORITHM = "SHA-256";

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationMetrics metrics;

    @Transactional
    public ReserveOutcome reserve(UUID showId, String userId, List<String> requestedSeats, String idempotencyKey) {
        Show show = showRepository.findShow(showId)
                .orElseThrow(() -> ApiException.notFound("No show with id " + showId));
        List<String> seats = uniqueSeats(requestedSeats);

        // Taken before any row lock, so every transaction acquires locks in the same order.
        reservationRepository.lockUserShow(showId, userId);

        String requestHash = fingerprint(showId, seats);
        long amountPaise = show.pricePaise() * seats.size();
        Optional<UUID> reservationId = reservationRepository.insertIfAbsent(
                showId, userId, idempotencyKey, requestHash, seats, amountPaise);
        if (reservationId.isEmpty()) {
            return replayOf(userId, idempotencyKey, requestHash);
        }

        rejectUnknownOrTakenSeats(showId, seats);
        enforcePerUserLimit(show, userId, reservationId.get(), seats.size());
        claimOrRollBack(showId, seats, reservationId.get());

        metrics.recordConfirmed();
        log.info("reservation confirmed",
                kv("reservation_id", reservationId.get()), kv("show_id", showId), kv("seats", seats));
        return ReserveOutcome.created(new ReservationResponse(
                reservationId.get(), showId, userId, seats, amountPaise, ReservationStatus.CONFIRMED));
    }

    /** Only the owner may cancel; cancelling twice returns the same answer instead of failing. */
    @Transactional
    public ReservationResponse cancel(UUID reservationId, String userId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .filter(found -> found.userId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("No reservation with id " + reservationId));

        // Guarded on CONFIRMED, so concurrent cancels release the seats exactly once.
        if (reservation.status() == ReservationStatus.CONFIRMED && reservationRepository.cancel(reservationId) == 1) {
            int released = seatRepository.releaseSeats(reservationId);
            log.info("reservation cancelled", kv("reservation_id", reservationId), kv("seats_released", released));
        }
        return ReservationResponse.from(reservation.cancelled());
    }

    private ReserveOutcome replayOf(String userId, String idempotencyKey, String requestHash) {
        Reservation existing = reservationRepository.findByIdempotencyKey(userId, idempotencyKey)
                .orElseThrow(() -> ApiException.conflict("idempotency_conflict",
                        "This idempotency key is in use by another request"));
        if (!existing.requestHash().equals(requestHash)) {
            metrics.recordDeclined(DeclineReason.KEY_REUSED);
            throw ApiException.conflict("idempotency_key_reused",
                    "This idempotency key was already used for a different show or seats");
        }
        metrics.recordDeclined(DeclineReason.IDEMPOTENT_REPLAY);
        return ReserveOutcome.replayed(ReservationResponse.from(existing));
    }

    /** Lock-free pre-check: declines a seat that is already gone without queueing for its row lock. */
    private void rejectUnknownOrTakenSeats(UUID showId, List<String> seats) {
        List<Seat> found = seatRepository.findRequestedSeats(showId, seats);
        if (found.size() != seats.size()) {
            Set<String> known = found.stream().map(Seat::label).collect(Collectors.toSet());
            throw ApiException.notFound("Unknown seats for this show: "
                    + join(seats.stream().filter(seat -> !known.contains(seat)).toList()));
        }
        List<String> taken = found.stream()
                .filter(seat -> seat.status() != SeatStatus.AVAILABLE)
                .map(Seat::label)
                .toList();
        if (!taken.isEmpty()) {
            declineAsSeatTaken(taken);
        }
    }

    private void enforcePerUserLimit(Show show, String userId, UUID reservationId, int requested) {
        long alreadyHeld = reservationRepository.countSeatsHeldByUser(show.id(), userId, reservationId);
        if (alreadyHeld + requested > show.perUserLimit()) {
            metrics.recordDeclined(DeclineReason.PER_USER_LIMIT);
            throw ApiException.conflict("per_user_limit", "This show allows %d seats per user; you already have %d"
                    .formatted(show.perUserLimit(), alreadyHeld));
        }
    }

    private void claimOrRollBack(UUID showId, List<String> seats, UUID reservationId) {
        List<String> claimed = seatRepository.claimSeats(showId, seats, reservationId);
        if (claimed.size() != seats.size()) {
            // Someone won the race between the pre-check and the lock; roll the whole request back.
            declineAsSeatTaken(seats.stream().filter(seat -> !claimed.contains(seat)).toList());
        }
    }

    private void declineAsSeatTaken(List<String> seats) {
        metrics.recordDeclined(DeclineReason.SEAT_TAKEN);
        throw ApiException.conflict("seat_taken", "Seats already taken: " + join(seats));
    }

    private static List<String> uniqueSeats(List<String> requestedSeats) {
        List<String> seats = List.copyOf(new LinkedHashSet<>(requestedSeats));
        if (seats.size() != requestedSeats.size()) {
            throw ApiException.badRequest("duplicate_seats", "Seat labels must be unique within a request");
        }
        return seats;
    }

    /** Identifies the request body behind an idempotency key, so a reused key with different seats is caught. */
    private static String fingerprint(UUID showId, List<String> seats) {
        String canonical = showId + "|" + String.join(",", seats.stream().sorted().toList());
        try {
            byte[] hash = MessageDigest.getInstance(HASH_ALGORITHM).digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(HASH_ALGORITHM + " is required but unavailable", e);
        }
    }

    private static String join(List<String> seats) {
        return String.join(", ", seats);
    }
}
