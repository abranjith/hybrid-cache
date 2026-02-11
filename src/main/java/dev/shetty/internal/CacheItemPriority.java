package dev.shetty.internal;

/**
 * Specifies how items are prioritized for preservation during a memory pressure triggered cleanup.
 */
public enum CacheItemPriority {
    /**
     * The cache entry should be removed as soon as possible during memory pressure triggered cleanup.
     */
    LOW,

    /**
     * The cache entry should be removed if there is no other low priority cache entries during memory pressure triggered cleanup.
     */
    NORMAL,

    /**
     * The cache entry should be removed only when there is no other low or normal priority cache entries during memory pressure triggered cleanup.
     */
    HIGH,

    /**
     * The cache entry should never be removed during memory pressure triggered cleanup.
     */
    NEVER_REMOVE
}
