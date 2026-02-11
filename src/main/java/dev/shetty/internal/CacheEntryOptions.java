package dev.shetty.internal;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Provides the cache options for an entry in IDistributedCache.
 */
public class CacheEntryOptions {
    private OffsetDateTime absoluteExpiration;
    private boolean absoluteExpirationSet;
    private Duration absoluteExpirationRelativeToNow;
    private boolean absoluteExpirationRelativeToNowSet;
    private Duration slidingExpiration;
    private boolean slidingExpirationSet;
    private boolean frozen;

    /**
     * Gets or sets an absolute expiration date for the cache entry.
     */
    public OffsetDateTime getAbsoluteExpiration() {
        return absoluteExpirationSet ? absoluteExpiration : null;
    }

    public void setAbsoluteExpiration(OffsetDateTime value) {
        setField("absoluteExpiration", value);
    }

    /**
     * Gets or sets an absolute expiration time, relative to now.
     */
    public Duration getAbsoluteExpirationRelativeToNow() {
        return absoluteExpirationRelativeToNowSet ? absoluteExpirationRelativeToNow : null;
    }

    public void setAbsoluteExpirationRelativeToNow(Duration value) {
        if (value != null && !value.isZero() && value.isNegative()) {
            throw new IllegalArgumentException("The relative expiration value must be positive.");
        }
        setField("absoluteExpirationRelativeToNow", value);
    }

    /**
     * Gets or sets how long a cache entry can be inactive before it will be removed.
     * This will not extend the entry lifetime beyond the absolute expiration (if set).
     */
    public Duration getSlidingExpiration() {
        return slidingExpirationSet ? slidingExpiration : null;
    }

    public void setSlidingExpiration(Duration value) {
        if (value != null && !value.isZero() && value.isNegative()) {
            throw new IllegalArgumentException("The sliding expiration value must be positive.");
        }
        setField("slidingExpiration", value);
    }

    /**
     * Freezes this instance, making it immutable.
     * @return this instance
     */
    CacheEntryOptions freeze() {
        this.frozen = true;
        return this;
    }

    // Helper method to set fields and track if set, with frozen check
    private void setField(String fieldName, Object value) {
        if (frozen) {
            throw new IllegalStateException("This instance has been frozen and cannot be mutated");
        }
        switch (fieldName) {
            case "absoluteExpiration":
                this.absoluteExpiration = (OffsetDateTime) value;
                this.absoluteExpirationSet = value != null;
                break;
            case "absoluteExpirationRelativeToNow":
                this.absoluteExpirationRelativeToNow = (Duration) value;
                this.absoluteExpirationRelativeToNowSet = value != null;
                break;
            case "slidingExpiration":
                this.slidingExpiration = (Duration) value;
                this.slidingExpirationSet = value != null;
                break;
        }
    }
}
