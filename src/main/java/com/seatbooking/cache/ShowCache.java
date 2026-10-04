package com.seatbooking.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.seatbooking.config.CacheProperties;
import com.seatbooking.model.Show;
import com.seatbooking.repository.ShowRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Read-through cache (Cache-Aside pattern) for shows. Name, price and per-user limit never change
 * after creation, so an entry can never go stale and every reserve after the first skips a query.
 * Concurrent misses for one show collapse into a single load. Unknown ids are not cached, so a show
 * created on another instance is found as soon as it exists.
 */
@Component
public class ShowCache {

    private final ShowRepository showRepository;
    private final Cache<UUID, Show> shows;

    public ShowCache(ShowRepository showRepository, CacheProperties properties) {
        this.showRepository = showRepository;
        this.shows = Caffeine.newBuilder().maximumSize(properties.showMaxEntries()).build();
    }

    public Optional<Show> find(UUID showId) {
        return Optional.ofNullable(shows.get(showId, id -> showRepository.findShow(id).orElse(null)));
    }
}
