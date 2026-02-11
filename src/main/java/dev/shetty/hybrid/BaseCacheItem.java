package dev.shetty.hybrid;

import dev.shetty.internal.BufferChunk;
import dev.shetty.internal.TagSet;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

public abstract class BaseCacheItem {
    private final AtomicLong creationTimestamp;
    private final AtomicInteger refCount = new AtomicInteger(1);
    private TagSet tags = TagSet.EMPTY;

    protected BaseCacheItem(long creationTimestamp) {
        this.creationTimestamp = new AtomicLong(creationTimestamp);
    }
    
    protected BaseCacheItem(long creationTimestamp, TagSet tags) {
        this.creationTimestamp = new AtomicLong(creationTimestamp);
        this.tags = tags != null ? tags : TagSet.EMPTY;
    }

    public abstract boolean isDebugImmutable();

    public long getCreationTimestamp() {
        return creationTimestamp.get();
    }
    
    public TagSet getTags() {
        return tags;
    }
    
    public void setTags(TagSet tags) {
        this.tags = tags != null ? tags : TagSet.EMPTY;
    }

    public int getRefCount() {
        return refCount.get();
    }

    /**
     * Shared eviction callback.
     */
    public static final BiConsumer<Object, Object> SHARED_ON_EVICTION = (key, value) -> {
        if (value instanceof BaseCacheItem item) {
            item.release();
        }
    };

    /**
     * Whether this item needs an eviction callback.
     */
    public boolean needsEvictionCallback() {
        return false;
    }

    /**
     * Try to reserve a buffer for this item.
     */
    public abstract Optional<BufferChunk> tryReserveBuffer();

    /**
     * Signal that the consumer is done with this item (ref-count decr).
     *
     * @return true if this is the final release.
     */
    public boolean release() {
        int newCount = refCount.decrementAndGet();
        if (newCount == 0) {
            onFinalRelease();
            return true;
        }
        return false;
    }

    /**
     * Try to increment the ref count, but not if it is zero or negative.
     */
    public boolean tryReserve() {
        int oldValue = refCount.get();
        while (true) {
            if (oldValue == 0 || oldValue == -1) {
                return false;
            }
            if (refCount.compareAndSet(oldValue, oldValue + 1)) {
                return true;
            }
            oldValue = refCount.get();
        }
    }

    /**
     * Checks if this cache item is still valid.
     * @return true if valid, false otherwise.
     */
    public boolean isValid() {
        return refCount.get() > 0;
    }

    /**
     * Unsafe method to set the creation timestamp.
     * This should only be used in special circumstances for timestamp collision resolution.
     * @param timestamp The new timestamp.
     */
    public void unsafeSetCreationTimestamp(long timestamp) {
        this.creationTimestamp.set(timestamp);
    }

    /**
     * Called when the ref count reaches zero.
     */
    protected void onFinalRelease() {
        // Override for cleanup
    }
}
