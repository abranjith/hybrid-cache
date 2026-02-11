package dev.shetty.hybrid;

import dev.shetty.internal.BufferChunk;
import dev.shetty.internal.TagSet;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents a cache item for types that do not require defensive copies.
 * This class is final to prevent further extension, mirroring the sealed C# class.
 *
 * @param <T> The type of the value stored in the cache item.
 */
public final class ImmutableCacheItem<T> extends CacheItem<T> {

    // A shared, reserved instance. Using AtomicReference for volatile semantics.
    private static final AtomicReference<ImmutableCacheItem<?>> SHARED_DEFAULT = new AtomicReference<>();

    private T value; // The cached value, set later via setValue.
    private long size = -1;

    public ImmutableCacheItem(long creationTimestamp) {
        super(creationTimestamp);
    }
    
    public ImmutableCacheItem(long creationTimestamp, TagSet tags) {
        super(creationTimestamp, tags);
    }

    @Override
    public boolean isDebugImmutable() {
        return true;
    }

    /**
     * Gets a shared instance that is pre-reserved.
     * This avoids creating new objects for simple reserved instances.
     *
     * @param <T> The type of the cache item.
     * @return A reserved ImmutableCacheItem instance.
     */
    @SuppressWarnings("unchecked")
    public static <T> ImmutableCacheItem<T> getReservedShared() {
        ImmutableCacheItem<T> obj = (ImmutableCacheItem<T>) SHARED_DEFAULT.get();
        if (obj == null || !obj.tryReserve()) {
            obj = new ImmutableCacheItem<>(0); // Timestamp is not used here.
            obj.tryReserve(); // This is guaranteed to succeed on a new instance.
            SHARED_DEFAULT.set(obj);
        }
        return obj;
    }

    /**
     * Sets the value and size for this cache item.
     *
     * @param value The value to cache.
     * @param size  The size of the value in bytes.
     */
    public void setValue(T value, long size) {
        this.value = value;
        this.size = size;
    }

    @Override
    public Optional<T> tryGetValue() {
        // The value is always available for immutable items.
        return Optional.of(value);
    }

    /**
     * Tries to get the size of the item.
     *
     * @return The size if it has been set, otherwise -1.
     */
    @Override
    public OptionalLong tryGetSize() {
        return OptionalLong.of(this.size);
    }

    @Override
    public Optional<BufferChunk> tryReserveBuffer() {
        // Immutable items do not have a buffer to reserve.
        return Optional.empty();
    }
}