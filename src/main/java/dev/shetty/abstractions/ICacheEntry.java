package dev.shetty.abstractions;

import dev.shetty.internal.CacheItemPriority;

import java.io.Closeable;
import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Represents an entry in the Cache implementation.
 * When closed, is committed to the cache.
 */
public interface ICacheEntry extends Closeable {
    /**
     * Gets the key of the cache entry.
     */
    Object getKey();

    /**
     * Gets or sets the value of the cache entry.
     */
    Object getValue();
    void setValue(Object value);

    /**
     * Gets or sets an absolute expiration date for the cache entry.
     */
    OffsetDateTime getAbsoluteExpiration();
    void setAbsoluteExpiration(OffsetDateTime expiration);

    /**
     * Gets or sets an absolute expiration time, relative to now.
     */
    Duration getAbsoluteExpirationRelativeToNow();
    void setAbsoluteExpirationRelativeToNow(Duration duration);

    /**
     * Gets or sets how long a cache entry can be inactive before it will be removed.
     * This will not extend the entry lifetime beyond the absolute expiration (if set).
     */
    Duration getSlidingExpiration();
    void setSlidingExpiration(Duration duration);

    /*  TODO solve this
    // Gets the IChangeToken instances which cause the cache entry to expire.
    List<IChangeToken> getExpirationTokens();
    */

    /* TODO solve this
     //Gets or sets the callbacks that will be fired after the cache entry is evicted from the cache.
    List<PostEvictionCallbackRegistration> getPostEvictionCallbacks();
    */

    /**
     * Gets or sets the priority for keeping the cache entry in the cache during a cleanup.
     * The default is CacheItemPriority.Normal.
     */
    CacheItemPriority getPriority();
    void setPriority(CacheItemPriority priority);

    /**
     * Gets or sets the size of the cache entry value.
     */
    Long getSize();
    void setSize(Long size);

    @Override
    void close();
}
