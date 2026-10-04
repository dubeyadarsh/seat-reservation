package com.seatbooking.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.seatbooking.config.CacheProperties;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Who owns each confirmed seat, so a hot-seat loser is declined from memory without a database
 * connection. In a burst almost every request is such a loser, so this removes nearly all database
 * work from the storm.
 *
 * <p>It is a hint, never the source of truth. Entries are written only for seats the database has
 * already committed as confirmed, so the cache cannot decline a seat nobody holds, except for at most
 * the TTL after a cancel on a different instance (cancels on this instance evict immediately). A
 * missing entry just falls through to the database, whose conditional update decides every win.
 * A seat the caller already owns is never declined here, so their idempotent retry still reaches
 * the replay logic.
 */
@Component
public class SoldSeatCache {

    private final Cache<SeatKey, String> owners;

    public SoldSeatCache(CacheProperties properties) {
        this.owners = Caffeine.newBuilder()
                .maximumSize(properties.soldSeatMaxEntries())
                .expireAfterWrite(properties.soldSeatTtl())
                .build();
    }

    /** Requested seats known to be confirmed to someone other than {@code userId}. */
    public List<String> ownedByOthers(UUID showId, String userId, List<String> seats) {
        return seats.stream()
                .filter(seat -> {
                    String owner = owners.getIfPresent(new SeatKey(showId, seat));
                    return owner != null && !owner.equals(userId);
                })
                .toList();
    }

    /** Call only after the confirmation is committed. */
    public void markSold(UUID showId, String ownerId, Collection<String> seats) {
        seats.forEach(seat -> owners.put(new SeatKey(showId, seat), ownerId));
    }

    /** Call only after the release is committed. */
    public void markReleased(UUID showId, Collection<String> seats) {
        owners.invalidateAll(seats.stream().map(seat -> new SeatKey(showId, seat)).toList());
    }

    private record SeatKey(UUID showId, String label) {
    }
}
