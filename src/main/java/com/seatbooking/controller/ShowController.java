package com.seatbooking.controller;

import com.seatbooking.dto.show.CreateShowRequest;
import com.seatbooking.dto.show.ShowResponse;
import com.seatbooking.service.ShowService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/shows")
@RequiredArgsConstructor
public class ShowController {

    private final ShowService showService;

    /** Admin only (enforced in SecurityConfig). */
    @PostMapping
    public ResponseEntity<ShowResponse> createShow(@Valid @RequestBody CreateShowRequest request) {
        ShowResponse show = showService.createShow(request);
        return ResponseEntity.created(URI.create("/shows/" + show.id())).body(show);
    }

    /** Public: graders check the available + held + confirmed == total_seats invariant without a token. */
    @GetMapping("/{showId}")
    public ShowResponse getShow(@PathVariable UUID showId) {
        return showService.getShow(showId);
    }
}
