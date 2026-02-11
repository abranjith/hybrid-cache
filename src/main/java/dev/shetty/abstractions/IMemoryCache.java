package dev.shetty.abstractions;

import java.time.Duration;
import java.util.Optional;

/**
 * Represents an in-process memory cache.
 */
public interface IMemoryCache {
    /**
     * Gets the item associated with this key if present.
     * @param key An object identifying the requested entry.
     * @return An Optional containing the located value, or empty if not found.
     */
    Optional<Object> get(Object key);

    /**
     * Create or overwrite an entry in the cache.
     * @param key An object identifying the entry.
     * @return The newly created ICacheEntry instance.
     */
    ICacheEntry set(Object key);

    /**
     * Create or overwrite an entry in the cache with a value and expiration.
     * @param key An object identifying the entry.
     * @param value The value to cache.
     * @param absoluteExpirationRelativeToNow The expiration duration relative to now.
     */
    void set(Object key, Object value, Duration absoluteExpirationRelativeToNow);

    /**
     * Removes the object associated with the given key.
     * @param key An object identifying the entry.
     */
    void remove(Object key);
}
