package dev.shetty.internal;

import dev.shetty.hybrid.*;
import dev.shetty.abstractions.IHybridCacheSerializer;

import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
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
public final class StampedeState<TState, T> extends BaseStampedeState {

    private final CompletableFuture<CacheItem<T>> result;
    private volatile Class<T> type;
    private volatile TState state;
    private volatile BiFunction<TState, CancellationToken, T> underlying; // main data factory
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
    public StampedeState(DefaultHybridCache cache, StampedeKey key, Class<T> type) {
        super(cache, key, CacheItem.create(cache.getCurrentTimestamp(), type));
        this.result = new CompletableFuture<>();
        this.type = type;
    }
    
    /**
     * Initializes a new instance with tags and cancellation support.
     *
     * @param cache The cache instance.
     * @param key The key for the cache entry.
     * @param tags The tags for the cache entry.
     * @param canBeCanceled Whether the operation can be canceled.
     */
    public StampedeState(DefaultHybridCache cache, StampedeKey key, TagSet tags, boolean canBeCanceled) {
        super(cache, key, CacheItem.create(cache.getCurrentTimestamp(), tags));
        this.result = new CompletableFuture<>();
        // We need to infer the type - this is a limitation in Java without reified generics
        this.type = null; // Will be set when the value is created
    }

