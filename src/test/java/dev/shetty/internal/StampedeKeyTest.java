package dev.shetty.internal;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

public class StampedeKeyTest {
    @Test
    void equalityUsesKeyAndFlags() {
        EnumSet<HybridCacheEntryFlags> flags = EnumSet.of(HybridCacheEntryFlags.DISABLE_LOCAL_CACHE_READ);
        StampedeKey key1 = new StampedeKey("k", flags);
        StampedeKey key2 = new StampedeKey("k", EnumSet.of(HybridCacheEntryFlags.DISABLE_LOCAL_CACHE_READ));
        StampedeKey key3 = new StampedeKey("k", EnumSet.of(HybridCacheEntryFlags.DISABLE_DISTRIBUTED_CACHE_READ));

        assertEquals(key1, key2);
        assertEquals(key1.hashCode(), key2.hashCode());
        assertNotEquals(key1, key3);
    }

    @Test
    void toStringIncludesKey() {
        StampedeKey key = new StampedeKey("my-key", EnumSet.noneOf(HybridCacheEntryFlags.class));
        assertTrue(key.toString().contains("my-key"));
    }
}
