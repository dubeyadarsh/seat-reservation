package com.seatbooking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.seatbooking.dto.reservation.ReserveOutcome;
import com.seatbooking.exception.ApiException;
import com.seatbooking.model.Reservation;
import com.seatbooking.model.ReservationStatus;
import com.seatbooking.model.Seat;
import com.seatbooking.model.SeatStatus;
import com.seatbooking.model.Show;
import com.seatbooking.observability.ReservationMetrics;
import com.seatbooking.repository.ReservationRepository;
import com.seatbooking.repository.SeatRepository;
import com.seatbooking.repository.ShowRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

/** Losers and retries must be answered from reads alone; only a plausible winner takes the lock and writes. */
class ReservationServiceTest {

    private static final long PRICE_PAISE = 25_000L;
    private static final int PER_USER_LIMIT = 4;
    private static final UUID SHOW_ID = UUID.randomUUID();
    private static final UUID RESERVATION_ID = UUID.randomUUID();
    private static final String USER = "alice";
    private static final String KEY = "k1";
    private static final List<String> SEATS = List.of("A1");

    private final ShowRepository showRepository = mock(ShowRepository.class);
    private final SeatRepository seatRepository = mock(SeatRepository.class);
    private final ReservationRepository reservationRepository = mock(ReservationRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ReservationService service = new ReservationService(
            showRepository, seatRepository, reservationRepository, new ReservationMetrics(registry));

    @BeforeEach
    void showExists() {
        when(showRepository.findShow(SHOW_ID))
                .thenReturn(Optional.of(new Show(SHOW_ID, "friday-night", PRICE_PAISE, PER_USER_LIMIT, 3)));
    }

    @Test
    void hotSeatLoserIsDeclinedWithoutLockingOrWriting() {
        when(reservationRepository.findByIdempotencyKey(USER, KEY)).thenReturn(Optional.empty());
        when(seatRepository.findRequestedSeats(SHOW_ID, SEATS)).thenReturn(List.of(seat("A1", SeatStatus.CONFIRMED)));

        assertThatThrownBy(() -> service.reserve(SHOW_ID, USER, SEATS, KEY))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT)
                .hasFieldOrPropertyWithValue("code", "seat_taken");
        assertNoLockOrWrite();
        assertThat(declined("seat_taken")).isEqualTo(1);
    }

    @Test
    void retryIsReplayedBeforeAnySeatCheck() {
        when(reservationRepository.findByIdempotencyKey(USER, KEY)).thenReturn(Optional.of(existing(SEATS)));

        ReserveOutcome outcome = service.reserve(SHOW_ID, USER, SEATS, KEY);

        assertThat(outcome.replayed()).isTrue();
        assertThat(outcome.reservation().reservationId()).isEqualTo(RESERVATION_ID);
        verify(seatRepository, never()).findRequestedSeats(any(), anyList());
        assertNoLockOrWrite();
        assertThat(declined("idempotent_replay")).isEqualTo(1);
    }

    @Test
    void retryThatFindsItsOwnParallelAttemptJustCommittedIsReplayedNotDeclined() {
        when(reservationRepository.findByIdempotencyKey(USER, KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing(SEATS)));
        when(seatRepository.findRequestedSeats(SHOW_ID, SEATS)).thenReturn(List.of(seat("A1", SeatStatus.CONFIRMED)));

        ReserveOutcome outcome = service.reserve(SHOW_ID, USER, SEATS, KEY);

        assertThat(outcome.replayed()).isTrue();
        assertNoLockOrWrite();
        assertThat(declined("seat_taken")).isZero();
    }

    @Test
    void reusedKeyWithDifferentSeatsIsRejectedWithoutWriting() {
        when(reservationRepository.findByIdempotencyKey(USER, KEY)).thenReturn(Optional.of(existing(List.of("A2"))));

        assertThatThrownBy(() -> service.reserve(SHOW_ID, USER, SEATS, KEY))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "idempotency_key_reused");
        assertNoLockOrWrite();
        assertThat(declined("key_reused")).isEqualTo(1);
    }

    @Test
    void plausibleWinnerLocksBeforeWritingAndClaims() {
        when(reservationRepository.findByIdempotencyKey(USER, KEY)).thenReturn(Optional.empty());
        when(seatRepository.findRequestedSeats(SHOW_ID, SEATS)).thenReturn(List.of(seat("A1", SeatStatus.AVAILABLE)));
        when(reservationRepository.insertIfAbsent(SHOW_ID, USER, KEY, ReservationService.fingerprint(SHOW_ID, SEATS),
                SEATS, PRICE_PAISE)).thenReturn(Optional.of(RESERVATION_ID));
        when(seatRepository.claimSeats(SHOW_ID, SEATS, RESERVATION_ID)).thenReturn(SEATS);

        ReserveOutcome outcome = service.reserve(SHOW_ID, USER, SEATS, KEY);

        assertThat(outcome.replayed()).isFalse();
        assertThat(outcome.reservation().amountPaise()).isEqualTo(PRICE_PAISE);
        InOrder order = inOrder(reservationRepository, seatRepository);
        order.verify(reservationRepository).lockUserShow(SHOW_ID, USER);
        order.verify(reservationRepository).insertIfAbsent(any(), anyString(), anyString(), anyString(), anyList(),
                anyLong());
        order.verify(seatRepository).claimSeats(SHOW_ID, SEATS, RESERVATION_ID);
        assertThat(registry.get("reservations.confirmed").counter().count()).isEqualTo(1);
    }

    private void assertNoLockOrWrite() {
        verify(reservationRepository, never()).lockUserShow(any(), anyString());
        verify(reservationRepository, never()).insertIfAbsent(any(), anyString(), anyString(), anyString(), anyList(),
                anyLong());
        verify(seatRepository, never()).claimSeats(any(), anyList(), any());
    }

    private double declined(String reason) {
        return registry.get("reservations.declined").tag("reason", reason).counter().count();
    }

    private static Seat seat(String label, SeatStatus status) {
        return new Seat(label, status);
    }

    private static Reservation existing(List<String> seats) {
        return new Reservation(RESERVATION_ID, SHOW_ID, USER, ReservationService.fingerprint(SHOW_ID, seats), seats,
                PRICE_PAISE * seats.size(), ReservationStatus.CONFIRMED);
    }
}
