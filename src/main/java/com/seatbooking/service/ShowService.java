package com.seatbooking.service;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.seatbooking.cache.ShowCache;
import com.seatbooking.dto.show.CreateShowRequest;
import com.seatbooking.dto.show.ShowResponse;
import com.seatbooking.exception.ApiException;
import com.seatbooking.model.Seat;
import com.seatbooking.model.Show;
import com.seatbooking.repository.SeatRepository;
import com.seatbooking.repository.ShowRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShowService {

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ShowCache showCache;

    /** Show and seats are written in one transaction, so a show never exists with only some of its seats. */
    @Transactional
    public ShowResponse createShow(CreateShowRequest request) {
        List<String> labels = request.seats();
        rejectDuplicateLabels(labels);

        Show show = showRepository.insertShow(
                request.name(), request.pricePaise(), request.perUserLimitOrDefault(), labels.size());
        seatRepository.insertSeats(show.id(), labels);
        log.info("show created", kv("show_id", show.id()), kv("total_seats", show.totalSeats()));

        return ShowResponse.from(show, labels.stream().map(Seat::available).toList());
    }

    /** Seat statuses and counts always come from one read, so the response reconciles with itself. */
    @Transactional(readOnly = true)
    public ShowResponse getShow(UUID showId) {
        Show show = showCache.find(showId)
                .orElseThrow(() -> ApiException.notFound("No show with id " + showId));
        return ShowResponse.from(show, seatRepository.findSeatsByShow(showId));
    }

    private static void rejectDuplicateLabels(List<String> labels) {
        Set<String> seen = new HashSet<>();
        List<String> duplicates = labels.stream().filter(label -> !seen.add(label)).distinct().toList();
        if (!duplicates.isEmpty()) {
            throw ApiException.badRequest("duplicate_seats",
                    "Seat labels must be unique; duplicated: " + String.join(", ", duplicates));
        }
    }
}
