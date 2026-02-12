package dev.shetty.hybrid;

import dev.shetty.abstractions.HybridCache;
import dev.shetty.abstractions.IDistributedCache;
import dev.shetty.abstractions.IMemoryCache;
import dev.shetty.abstractions.IHybridCacheSerializer;
import dev.shetty.abstractions.IHybridCacheSerializerFactory;
import dev.shetty.internal.CancellationToken;
import dev.shetty.internal.CacheFeatures;
import dev.shetty.internal.HybridCacheEntryOptions;
import dev.shetty.internal.HybridCacheOptions;
import dev.shetty.internal.BufferChunk;
import dev.shetty.internal.ByteArrayPool;
import dev.shetty.internal.StampedeKey;
import dev.shetty.internal.StampedeState;
import dev.shetty.internal.PartitionedSyncLock;
import dev.shetty.internal.HybridCacheEntryFlags;
import dev.shetty.internal.TagSet;
import dev.shetty.internal.HybridCachePayload;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;
import java.util.logging.Logger;

/**
 * Default implementation of {@link HybridCache} providing multi-tier caching
 * with stampede protection, L1 (in-process) + L2 (distributed) architecture.
 * <p>
 * This class is designed for use with virtual threads. I/O operations (L2 reads/writes)
 * block the calling virtual thread, which the JVM un-mounts from its carrier during blocking.
 */
public final class DefaultHybridCache extends HybridCache {
    public static final int DEFAULT_EXPIRATION_MINUTES = 5;

    private final IDistributedCache backendCache;
    private final IMemoryCache localCache;
    private final HybridCacheOptions options;
    private final Logger logger;
    private final EnumSet<CacheFeatures> features = EnumSet.noneOf(CacheFeatures.class);
    private final Duration defaultExpiration;
    private final Duration defaultLocalCacheExpiration;
    private final int maximumKeyLength;

    // Serializer resolution cache
    private final ConcurrentHashMap<Class<?>, IHybridCacheSerializer<?>> serializerCache = new ConcurrentHashMap<>();
    private final List<IHybridCacheSerializerFactory> serializerFactories;

    // Stampede protection state management
    private final ConcurrentHashMap<StampedeKey, StampedeState<?, ?>> stampedeStates = new ConcurrentHashMap<>();
    private final PartitionedSyncLock partitionedLock = new PartitionedSyncLock();

    public DefaultHybridCache(
            HybridCacheOptions options,
            IMemoryCache localCache,
            IDistributedCache backendCache,
            Logger logger
    ) {
        this(options, localCache, backendCache, logger, List.of());
    }

    public DefaultHybridCache(
            HybridCacheOptions options,
            IMemoryCache localCache,
            IDistributedCache backendCache,
            Logger logger,
            List<IHybridCacheSerializerFactory> serializerFactories
    ) {
        this.options = options;
        this.localCache = localCache;
        this.backendCache = backendCache;
        this.logger = logger != null ? logger : Logger.getLogger(DefaultHybridCache.class.getName());
        this.maximumKeyLength = options.getMaximumKeyLength();
        this.serializerFactories = serializerFactories != null ? serializerFactories : List.of();

        if (backendCache != null) {
            features.add(CacheFeatures.BACKEND_CACHE);
        }

        this.defaultExpiration = options.getDefaultEntryOptions() != null
                ? options.getDefaultEntryOptions().getExpiration()
                : Duration.ofMinutes(DEFAULT_EXPIRATION_MINUTES);

        this.defaultLocalCacheExpiration = getEffectiveLocalCacheExpiration(options.getDefaultEntryOptions())
                .orElse(defaultExpiration);
    }

    // ---- HybridCache abstract method implementations ----

