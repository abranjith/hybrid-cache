package dev.shetty.internal;

/**
 * Additional flags that apply to a HybridCache operation.
 */
public enum HybridCacheEntryFlags {

    /**
     * No additional flags.
     */
    NONE,

    /**
     * Disables reading from the local in-process cache.
     */
    DISABLE_LOCAL_CACHE_READ,

    /**
     * Disables writing to the local in-process cache.
     */
    DISABLE_LOCAL_CACHE_WRITE,

    /**
     * Disables both reading from and writing to the local in-process cache.
     */
    //DISABLE_LOCAL_CACHE = EnumSet.of(DISABLE_LOCAL_CACHE_READ, DISABLE_LOCAL_CACHE_WRITE);

    /**
     * Disables reading from the secondary distributed cache.
     */
    DISABLE_DISTRIBUTED_CACHE_READ,

    /**
     * Disables writing to the secondary distributed cache.
     */
    DISABLE_DISTRIBUTED_CACHE_WRITE,

    /**
     * Disables both reading from and writing to the secondary distributed cache.
     */
    //DISABLE_DISTRIBUTED_CACHE = DISABLE_DISTRIBUTED_CACHE_READ | DISABLE_DISTRIBUTED_CACHE_WRITE;

    /**
     * Only fetches the value from cache; does not attempt to access the underlying data store.
     */
    DISABLE_UNDERLYING_DATA,

    /**
     * Disables compression for this payload.
     */
    DISABLE_COMPRESSION
}