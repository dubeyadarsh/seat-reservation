package com.seatbooking.service;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.seatbooking.cache.ShowCache;
import com.seatbooking.cache.SoldSeatCache;
import com.seatbooking.dto.reservation.ReservationResponse;
import com.seatbooking.dto.reservation.ReserveOutcome;
import com.seatbooking.exception.ApiException;
import com.seatbooking.model.RequestedSeat;
import com.seatbooking.model.Reservation;
import com.seatbooking.model.ReservationStatus;
import com.seatbooking.model.SeatStatus;
import com.seatbooking.model.Show;
import com.seatbooking.observability.ReservationMetrics;
import com.seatbooking.observability.ReservationMetrics.DeclineReason;
import com.seatbooking.repository.ReservationRepository;
import com.seatbooking.repository.SeatRepository;
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
import org.springframework.transaction.support.TransactionOperations;

/**
 * Booking is all-or-nothing (see DESIGN_DECISIONS.md): every requested seat is claimed, or none is
 * and the transaction rolls back. Declines are 409 domain outcomes, never server errors.
 *
 * <p>Work is ordered cheapest first, because in a burst almost every request loses: a seat already
 * sold to someone else is declined from memory, retries and taken seats are answered by plain reads,
 * and only a request that can still win opens a transaction. Caches and the confirmed counter are
 * updated after commit, so they never reflect a booking that rolled back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

    private static final String HASH_ALGORITHM = "SHA-256";

    private final ShowCache showCache;
    private final SoldSeatCache soldSeats;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final TransactionOperations transaction;
    private final ReservationMetrics metrics;

    public ReserveOutcome reserve(UUID showId, String userId, List<String> requestedSeats, String idempotencyKey) {
        List<String> seats = uniqueSeats(requestedSeats);
        List<String> soldToOthers = soldSeats.ownedByOthers(showId, userId, seats);
        if (!soldToOthers.isEmpty()) {
            throw seatTaken(soldToOthers);
        }

        Show show = showCache.find(showId).orElseThrow(() -> ApiException.notFound("No show with id " + showId));
        String requestHash = fingerprint(showId, seats);
        Optional<ReserveOutcome> replay = replayIfKeyUsed(userId, idempotencyKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        List<String> taken = takenSeats(showId, seats);
        if (!taken.isEmpty()) {
            // A parallel retry of this key may have just committed these seats; that is a replay, not a loss.
            return replayIfKeyUsed(userId, idempotencyKey, requestHash).orElseThrow(() -> seatTaken(taken));
        }

        ReserveOutcome outcome = transaction.execute(status -> claim(show, userId, seats, idempotencyKey, requestHash));
        if (!outcome.replayed()) {
            soldSeats.markSold(showId, userId, seats);
            metrics.recordConfirmed();
            log.info("reservation confirmed", kv("reservation_id", outcome.reservation().reservationId()),
                    kv("show_id", showId), kv("seats", seats));
        }
        return outcome;
    }

    /** Only the owner may cancel; cancelling twice returns the same answer instead of failing. */
    public ReservationResponse cancel(UUID reservationId, String userId) {
        Cancellation cancellation = transaction.execute(status -> cancelInTransaction(reservationId, userId));
        Reservation reservation = cancellation.reservation();
        if (cancellation.released()) {
            soldSeats.markReleased(reservation.showId(), reservation.seatLabels());
        }
        return ReservationResponse.from(reservation.cancelled());
    }

    /** The atomic part: per-user lock, exactly-once insert, limit check, then the conditional claim. */
    private ReserveOutcome claim(Show show, String userId, List<String> seats, String idempotencyKey,
                                 String requestHash) {
        // Taken before any row lock, so every transaction acquires locks in the same order.
        reservationRepository.lockUserShow(show.id(), userId);

        long amountPaise = show.pricePaise() * seats.size();
        Optional<UUID> reservationId = reservationRepository.insertIfAbsent(
                show.id(), userId, idempotencyKey, requestHash, seats, amountPaise);
        if (reservationId.isEmpty()) {
            return replayIfKeyUsed(userId, idempotencyKey, requestHash)
                    .orElseThrow(() -> ApiException.conflict("idempotency_conflict",
                            "This idempotency key is in use by another request"));
        }

        enforcePerUserLimit(show, userId, reservationId.get(), seats.size());
        claimOrRollBack(show.id(), seats, reservationId.get());
        return ReserveOutcome.created(new ReservationResponse(
                reservationId.get(), show.id(), userId, seats, amountPaise, ReservationStatus.CONFIRMED));
    }

    private Cancellation cancelInTransaction(UUID reservationId, String userId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .filter(found -> found.userId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("No reservation with id " + reservationId));

        // Guarded on CONFIRMED, so concurrent cancels release the seats exactly once.
        boolean released = reservation.status() == ReservationStatus.CONFIRMED
                && reservationRepository.cancel(reservationId) == 1;
        if (released) {
            int seatsReleased = seatRepository.releaseSeats(reservationId);
            log.info("reservation cancelled", kv("reservation_id", reservationId), kv("seats_released", seatsReleased));
        }
        return new Cancellation(reservation, released);
    }

    private Optional<ReserveOutcome> replayIfKeyUsed(String userId, String idempotencyKey, String requestHash) {
        return reservationRepository.findByIdempotencyKey(userId, idempotencyKey)
                .map(existing -> replayOf(existing, requestHash));
    }

    private ReserveOutcome replayOf(Reservation existing, String requestHash) {
        if (!existing.requestHash().equals(requestHash)) {
            metrics.recordDeclined(DeclineReason.KEY_REUSED);
            throw ApiException.conflict("idempotency_key_reused",
                    "This idempotency key was already used for a different show or seats");
        }
        metrics.recordDeclined(DeclineReason.IDEMPOTENT_REPLAY);
        return ReserveOutcome.replayed(ReservationResponse.from(existing));
    }

    /**
     * Lock-free pre-check: finds seats that are already gone without queueing for their row locks,
     * and teaches the sold-seat cache their committed owners so the next loser never gets this far.
     */
    private List<String> takenSeats(UUID showId, List<String> seats) {
        List<RequestedSeat> found = seatRepository.findRequestedSeats(showId, seats);
        if (found.size() != seats.size()) {
            Set<String> known = found.stream().map(RequestedSeat::label).collect(Collectors.toSet());
            throw ApiException.notFound("Unknown seats for this show: "
                    + join(seats.stream().filter(seat -> !known.contains(seat)).toList()));
        }
        List<RequestedSeat> taken = found.stream().filter(seat -> seat.status() != SeatStatus.AVAILABLE).toList();
        taken.stream()
                .filter(seat -> seat.ownerId() != null)
                .collect(Collectors.groupingBy(RequestedSeat::ownerId,
                        Collectors.mapping(RequestedSeat::label, Collectors.toList())))
                .forEach((owner, labels) -> soldSeats.markSold(showId, owner, labels));
        return taken.stream().map(RequestedSeat::label).toList();
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
            throw seatTaken(seats.stream().filter(seat -> !claimed.contains(seat)).toList());
        }
    }

    private ApiException seatTaken(List<String> seats) {
        metrics.recordDeclined(DeclineReason.SEAT_TAKEN);
        return ApiException.conflict("seat_taken", "Seats already taken: " + join(seats));
    }

    private static List<String> uniqueSeats(List<String> requestedSeats) {
        List<String> seats = List.copyOf(new LinkedHashSet<>(requestedSeats));
        if (seats.size() != requestedSeats.size()) {
            throw ApiException.badRequest("duplicate_seats", "Seat labels must be unique within a request");
        }
        return seats;
    }

    /** Identifies the request body behind an idempotency key, so a reused key with different seats is caught. */
    static String fingerprint(UUID showId, List<String> seats) {
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

    private record Cancellation(Reservation reservation, boolean released) {
    }
}
