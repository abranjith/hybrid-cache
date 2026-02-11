package dev.shetty.abstractions;

import dev.shetty.internal.CacheEntryOptions;

/**
 * Represents a distributed cache of serialized values.
 */
public interface IDistributedCache {
    /**
     * Gets a value with the given key.
     * @param key A string identifying the requested value.
     * @return The located value or null.
     */
    byte[] get(String key);

    /**
     * Sets a value with the given key.
     * @param key A string identifying the requested value.
     * @param value The value to set in the cache.
     * @param options The cache options for the value.
     */
    void set(String key, byte[] value, CacheEntryOptions options);

    /**
     * Refreshes a value in the cache based on its key, resetting its sliding expiration timeout (if any).
     * @param key A string identifying the requested value.
     */
    void refresh(String key);

    /**
     * Removes the value with the given key.
     * @param key A string identifying the requested value.
     */
    void remove(String key);
}