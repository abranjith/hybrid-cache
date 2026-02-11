package dev.shetty.internal;

import java.util.EnumSet;
import java.util.Objects;

/**
 * A key class used to identify and prevent cache stampedes.
 * <p>
 * Combines the cache key with the entry flags so that conflicting calls
 * (e.g., one disabling L2, another not) are treated as separate stampede groups.
 */
public final class StampedeKey {
    private final String key;
    private final EnumSet<HybridCacheEntryFlags> flags;
    private final int hashCode; // pre-compute hash code for performance

    /**
     * Creates a new StampedeKey with the specified key and flags.
     *
     * @param key   The cache key
     * @param flags The cache entry flags (combined)
     */
    public StampedeKey(String key, EnumSet<HybridCacheEntryFlags> flags) {
        this.key = Objects.requireNonNull(key, "key cannot be null");
        this.flags = Objects.requireNonNull(flags, "flags cannot be null");
        this.hashCode = Objects.hash(key, flags);
    }

    /**
     * Gets the key component.
     */
    public String getKey() {
        return key;
    }

    /**
     * Gets the flags component.
     */
    public EnumSet<HybridCacheEntryFlags> getFlags() {
        return flags;
    }

    /**
     * Gets the pre-computed hash code.
     */
    int getHashCode() {
        return hashCode;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof StampedeKey other)) return false;
        return flags.equals(other.flags) & key.equals(other.key);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return key + " (" + flags + ")";
    }
}