    /**
     * Initializes a new instance for direct set operations (no result needed).
     *
     * @param cache The cache instance.
     * @param key The key for the cache entry.
     * @param type The class type of the cached value.
     */
    public StampedeState(DefaultHybridCache cache, StampedeKey key, Class<T> type, boolean isDirectSet) {
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
    public void queueUserWorkItem(TState state, BiFunction<TState, CancellationToken, T> underlying, HybridCacheEntryOptions options) {
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
    public CompletableFuture<Void> executeDirectAsync(TState state, BiFunction<TState, CancellationToken, T> underlying, HybridCacheEntryOptions options) {
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
    
    /**
     * Joins the operation, blocking until completion.
     * This method is designed to be called from virtual threads where blocking is cheap.
     *
     * @param cancellationToken Token for cooperative cancellation
     * @return The result value
     */
    public T join(CancellationToken cancellationToken) {
        try {
            // Block on the result future - cheap on virtual threads
            CacheItem<T> cacheItem = result.join();
            return cacheItem.getReservedValue();
        } catch (Exception ex) {
            throw new RuntimeException("Cache operation failed", ex);
        }
    }
    
    /**
     * Unwraps a reserved cache item value.
     * For completed results, returns immediately. Otherwise blocks on virtual thread.
     *
     * @return The unwrapped value
     */
    public T unwrapReserved() {
        try {
            CacheItem<T> cacheItem = result.join();
            return cacheItem.getReservedValue();
        } catch (Exception ex) {
            throw new RuntimeException("Cache operation failed", ex);
        }
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
            EnumSet<HybridCacheEntryFlags> activeFlags = getKey().getFlags();
            
            // Check for distributed cache read if appropriate
            if (!activeFlags.contains(HybridCacheEntryFlags.DISABLE_DISTRIBUTED_CACHE_READ)) {
                // Try to get from L2 (distributed cache) with type metadata
                DefaultHybridCache.L2ReadResult l2Result = getCache().getFromL2WithType(getKey().getKey());
                
                if (l2Result != null && l2Result.data != null && l2Result.data.hasValue()) {
                    // Infer type from L2 metadata if not already set (Java type erasure workaround)
                    if (type == null && l2Result.type != null) {
                        @SuppressWarnings("unchecked")
                        Class<T> l2Type = (Class<T>) l2Result.type;
                        type = l2Type;
                    }
                    // Parse and validate the result from L2
                    if (type != null && tryParseL2Result(l2Result.data)) {
                        return; // Successfully got result from L2
                    }
                }
            }

            // Nothing from L2; invoke the underlying data store
            if (!activeFlags.contains(HybridCacheEntryFlags.DISABLE_UNDERLYING_DATA)) {
                T newValue;
                try {
                    // Create a cancellation token for this operation
                    CancellationToken token = isCanceled ? CancellationToken.NONE : CancellationToken.NONE;
                    newValue = underlying.apply(state, token);
                } catch (Exception ex) {
                    setException(ex);
                    return;
                }

                // Infer the type from the factory result if not set (Java type erasure workaround)
                @SuppressWarnings("unchecked")
                Class<T> effectiveType = type != null ? type : (newValue != null ? (Class<T>) newValue.getClass() : null);
                if (effectiveType != null && type == null) {
                    type = effectiveType;
                }

                // Check whether we're going to hit a timing problem with tag invalidation
                if (!getCache().isValid(getTypedCacheItem())) {
                    // Handle timestamp collision - very rare scenario
                    long time = getCache().getCurrentTimestamp();
                    if (time <= getTypedCacheItem().getCreationTimestamp()) {
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
                CacheItem<T> cacheItem = getTypedCacheItem();
                boolean skipSerialize = cacheItem instanceof ImmutableCacheItem && 
                    (activeFlags.contains(HybridCacheEntryFlags.DISABLE_LOCAL_CACHE_WRITE) && activeFlags.contains(HybridCacheEntryFlags.DISABLE_DISTRIBUTED_CACHE_WRITE));

                if (skipSerialize) {
                    setImmutableResultWithoutSerialize(newValue);
                } else if (cacheItem.tryReserve()) {
                    BufferChunk bufferToRelease = null;
                    try {
                        // Try to serialize the value
                        DefaultHybridCache.SerializationResult<T> serializationResult = getCache().trySerialize(newValue, type);
                        
                        if (serializationResult.isSuccess()) {
                            bufferToRelease = serializationResult.getBuffer();
                            BufferChunk buffer = bufferToRelease.doNotReturnToPool();
                            
                            // Set the result (includes L1 write if appropriate)
                            setResultPreSerialized(newValue, bufferToRelease, serializationResult.getSerializer());

                            // MutableCacheItem.setValue takes ownership of the buffer's lifetime;
                            // null out local ref so finally block doesn't double-recycle
                            if (cacheItem instanceof MutableCacheItem) {
                                bufferToRelease = null;
                            }
                            
                            // Write to L2 if appropriate
                            if (!activeFlags.contains(HybridCacheEntryFlags.DISABLE_DISTRIBUTED_CACHE_WRITE)) {
                                try {
                                    getCache().setL2(getKey().getKey(), cacheItem, buffer, options, type);
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
        try {
            // Parse the HybridCachePayload
            byte[] data = result.toArray();
            HybridCachePayload.ParsedPayload parsed = HybridCachePayload.tryParse(
                data,
                getKey().getKey(),
                TagSet.EMPTY,
                getCache().getCurrentTimestamp()
            );
            
            // Check parse result
            if (parsed.result != HybridCachePayload.ParseResult.SUCCESS) {
                getCache().getLogger().log(Level.FINE, "L2 payload parse failed: " + parsed.result);
                return false;
            }
            
            // Deserialize the payload
            IHybridCacheSerializer<T> serializer = getCache().getSerializer(type);
            if (serializer == null) {
                getCache().getLogger().warning("No serializer found for type: " + type.getName());
                return false;
            }
            
            T value = serializer.deserialize(java.nio.ByteBuffer.wrap(parsed.payload));
            
            // Set the result from L2
            setResultFromL2(value, parsed.payload.length, parsed.remainingTime);
            return true;
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

    private void setResultFromL2(T value, int size, Duration remainingTime) {
        CacheItem<T> cacheItem;
        switch (getTypedCacheItem()) {
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
        
        // Use the remaining time from the payload for cache expiration
        setResult(cacheItem, remainingTime != null ? remainingTime : Duration.ofMillis(Long.MAX_VALUE));
    }

    private void setImmutableResultWithoutSerialize(T value) {
        CacheItem<T> cacheItem;
        if (getTypedCacheItem() instanceof ImmutableCacheItem<T> immutable) {
            immutable.setValue(value, -1);
            cacheItem = immutable;
        } else {
            throw new IllegalStateException("Unexpected cache item type for immutable result");
        }

        setResult(cacheItem);
    }

    private void setResultPreSerialized(T value, BufferChunk buffer, IHybridCacheSerializer<T> serializer) {
        CacheItem<T> cacheItem;
        switch (getTypedCacheItem()) {
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
        if (!getKey().getFlags().contains(HybridCacheEntryFlags.DISABLE_LOCAL_CACHE_WRITE)) {
            getCache().setL1(getKey().getKey(), value, options, maxRelativeTime);
        }

        if (result != null) {
            getCache().removeStampedeState(getKey());
            result.complete(value);
        }
    }

    /**
     * Gets the cache item with the correct generic type.
     *
     * @return The typed cache item.
     */
    @SuppressWarnings("unchecked")
    private CacheItem<T> getTypedCacheItem() {
        return (CacheItem<T>) getCacheItem();
    }
}