    @Override
    public <TState, T> T getOrCreate(
            String key,
            TState state,
            BiFunction<TState, CancellationToken, T> factory,
            HybridCacheEntryOptions options,
            Collection<String> tags,
            CancellationToken cancellationToken
    ) {
        cancellationToken.throwIfCancelled();

        if (!validateKey(key)) {
            // Key is invalid — bypass cache, call factory directly
            return factory.apply(state, cancellationToken);
        }

        // Determine effective flags
        EnumSet<HybridCacheEntryFlags> flags = getEffectiveFlags(options);

        // Try L1 (local cache) unless disabled
        if (!flags.contains(HybridCacheEntryFlags.DISABLE_LOCAL_CACHE_READ)) {
            Optional<CacheItem<T>> cached = tryGetExisting(key);
            if (cached.isPresent() && cached.get().tryReserve()) {
                Optional<T> value = cached.get().tryGetValue();
                if (value.isPresent()) {
                    // L1 hit - return immediately
                    T result = value.get();
                    cached.get().release();
                    return result;
                }
            }
        }

        // Get or create stampede state for coordination
        boolean canBeCanceled = cancellationToken != CancellationToken.NONE;
        StampedeResult<TState, T> stampedeResult = getOrCreateStampedeState(key, flags, tags, canBeCanceled);
        StampedeState<TState, T> stampede = stampedeResult.state;

        if (stampedeResult.isInitiator) {
            // We're the initiator - queue the background work
            if (canBeCanceled) {
                // Start work independently so others can join
                stampede.queueUserWorkItem(state, factory, options);
            } else {
                // Execute directly since we can't be canceled
                stampede.executeDirectAsync(state, factory, options);
                return stampede.unwrapReserved();
            }
        }

        // Join the existing/queued operation
        try {
            return stampede.join(cancellationToken);
        } catch (Exception ex) {
            stampede.cancelCaller();
            throw ex;
        }
    }

    @Override
    public <T> void set(
            String key,
            T value,
            HybridCacheEntryOptions options,
            Collection<String> tags,
            CancellationToken cancellationToken
    ) {
        cancellationToken.throwIfCancelled();

        // Write to L1
        if (localCache != null) {
            Duration expiration = getL1AbsoluteExpirationRelativeToNow(options);
            localCache.set(key, value, expiration);
        }

        // Write to L2
        if (backendCache != null) {
            cancellationToken.throwIfCancelled();
            
            // Serialize the value
            @SuppressWarnings("unchecked")
            Class<T> type = (Class<T>) value.getClass();
            SerializationResult<T> serResult = trySerialize(value, type);
            
            if (serResult.isSuccess()) {
                BufferChunk buffer = serResult.getBuffer();
                try {
                    // Create a cache item with tags for L2 write
                    TagSet tagSet = TagSet.create(tags);
                    CacheItem<T> cacheItem = CacheItem.create(getCurrentTimestamp(), tagSet);
                    
                    // Write to L2 using HybridCachePayload format
                    setL2(key, cacheItem, buffer, options);
                } finally {
                    // Recycle the buffer
                    if (buffer != null) {
                        buffer.recycleIfAppropriate();
                    }
                }
            } else {
                logger.warning("Failed to serialize value for L2 cache write: " + key);
            }
        }
    }

    @Override
    public void remove(String key, CancellationToken cancellationToken) {
        cancellationToken.throwIfCancelled();

        // Remove from L1
        if (localCache != null) {
            localCache.remove(key);
        }
        
        // Remove from L2
        if (backendCache != null) {
            cancellationToken.throwIfCancelled();
            backendCache.remove(key);
        }
        
        // Clean up any in-flight stampede state for this key
        // Note: We iterate through all types since we don't know the type here
        // This is a simplification; in production, you might want a more efficient approach
        stampedeStates.keySet().removeIf(stampedeKey -> stampedeKey.getKey().equals(key));
    }

    @Override
    public void removeByTag(String tag, CancellationToken cancellationToken) {
        cancellationToken.throwIfCancelled();
        // TODO: Phase 2/3 — tag invalidation system
    }

    // ---- Internal methods used by StampedeState ----

    /**
     * Gets the current timestamp for cache operations.
     */
    public long getCurrentTimestamp() {
        return System.currentTimeMillis();
    }

    /**
     * Gets data directly from L2 (distributed) cache.
     * Returns a BufferChunk wrapping the raw byte[] from the backend.
     */
    public BufferChunk getFromL2Direct(String key) {
        if (backendCache == null) {
            return null;
        }

        try {
            byte[] data = backendCache.get(key);
            if (data != null) {
                return new BufferChunk(data);
            }
        } catch (Exception ex) {
            logger.warning("Failed to get from L2 cache: " + ex.getMessage());
        }
        return null;
    }

    /**
     * Result of reading from L2, including type metadata for Java type erasure workaround.
     */
    public static class L2ReadResult {
        public final BufferChunk data;
        public final Class<?> type;

        public L2ReadResult(BufferChunk data, Class<?> type) {
            this.data = data;
            this.type = type;
        }
    }

