package dev.shetty.hybrid;

import dev.shetty.abstractions.IHybridCacheSerializer;
import dev.shetty.internal.BufferChunk;
import dev.shetty.internal.TagSet;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * Represents a cache item for types that require defensive copies, stored in a serialized form.
 * This class is final to prevent further extension, mirroring the sealed C# class.
 *
 * @param <T> The type of the value stored in the cache item.
 */
public final class MutableCacheItem<T> extends CacheItem<T> {

    private IHybridCacheSerializer<T> serializer;
    private BufferChunk buffer;
    private T fallbackValue; // Used only in case of deserialization failures.

    public MutableCacheItem(long creationTimestamp) {
        super(creationTimestamp);
    }
    
    public MutableCacheItem(long creationTimestamp, TagSet tags) {
        super(creationTimestamp, tags);
    }

    @Override
    public boolean needsEvictionCallback() {
        // The buffer should be returned to the pool if it's marked as such.
        return buffer != null && buffer.returnToPool();
    }

    @Override
    public boolean isDebugImmutable() {
        return false;
    }

    /**
     * Sets the serialized value and its serializer. This takes ownership of the buffer.
     *
     * @param buffer     The buffer chunk containing the serialized data. The caller
     *                   must not use this instance after the call.
     * @param serializer The serializer to deserialize the data.
     */
    public void setValue(BufferChunk buffer, IHybridCacheSerializer<T> serializer) {
        this.serializer = serializer;
        this.buffer = buffer;
    }

    /**
     * Sets a fallback value to be used if deserialization fails.
     *
     * @param fallbackValue The fallback value.
     */
    public void setFallbackValue(T fallbackValue) {
        this.fallbackValue = fallbackValue;
    }

    /**
     * Tries to deserialize and retrieve the value.
     * This operation is thread-safe and manages the item's reference count.
     *
     * @return A GetValueResult containing the value if successful.
     */
    @Override
    public Optional<T> tryGetValue() {
        if (tryReserve()) {
            try {
                if (serializer == null) {
                    return Optional.ofNullable(fallbackValue);
                }
                T value = serializer.deserialize(buffer.asReadOnlyBuffer());
                return Optional.of(value);
            } catch (Exception ex) {
                // Propagate as a runtime exception to signal a critical failure.
                throw new RuntimeException("Deserialization failed.", ex);
            } finally {
                release();
            }
        }
        return Optional.empty();
    }

    /**
     * Tries to get the size of the serialized data.
     *
     * @return The size in bytes if available, otherwise -1.
     */
    @Override
    public OptionalLong tryGetSize() {
        if (tryReserve()) {
            try {
                return OptionalLong.of ((buffer != null) ? buffer.getLength() : -1);
            } finally {
                release();
            }
        }
        return OptionalLong.of(-1);
    }

    /**
     * Tries to reserve the underlying buffer for direct use.
     *
     * @return An Optional containing the buffer if reservation is successful.
     *         The returned buffer is marked to not be returned to the pool by the caller.
     */
    @Override
    public Optional<BufferChunk> tryReserveBuffer() {
        if (tryReserve()) {
            // The caller of this method gets a view of the buffer but is not
            // responsible for recycling it.
            return Optional.of(buffer.doNotReturnToPool());
        }
        return Optional.empty();
    }

    /**
     * Debug-only method to track buffer usage (no-op in production).
     * This method exists for compatibility with the C# implementation.
     * 
     * @param cache The cache instance (unused in this implementation).
     */
    public void debugOnlyTrackBuffer(DefaultHybridCache cache) {
        // This is a debug-only method in the C# implementation
        // In Java, we'll make it a no-op for production builds
        // In a debug build, you could add logging or tracking here
    }

    /**
     * Called when the item's reference count drops to zero.
     * Recycles the underlying buffer if it's from a pool.
     */
    @Override
    protected void onFinalRelease() {
        if (buffer != null) {
            buffer.recycleIfAppropriate();
        }
    }
}