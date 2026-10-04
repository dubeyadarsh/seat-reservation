package com.seatbooking.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.seatbooking.config.CacheProperties;
import org.springframework.stereotype.Component;

/**
 * Idempotency keys this instance has seen stored. The sold-seat fast path consults it so a reused
 * key is never short-circuited as "seat taken": it reaches the replay check and gets its exact answer
 * (200 replay or 409 idempotency_key_reused). Keys of declined requests are never stored, so they are
 * never here, and the fast path keeps answering the storm from memory.
 */
@Component
public class UsedKeyCache {

    private final Cache<UsedKey, Boolean> keys;

    public UsedKeyCache(CacheProperties properties) {
        this.keys = Caffeine.newBuilder().maximumSize(properties.usedKeyMaxEntries()).build();
    }

    public boolean contains(String userId, String idempotencyKey) {
        return keys.getIfPresent(new UsedKey(userId, idempotencyKey)) != null;
    }

    /** Call only once the key is known to be committed. */
    public void add(String userId, String idempotencyKey) {
        keys.put(new UsedKey(userId, idempotencyKey), Boolean.TRUE);
    }

    private record UsedKey(String userId, String idempotencyKey) {
    }
}
