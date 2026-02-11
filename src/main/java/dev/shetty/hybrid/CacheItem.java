package dev.shetty.hybrid;

import dev.shetty.internal.TagSet;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * Represents a typed item in the cache, extending the base CacheItem.
 * This class is abstract and serves as the foundation for mutable and immutable cache items.
 * It corresponds to the C# internal abstract class DefaultHybridCache.CacheItem<T>.
 *
 * @param <T> The type of the value stored in the cache item.
 */
public abstract class CacheItem<T> extends BaseCacheItem {

    protected CacheItem(long creationTimestamp) {
        super(creationTimestamp);
    }
    
    protected CacheItem(long creationTimestamp, TagSet tags) {
        super(creationTimestamp, tags);
    }

    /**
     * Tries to get the size of the cached item.
     * The C# 'out' parameter is replaced by returning an OptionalLong.
     */
    public abstract OptionalLong tryGetSize();

    /**
     * Tries to get the value from the cache item.
     * The C# 'out' parameter is replaced by returning an Optional<T>.
     *
     * @return An Optional containing the value if successful, otherwise an empty Optional.
     */
    public abstract Optional<T> tryGetValue();

    /**
     * Gets a value that was previously reserved. This action consumes the reservation.
     *
     * @return The value of the cache item.
     * @throws IllegalStateException if the item has been recycled before the value is retrieved.
     */
    public T getReservedValue() {
        Optional<T> valueOpt = tryGetValue();
        if (valueOpt.isEmpty()) {
            throw new IllegalStateException("The cache item has been recycled before the value was obtained.");
        }

        release(); // Consume the reservation by releasing the item.
        return valueOpt.get();
    }

    /**
     * Gets a value that was previously reserved. This action consumes the reservation.
     *
     * @param log The logger instance (unused in this implementation, for compatibility).
     * @return The value of the cache item.
     * @throws IllegalStateException if the item has been recycled before the value is retrieved.
     */
    public T getReservedValue(java.util.logging.Logger log) {
        return getReservedValue(); // Delegate to the existing method
    }

    /**
     * Factory method to create a CacheItem<T>.
     * Due to Java's type erasure, this method requires a Class<T> token to make
     * runtime decisions about mutability.
     *
     * @param <T> The type of the value.
     * @param creationTimestamp The timestamp of creation.
     * @param type The class of the value, used to check for immutability.
     * @return A new CacheItem<T>, either mutable or immutable.
     */
    public static <T> CacheItem<T> create(long creationTimestamp, Class<T> type) {
        if (ImmutableTypeCache.isImmutable(type)) {
            return new ImmutableCacheItem<T>(creationTimestamp);
        } else {
            return new MutableCacheItem<T>(creationTimestamp);
        }
    }
    
    /**
     * Factory method to create a CacheItem<T> with tags.
     * Creates immutable or mutable cache items based on runtime inspection.
     * Note: Without a Class<T> token, we default to mutable.
     *
     * @param <T> The type of the value.
     * @param creationTimestamp The timestamp of creation.
     * @param tags The tags for the cache entry.
     * @return A new CacheItem<T>.
     */
    public static <T> CacheItem<T> create(long creationTimestamp, TagSet tags) {
        // Without type information, we default to mutable
        return new MutableCacheItem<T>(creationTimestamp, tags);
    }
}