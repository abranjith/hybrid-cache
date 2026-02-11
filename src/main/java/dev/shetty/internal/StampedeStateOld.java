package dev.shetty.internal;

import dev.shetty.hybrid.CacheItem;
import dev.shetty.hybrid.DefaultHybridCache;
import dev.shetty.hybrid.ImmutableCacheItem;
import dev.shetty.hybrid.MutableCacheItem;
import dev.shetty.abstractions.IHybridCacheSerializer;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.time.Duration;

/**
 * Represents the state of a cache operation that is guarding against cache stampedes for a specific type.
 * This is the Java implementation of the C# StampedeState<TState, T> class.
 *
 * @param <TState> The type of the state passed to the underlying data callback.
 * @param <T> The type of the value being cached.
 */
public final class StampedeStateOld<TState, T> extends BaseStampedeState {

    // Combine L1 and L2 write disable flags for efficiency checks
    private static final int FLAGS_DISABLE_L1_AND_L2_WRITE = 
        HybridCacheEntryFlags.DISABLE_LOCAL_CACHE_WRITE | HybridCacheEntryFlags.DISABLE_DISTRIBUTED_CACHE_WRITE;

    private final CompletableFuture<CacheItem<T>> result;
    private final Class<T> type;
    private volatile TState state;
    private volatile Function<TState, T> underlying; // main data factory
    private volatile HybridCacheEntryOptions options;
    private volatile CompletableFuture<T> sharedUnwrap; // allows multiple non-cancellable callers to share a single task
    private volatile boolean isCanceled;

    /**
     * Initializes a new instance for shared operations.
     *
     * @param cache The cache instance.
     * @param key The key for the cache entry.
     * @param type The class type of the cached value.
     */
    public StampedeStateOld(DefaultHybridCache cache, StampedeKey key, Class<T> type) {
        super(cache, key, CacheItem.create(cache.getCurrentTimestamp(), type));
        this.result = new CompletableFuture<>();
        this.type = type;
    }

    /**
     * Initializes a new instance for direct set operations (no result needed).
     *
     * @param cache The cache instance.
     * @param key The key for the cache entry.
     * @param type The class type of the cached value.
     */
    public StampedeStateOld(DefaultHybridCache cache, StampedeKey key, Class<T> type, boolean isDirectSet) {
        super(cache, key, CacheItem.create(cache.getCurrentTimestamp(), type));
        this.result = isDirectSet ? null : new CompletableFuture<>();
        this.type = type;
    }

    @Override
    public Class<?> getType() {
        return type;
    }

    /**
     * Sets the result directly without any other side-effects.
     * Internal method for direct result setting.
     *
     * @param value The cache item to set as result.
     */
    void setResultDirect(CacheItem<T> value) {
        if (result != null) {
            result.complete(value);
        }
    }

    /**
     * Queues the work item for execution in the thread pool.
     *
     * @param state The state to pass to the underlying callback.
     * @param underlying The underlying data factory function.
     * @param options The cache entry options.
     */
    public void queueUserWorkItem(TState state, Function<TState, T> underlying, HybridCacheEntryOptions options) {
        if (this.underlying != null) {
            throw new IllegalStateException("Factory should not already be set");
        }
        if (underlying == null) {
            throw new IllegalArgumentException("Factory argument should be meaningful");
        }

        // Initialize the callback state
        this.state = state;
        this.underlying = underlying;
        this.options = options;

        // Execute in thread pool
        CompletableFuture.runAsync(this::execute);
    }

    /**
     * Executes the operation directly and returns a future.
     *
     * @param state The state to pass to the underlying callback.
     * @param underlying The underlying data factory function.
     * @param options The cache entry options.
     * @return A CompletableFuture representing the async operation.
     */
    public CompletableFuture<Void> executeDirectAsync(TState state, Function<TState, T> underlying, HybridCacheEntryOptions options) {
        if (this.underlying != null) {
            throw new IllegalStateException("Factory should not already be set");
        }
        if (underlying == null) {
            throw new IllegalArgumentException("Factory argument should be meaningful");
        }

        // Initialize the callback state
        this.state = state;
        this.underlying = underlying;
        this.options = options;

        return CompletableFuture.runAsync(this::backgroundFetch);
    }

    @Override
    public void execute() {
        backgroundFetch();
    }

    @Override
    public void setCanceled() {
        isCanceled = true;
        if (result != null) {
            result.cancel(true);
        }
    }

    /**
     * Joins the async operation with optional cancellation support.
     *
     * @param log The logger instance.
     * @return A CompletableFuture<T> representing the result.
     */
    public CompletableFuture<T> joinAsync(Logger log) {
        // If the underlying has already completed, we can simply wrap the shared task
        if (getTask().isDone()) {
            return unwrapReservedAsync(log);
        }

        // For simplicity in Java, we'll handle cancellation through CompletableFuture's built-in mechanisms
        return unwrapReservedAsync(log);
    }

