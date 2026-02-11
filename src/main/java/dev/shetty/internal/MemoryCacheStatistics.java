package dev.shetty.internal;

/**
 * Holds a snapshot of statistics for a memory cache.
 *
 * @param currentEntryCount    The number of ICacheEntry instances currently in the memory cache.
 * @param currentEstimatedSize An estimated sum of all the ICacheEntry.size values currently in the memory cache.
 *                             May be null if size isn't being tracked.
 * @param totalMisses          The total number of cache misses.
 * @param totalHits            The total number of cache hits.
 */
public record MemoryCacheStatistics(long currentEntryCount, Long currentEstimatedSize, long totalMisses,
                                    long totalHits) {
    /**
     * Initializes an instance of MemoryCacheStatistics.
     */
    public MemoryCacheStatistics {
    }
}