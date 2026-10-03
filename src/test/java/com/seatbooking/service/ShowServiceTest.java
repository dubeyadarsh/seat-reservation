package com.seatbooking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.seatbooking.dto.show.CreateShowRequest;
import com.seatbooking.dto.show.SeatCounts;
import com.seatbooking.dto.show.ShowResponse;
import com.seatbooking.exception.ApiException;
import com.seatbooking.model.Seat;
import com.seatbooking.model.SeatStatus;
import com.seatbooking.model.Show;
import com.seatbooking.repository.SeatRepository;
import com.seatbooking.repository.ShowRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ShowServiceTest {

    private static final long PRICE_PAISE = 25_000L;
    private static final UUID SHOW_ID = UUID.randomUUID();

    private final ShowRepository repository = mock(ShowRepository.class);
    private final SeatRepository seatRepository = mock(SeatRepository.class);
    private final ShowService service = new ShowService(repository, seatRepository);

    @Test
    void createsShowWithEverySeatAvailableInRequestOrder() {
        List<String> labels = List.of("B2", "A1", "C3");
        when(repository.insertShow("friday-night", PRICE_PAISE, 4, 3))
                .thenReturn(new Show(SHOW_ID, "friday-night", PRICE_PAISE, 4, 3));

        ShowResponse response = service.createShow(new CreateShowRequest("friday-night", labels, PRICE_PAISE, null));

        assertThat(response.id()).isEqualTo(SHOW_ID);
        assertThat(response.perUserLimit()).isEqualTo(CreateShowRequest.DEFAULT_PER_USER_LIMIT);
        assertThat(response.totalSeats()).isEqualTo(3);
        assertThat(response.seats()).extracting(Seat::label).containsExactly("B2", "A1", "C3");
        assertThat(response.seats()).extracting(Seat::status).containsOnly(SeatStatus.AVAILABLE);
        assertThat(response.counts()).isEqualTo(new SeatCounts(3, 0, 0));
        verify(seatRepository).insertSeats(SHOW_ID, labels);
    }

    @Test
    void usesRequestedPerUserLimit() {
        when(repository.insertShow("matinee", PRICE_PAISE, 2, 1))
                .thenReturn(new Show(SHOW_ID, "matinee", PRICE_PAISE, 2, 1));

        ShowResponse response = service.createShow(new CreateShowRequest("matinee", List.of("A1"), PRICE_PAISE, 2));

        assertThat(response.perUserLimit()).isEqualTo(2);
    }

    @Test
    void rejectsDuplicateSeatLabelsWithoutTouchingTheDatabase() {
        CreateShowRequest request = new CreateShowRequest("dup", List.of("A1", "A2", "A1"), PRICE_PAISE, null);

        assertThatThrownBy(() -> service.createShow(request))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
                .hasFieldOrPropertyWithValue("code", "duplicate_seats")
                .hasMessageContaining("A1");
        verify(repository, never()).insertShow(anyString(), anyLong(), anyInt(), anyInt());
    }
}