    /**
     * Gets the task representing this operation.
     *
     * @return The CompletableFuture representing the cache item result.
     */
    public CompletableFuture<CacheItem<T>> getTask() {
        if (result == null) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Task should not be accessed for non-shared instances"));
        }
        return result;
    }

    /**
     * Unwraps the reserved async result.
     *
     * @param log The logger instance.
     * @return A CompletableFuture<T> representing the unwrapped result.
     */
    CompletableFuture<T> unwrapReservedAsync(Logger log) {
        CompletableFuture<CacheItem<T>> task = getTask();
        
        if (task.isDone() && !task.isCompletedExceptionally()) {
            try {
                return CompletableFuture.completedFuture(task.get().getReservedValue(log));
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        // If the type is immutable, callers can share the final step too
        CompletableFuture<T> result = ImmutableTypeCache.isImmutable(type) ? 
            getOrCreateSharedUnwrap(log) : createAwaitedAsync(log, task);
        
        return result;
    }

    private CompletableFuture<T> getOrCreateSharedUnwrap(Logger log) {
        if (sharedUnwrap == null) {
            synchronized (this) {
                if (sharedUnwrap == null) {
                    sharedUnwrap = createAwaitedAsync(log, getTask());
                }
            }
        }
        return sharedUnwrap;
    }

    private CompletableFuture<T> createAwaitedAsync(Logger log, CompletableFuture<CacheItem<T>> task) {
        return task.thenApply(cacheItem -> cacheItem.getReservedValue(log));
    }

    /**
     * Main background fetch logic that handles the cache operation.
     */
    private void backgroundFetch() {
        try {
            int activeFlags = getKey().getFlags();
            
            // Check for distributed cache read if appropriate
            if ((activeFlags & HybridCacheEntryFlags.DISABLE_DISTRIBUTED_CACHE_READ) == 0) {
                // Try to get from L2 (distributed cache)
                BufferChunk l2Result = getCache().getFromL2Direct(getKey().getKey());
                
                if (l2Result != null && l2Result.hasValue()) {
                    // Parse and validate the result from L2
                    if (tryParseL2Result(l2Result)) {
                        return; // Successfully got result from L2
                    }
                }
            }

            // Nothing from L2; invoke the underlying data store
            if ((activeFlags & HybridCacheEntryFlags.DISABLE_UNDERLYING_DATA) == 0) {
                T newValue;
                try {
                    newValue = underlying.apply(state);
                } catch (Exception ex) {
                    setException(ex);
                    return;
                }

                // Check whether we're going to hit a timing problem with tag invalidation
                if (!getCache().isValid(getCacheItem())) {
                    // Handle timestamp collision - very rare scenario
                    long time = getCache().getCurrentTimestamp();
                    if (time <= getCacheItem().getCreationTimestamp()) {
                        try {
                            Thread.sleep(1); // Artificial delay
                            time = getCache().getCurrentTimestamp();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            setException(e);
                            return;
                        }
                    }
                    getCacheItem().unsafeSetCreationTimestamp(time);
                }

                // Determine if we need to serialize
                CacheItem<T> cacheItem = getCacheItem();
                boolean skipSerialize = cacheItem instanceof ImmutableCacheItem && 
                    (activeFlags & FLAGS_DISABLE_L1_AND_L2_WRITE) == FLAGS_DISABLE_L1_AND_L2_WRITE;

                if (skipSerialize) {
                    setImmutableResultWithoutSerialize(newValue);
                } else if (cacheItem.tryReserve()) {
                    BufferChunk bufferToRelease = null;
                    try {
                        // Try to serialize the value
                        SerializationResult<T> serializationResult = getCache().trySerialize(newValue, type);
                        
                        if (serializationResult.isSuccess()) {
                            bufferToRelease = serializationResult.getBuffer();
                            BufferChunk buffer = bufferToRelease.doNotReturnToPool();
                            
                            // Set the result (includes L1 write if appropriate)
                            setResultPreSerialized(newValue, bufferToRelease, serializationResult.getSerializer());
                            
                            // Write to L2 if appropriate
                            if ((activeFlags & HybridCacheEntryFlags.DISABLE_DISTRIBUTED_CACHE_WRITE) == 0) {
                                try {
                                    getCache().setL2(getKey().getKey(), cacheItem, buffer, options);
                                } catch (Exception ex) {
                                    // Log L2 write failure but don't interrupt app flow
                                    getCache().getLogger().log(Level.WARNING, "L2 cache write failed", ex);
                                }
                            }
                        } else {
                            // Unable to serialize; try to at least store the value
                            setResultPreSerialized(newValue, bufferToRelease, serializationResult.getSerializer());
                        }
                    } finally {
                        // Release our hook on the CacheItem
                        cacheItem.release();
                        
                        // Recycle any leftover buffer
                        if (bufferToRelease != null) {
                            bufferToRelease.recycleIfAppropriate();
                        }
                    }
                } else {
                    throw new IllegalStateException("Internal HybridCache failure: unable to reserve cache item to assign result");
                }
            } else {
                // Can't read from data store; set default result
                setDefaultResult();
            }
        } catch (Exception ex) {
            setException(ex);
        }
    }

    private boolean tryParseL2Result(BufferChunk result) {
        // This would involve parsing the hybrid cache payload
        // For now, simplified implementation
        try {
            IHybridCacheSerializer<T> serializer = getCache().getSerializer(type);
            if (serializer != null) {
                T value = serializer.deserialize(result.asArraySegment());
                setResultFromL2(value, result.getLength());
                return true;
            }
        } catch (Exception ex) {
            getCache().getLogger().log(Level.WARNING, "Failed to parse L2 result", ex);
        }
        return false;
    }

    private void setException(Exception ex) {
        if (result != null) {
            getCache().removeStampedeState(getKey());
            result.completeExceptionally(ex);
        }
    }

    private void setDefaultResult() {
        if (result != null) {
            getCache().removeStampedeState(getKey());
            result.complete(ImmutableCacheItem.getReservedShared());
        }
    }

    private void setResultFromL2(T value, int size) {
        CacheItem<T> cacheItem;
        switch (getCacheItem()) {
            case ImmutableCacheItem<T> immutable -> {
                immutable.setValue(value, size);
                cacheItem = immutable;
            }
            case MutableCacheItem<T> mutable -> {
                // For L2 results, we typically deserialize to immutable form
                // This is a simplified approach
                mutable.setFallbackValue(value);
                cacheItem = mutable;
            }
            default -> throw new IllegalStateException("Unexpected cache item type");
        }
        
        setResult(cacheItem, Duration.ofMillis(Long.MAX_VALUE));
    }

    private void setImmutableResultWithoutSerialize(T value) {
        if ((getKey().getFlags() & FLAGS_DISABLE_L1_AND_L2_WRITE) != FLAGS_DISABLE_L1_AND_L2_WRITE) {
            throw new IllegalStateException("Only expected if L1+L2 disabled");
        }

        CacheItem<T> cacheItem;
        if (getCacheItem() instanceof ImmutableCacheItem<T> immutable) {
            immutable.setValue(value, -1);
            cacheItem = immutable;
        } else {
            throw new IllegalStateException("Unexpected cache item type for immutable result");
        }

        setResult(cacheItem);
    }

    private void setResultPreSerialized(T value, BufferChunk buffer, IHybridCacheSerializer<T> serializer) {
        CacheItem<T> cacheItem;
        switch (getCacheItem()) {
            case ImmutableCacheItem<T> immutable -> {
                immutable.setValue(value, buffer != null ? buffer.getLength() : -1);
                cacheItem = immutable;
            }
            case MutableCacheItem<T> mutable -> {
                if (serializer == null) {
                    mutable.setFallbackValue(value);
                } else {
                    mutable.setValue(buffer, serializer);
                    mutable.debugOnlyTrackBuffer(getCache());
                }
                cacheItem = mutable;
            }
            default -> throw new IllegalStateException("Unexpected cache item type");
        }

        setResult(cacheItem);
    }

    private void setResult(CacheItem<T> value) {
        setResult(value, Duration.ofMillis(Long.MAX_VALUE));
    }

    private void setResult(CacheItem<T> value, Duration maxRelativeTime) {
        if ((getKey().getFlags() & HybridCacheEntryFlags.DISABLE_LOCAL_CACHE_WRITE) == 0) {
            getCache().setL1(getKey().getKey(), value, options, maxRelativeTime);
        }

        if (result != null) {
            getCache().removeStampedeState(getKey());
            result.complete(value);
        }
    }

    /**
     * Helper class to represent serialization results.
     */
    private static class SerializationResult<T> {
        private final boolean success;
        private final BufferChunk buffer;
        private final IHybridCacheSerializer<T> serializer;

        public SerializationResult(boolean success, BufferChunk buffer, IHybridCacheSerializer<T> serializer) {
            this.success = success;
            this.buffer = buffer;
            this.serializer = serializer;
        }

        public boolean isSuccess() { return success; }
        public BufferChunk getBuffer() { return buffer; }
        public IHybridCacheSerializer<T> getSerializer() { return serializer; }
    }
}
