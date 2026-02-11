package dev.shetty.internal;

import java.time.Duration;
import java.util.EnumSet;

/**
 * Specifies additional options (for example, expiration) that apply to a HybridCache operation.
 * When options can be specified at multiple levels (for example, globally and per-call), the values are composed;
 * the most granular non-null value is used, with null values being inherited. If no value is specified at any level,
 * the implementation can choose a reasonable default.
 */
public final class HybridCacheEntryOptions {
    /**
     * The overall cache duration of this entry, passed to the backend distributed cache.
     */
    private final Duration expiration;

    /**
     * When retrieving a cached value from an external cache store, this value will be used to calculate the local
     * cache expiration, not exceeding the remaining overall cache lifetime.
     */
    private final Duration localCacheExpiration;

    /**
     * Additional flags that apply to the requested operation.
     */
    private final EnumSet<HybridCacheEntryFlags> flags;

    public HybridCacheEntryOptions(Duration expiration, Duration localCacheExpiration, EnumSet<HybridCacheEntryFlags> flags) {
        this.expiration = expiration;
        this.localCacheExpiration = localCacheExpiration;
        this.flags = flags != null ? flags : EnumSet.noneOf(HybridCacheEntryFlags.class);
    }

    public Duration getExpiration() {
        return expiration;
    }

    public Duration getLocalCacheExpiration() {
        return localCacheExpiration;
    }

    public EnumSet<HybridCacheEntryFlags> getFlags() {
        return flags;
    }

    /**
     * Converts this instance to a CacheEntryOptions if expiration is set.
     * @return a CacheEntryOptions instance or null if expiration is null.
     */
    public CacheEntryOptions toCacheEntryOptions() {
        if (expiration == null) return null;
        var options = new CacheEntryOptions();
        options.setAbsoluteExpirationRelativeToNow(expiration);
        return options;
    }
}