    /**
     * Gets data from L2 with type metadata. Java type erasure workaround:
     * the type class name is prepended to the raw L2 payload so the reader
     * can reconstruct the correct serializer.
     */
    public L2ReadResult getFromL2WithType(String key) {
        if (backendCache == null) {
            return null;
        }

        try {
            byte[] raw = backendCache.get(key);
            if (raw == null || raw.length < 4) {
                return null;
            }

            // Extract type name length (4 bytes, big-endian)
            int typeNameLen = ((raw[0] & 0xFF) << 24) | ((raw[1] & 0xFF) << 16)
                    | ((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF);
            if (typeNameLen <= 0 || typeNameLen > raw.length - 4) {
                // Not a typed payload — fall back to raw read
                return new L2ReadResult(new BufferChunk(raw), null);
            }

            String className = new String(raw, 4, typeNameLen, java.nio.charset.StandardCharsets.UTF_8);
            byte[] payload = new byte[raw.length - 4 - typeNameLen];
            System.arraycopy(raw, 4 + typeNameLen, payload, 0, payload.length);

            Class<?> type = Class.forName(className);
            return new L2ReadResult(new BufferChunk(payload), type);
        } catch (Exception ex) {
            logger.warning("Failed to get typed data from L2 cache: " + ex.getMessage());
        }
        return null;
    }

    /**
     * Checks if a cache item is still valid.
     */
    public boolean isValid(CacheItem<?> item) {
        return item != null && item.isValid();
    }

    /**
     * Attempts to serialize a value for caching.
     */
    public <T> SerializationResult<T> trySerialize(T value, Class<T> type) {
        try {
            IHybridCacheSerializer<T> serializer = getSerializer(type);
            if (serializer != null) {
                byte[] data = serializer.serialize(value);
                // Rent a pool buffer so recycleIfAppropriate() can safely return it
                byte[] pooled = ByteArrayPool.getShared().take(data.length);
                System.arraycopy(data, 0, pooled, 0, data.length);
                BufferChunk buffer = new BufferChunk(pooled, 0, data.length, true);
                return new SerializationResult<>(true, buffer, serializer);
            }
        } catch (Exception ex) {
            logger.warning("Serialization failed: " + ex.getMessage());
        }
        return new SerializationResult<>(false, null, null);
    }

    /**
     * Gets the appropriate serializer for a given type.
     * Caches the result for subsequent lookups.
     */
    @SuppressWarnings("unchecked")
    public <T> IHybridCacheSerializer<T> getSerializer(Class<T> type) {
        IHybridCacheSerializer<T> cached = (IHybridCacheSerializer<T>) serializerCache.get(type);
        if (cached != null) {
            return cached;
        }

        // Try each factory in order
        for (IHybridCacheSerializerFactory factory : serializerFactories) {
            IHybridCacheSerializer<T> serializer = factory.tryCreateSerializer(type);
            if (serializer != null) {
                serializerCache.put(type, serializer);
                return serializer;
            }
        }

        return null;
    }

    /**
     * Gets or creates a stampede state for coordinating concurrent cache operations.
     * <p>
     * Uses double-checked locking with partitioned locks to minimize contention.
     * Returns true if the current caller should execute the factory; false if they should
     * join an existing operation.
     * 
     * @param <TState> state type
     * @param <T> value type
     * @param key cache key
     * @param flags cache entry flags
     * @param tags tags for the entry
     * @param canBeCanceled whether the operation can be canceled
     * @return a result containing the stampede state and whether this caller is the initiator
     */
    @SuppressWarnings("unchecked")
    public <TState, T> StampedeResult<TState, T> getOrCreateStampedeState(
            String key,
            EnumSet<HybridCacheEntryFlags> flags,
            Collection<String> tags,
            boolean canBeCanceled
    ) {
        StampedeKey stampedeKey = new StampedeKey(key, flags);
        
        // First attempt: lock-free check
        StampedeState<TState, T> existing = tryJoinExistingSession(stampedeKey);
        if (existing != null) {
            return new StampedeResult<>(existing, false);
        }
        
        // Create new stampede state speculatively
        TagSet tagSet = TagSet.create(tags);
        StampedeState<TState, T> newState = new StampedeState<>(
            this, stampedeKey, tagSet, canBeCanceled);
        
        // Try to add it
        if (stampedeStates.putIfAbsent(stampedeKey, newState) == null) {
            // Successfully added - this caller is the initiator
            return new StampedeResult<>(newState, true);
        }
        
        // Failed to add - concurrent activity. Use partitioned lock for coordination.
        return partitionedLock.execute(stampedeKey, () -> {
            // Re-check under lock
            StampedeState<TState, T> locked = tryJoinExistingSession(stampedeKey);
            if (locked != null) {
                // Found existing session under lock
                newState.setCanceled(); // Mark speculative state as canceled
                return new StampedeResult<>(locked, false);
            }
            
            // Check if value was just cached by another thread
            if (!flags.contains(HybridCacheEntryFlags.DISABLE_LOCAL_CACHE_READ)) {
                Optional<CacheItem<T>> cached = tryGetExisting(key);
                if (cached.isPresent()) {
                    cached.get().release();
                }
            }
            
            // No existing session - use our speculative state
            stampedeStates.put(stampedeKey, newState);
            return new StampedeResult<>(newState, true);
        });
    }
    
    /**
     * Tries to join an existing stampede session.
     */
    @SuppressWarnings("unchecked")
    private <TState, T> StampedeState<TState, T> tryJoinExistingSession(StampedeKey key) {
        StampedeState<?, ?> found = stampedeStates.get(key);
        if (found == null) {
            return null;
        }
        
        // Type check
        if (!(found instanceof StampedeState)) {
            // This shouldn't happen in practice, but be defensive
            return null;
        }
        
        StampedeState<TState, T> typed = (StampedeState<TState, T>) found;
        if (typed.tryAddCaller()) {
            return typed;
        }
        
        return null;
    }
    
    /**
     * Result of stampede state creation.
     */
    public static class StampedeResult<TState, T> {
        public final StampedeState<TState, T> state;
        public final boolean isInitiator;
        
        public StampedeResult(StampedeState<TState, T> state, boolean isInitiator) {
            this.state = state;
            this.isInitiator = isInitiator;
        }
    }

    /**
     * Removes stampede state for a given key.
     */
    public void removeStampedeState(StampedeKey key) {
        stampedeStates.remove(key);
    }

    /**
     * Sets a value in L1 (local) cache.
     */
    public <T> void setL1(String key, CacheItem<T> value, HybridCacheEntryOptions options, Duration maxRelativeTime) {
        Duration expiration = getL1AbsoluteExpirationRelativeToNow(options);
        if (maxRelativeTime != null && maxRelativeTime.compareTo(expiration) < 0) {
            expiration = maxRelativeTime;
        }
        // L1 cache holds a reference to the CacheItem — reserve it
        value.tryReserve();
        localCache.set(key, value, expiration);
    }

    /**
     * Sets a value in L2 (distributed) cache.
     * Now works directly with byte[] — no boxing/unboxing.
     */
    public <T> void setL2(String key, CacheItem<T> cacheItem, BufferChunk buffer, HybridCacheEntryOptions options) {
        setL2(key, cacheItem, buffer, options, null);
    }

    /**
     * Sets a value in L2 (distributed) cache with type metadata.
     * Type metadata is prepended to the payload so L2 reads can reconstruct
     * the correct serializer (Java type erasure workaround).
     */
    public <T> void setL2(String key, CacheItem<T> cacheItem, BufferChunk buffer, HybridCacheEntryOptions options, Class<T> type) {
        if (backendCache == null || buffer == null || !buffer.hasValue()) {
            return;
        }

        try {
            // Serialize using HybridCachePayload format
            byte[] serializedData = buffer.toArray();
            TagSet tags = cacheItem != null ? cacheItem.getTags() : TagSet.EMPTY;
            Duration duration = options != null ? getL2AbsoluteExpirationRelativeToNow(options) : (this.options.getDefaultEntryOptions() != null ? this.options.getDefaultEntryOptions().getExpiration() : defaultExpiration);
            long creationTime = getCurrentTimestamp();
            
            // Calculate required buffer size
            int maxSize = HybridCachePayload.getMaxBytes(key, tags, serializedData.length);
            byte[] payloadBuffer = new byte[maxSize];
            
            // Write the payload
            int bytesWritten = HybridCachePayload.write(
                payloadBuffer,
                key,
                creationTime,
                duration,
                HybridCachePayload.PayloadFlags.NONE,
                tags,
                serializedData
            );
            
            // Trim to actual size
            byte[] hybridPayload = bytesWritten == payloadBuffer.length 
                ? payloadBuffer 
                : Arrays.copyOf(payloadBuffer, bytesWritten);

            // Wrap with type metadata if type is known
            byte[] finalPayload;
            if (type != null) {
                byte[] typeNameBytes = type.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                finalPayload = new byte[4 + typeNameBytes.length + hybridPayload.length];
                finalPayload[0] = (byte) (typeNameBytes.length >> 24);
                finalPayload[1] = (byte) (typeNameBytes.length >> 16);
                finalPayload[2] = (byte) (typeNameBytes.length >> 8);
                finalPayload[3] = (byte) (typeNameBytes.length);
                System.arraycopy(typeNameBytes, 0, finalPayload, 4, typeNameBytes.length);
                System.arraycopy(hybridPayload, 0, finalPayload, 4 + typeNameBytes.length, hybridPayload.length);
            } else {
                finalPayload = hybridPayload;
            }
            
            // Write to backend
            backendCache.set(key, finalPayload, options != null ? options.toCacheEntryOptions() : null);
        } catch (Exception ex) {
            logger.warning("Failed to set L2 cache: " + ex.getMessage());
        }
    }

    /**
     * Gets the logger instance.
     */
    public Logger getLogger() {
        return logger;
    }

    private boolean validateKey(String key) {
        if (key == null || key.isBlank()) {
            logger.warning("Key is empty or whitespace");
            return false;
        }
        if (key.length() > maximumKeyLength) {
            logger.warning("Key length exceeded: " + key.length());
            return false;
        }
        if (containsReservedCharacters(key)) {
            logger.warning("Key contains reserved characters");
            return false;
        }
        return true;
    }

    private boolean containsReservedCharacters(String key) {
        for (char c : key.toCharArray()) {
            if (c <= 31) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private <T> Optional<CacheItem<T>> tryGetExisting(String key) {
        Optional<Object> untyped = localCache.get(key);
        if (untyped.isPresent() && untyped.get() instanceof CacheItem<?> item) {
            CacheItem<T> typed = (CacheItem<T>) item;
            if (typed.isValid()) {
                return Optional.of(typed);
            }
            localCache.remove(key);
        }
        return Optional.empty();
    }

    private Optional<Duration> getEffectiveLocalCacheExpiration(HybridCacheEntryOptions options) {
        if (options == null) return Optional.empty();
        if (options.getLocalCacheExpiration() != null) {
            if (options.getExpiration() != null) {
                return Optional.of(
                        options.getLocalCacheExpiration().compareTo(options.getExpiration()) < 0
                                ? options.getLocalCacheExpiration()
                                : options.getExpiration()
                );
            }
            return Optional.of(options.getLocalCacheExpiration());
        }
        return Optional.ofNullable(options.getExpiration());
    }

    private Duration getL1AbsoluteExpirationRelativeToNow(HybridCacheEntryOptions options) {
        return getEffectiveLocalCacheExpiration(options).orElse(defaultLocalCacheExpiration);
    }

    private Duration getL2AbsoluteExpirationRelativeToNow(HybridCacheEntryOptions options) {
        return options != null && options.getExpiration() != null
                ? options.getExpiration()
                : defaultExpiration;
    }

    /**
     * Gets the effective flags for a cache operation,
     * combining global defaults with per-call options.
     */
    private EnumSet<HybridCacheEntryFlags> getEffectiveFlags(HybridCacheEntryOptions options) {
        if (options == null || options.getFlags() == null || options.getFlags().isEmpty()) {
            // Return defaults
            if (backendCache == null) {
                return EnumSet.of(HybridCacheEntryFlags.DISABLE_DISTRIBUTED_CACHE_READ, HybridCacheEntryFlags.DISABLE_DISTRIBUTED_CACHE_WRITE);
            }
            return EnumSet.noneOf(HybridCacheEntryFlags.class);
        }

        EnumSet<HybridCacheEntryFlags> result = EnumSet.copyOf(options.getFlags());
        if (backendCache == null) {
            result.add(HybridCacheEntryFlags.DISABLE_DISTRIBUTED_CACHE_READ);
            result.add(HybridCacheEntryFlags.DISABLE_DISTRIBUTED_CACHE_WRITE);
        }
        return result;
    }

    /**
     * Helper class to represent serialization results.
     */
    public static class SerializationResult<T> {
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