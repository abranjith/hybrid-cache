package dev.shetty.internal;

import dev.shetty.hybrid.BaseCacheItem;
import dev.shetty.hybrid.DefaultHybridCache;

/**
 * Represents the state of a cache operation that is guarding against cache stampedes.
 * <p>
 * In a cache stampede scenario, multiple concurrent requests for the same cache key that isn't cached
 * would all try to compute the value simultaneously. This class helps coordinate these requests so only
 * one operation generates the value while others wait for it.
 */
public abstract class BaseStampedeState {

    /** The cache instance handling this operation. */
    private final DefaultHybridCache cache;

    /** The cache item associated with this operation. */
    private final BaseCacheItem cacheItem;

    /** The key associated with this cache entry. */
    private final StampedeKey key;

    /**
     * Initializes a new instance of the StampedeState class optionally with shared cancellation support.
     *
     * @param cache The cache instance.
     * @param key The key for the cache entry.
     * @param cacheItem The cache item for this operation.
     */
    protected BaseStampedeState(DefaultHybridCache cache, StampedeKey key, BaseCacheItem cacheItem) {
        this.cache = cache;
        this.key = key;
        this.cacheItem = cacheItem;
    }

    /**
     * Gets the key for this cache entry.
     *
     * @return The key.
     */
    public final StampedeKey getKey() {
        return key;
    }

    /**
     * Gets the cache item for this operation.
     *
     * @return The cache item.
     */
    protected final BaseCacheItem getCacheItem() {
        return cacheItem;
    }

    /**
     * Gets the cache instance.
     *
     * @return The cache.
     */
    protected final DefaultHybridCache getCache() {
        return cache;
    }

    /**
     * Executes the cache operation.
     * This would be the equivalent of the IThreadPoolWorkItem.Execute method in the C# implementation.
     */
    public abstract void execute();

    /**
     * Returns a string representation of this instance.
     *
     * @return A string representation using the key.
     */
    @Override
    public String toString() {
        return key.toString();
    }

    /**
     * Marks this operation as canceled.
     */
    public abstract void setCanceled();

    /**
     * Gets the current number of callers for debugging purposes.
     *
     * @return The number of active callers.
     */
    public final int getDebugCallerCount() {
        return cacheItem.getRefCount();
    }

    /**
     * Gets the type of value being cached.
     *
     * @return The type of the cached value.
     */
    public abstract Class<?> getType();

    /**
     * Notifies that a caller is canceling its participation.
     * If this is the last caller, cancels the entire operation.
     */
    public final void cancelCaller() {
        // If this release is the final one (returns true), cancel the operation
        if (cacheItem.release()) {
            setCanceled();
        }
    }

    /**
     * Attempts to add another caller to this operation.
     *
     * @return true if the caller was successfully added; otherwise, false.
     */
    public final boolean tryAddCaller() {
        return cacheItem.tryReserve();
    }
}