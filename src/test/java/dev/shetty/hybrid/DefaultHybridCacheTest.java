package dev.shetty.hybrid;

import dev.shetty.abstractions.ICacheEntry;
import dev.shetty.abstractions.IDistributedCache;
import dev.shetty.abstractions.IHybridCacheSerializer;
import dev.shetty.abstractions.IHybridCacheSerializerFactory;
import dev.shetty.abstractions.IMemoryCache;
import dev.shetty.internal.CancellationToken;
import dev.shetty.internal.HybridCacheEntryOptions;
import dev.shetty.internal.HybridCacheOptions;
import dev.shetty.internal.HybridCacheEntryFlags;
import dev.shetty.internal.CacheEntryOptions;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

public class DefaultHybridCacheTest {
    private static DefaultHybridCache createCache(IMemoryCache memory, IDistributedCache distributed) {
        HybridCacheOptions options = new HybridCacheOptions();
        HybridCacheEntryOptions defaults = new HybridCacheEntryOptions(
            Duration.ofSeconds(30),
            Duration.ofSeconds(10),
            EnumSet.noneOf(HybridCacheEntryFlags.class)
        );
        options.setDefaultEntryOptions(defaults);
        return new DefaultHybridCache(
            options,
            memory,
            distributed,
            Logger.getLogger("test"),
            List.of(new StringSerializerFactory())
        );
    }

    @Test
    void getOrCreateUsesL1Cache() {
        InMemoryMemoryCache memoryCache = new InMemoryMemoryCache();
        DefaultHybridCache cache = createCache(memoryCache, null);
        AtomicInteger counter = new AtomicInteger();

        String value1 = cache.getOrCreate("key",
            counter,
            (state, ct) -> {
                state.incrementAndGet();
                return "value";
            },
            null,
            null,
            CancellationToken.NONE
        );

        String value2 = cache.getOrCreate("key",
            counter,
            (state, ct) -> {
                state.incrementAndGet();
                return "value";
            },
            null,
            null,
            CancellationToken.NONE
        );

        assertEquals("value", value1);
        assertEquals("value", value2);
        assertEquals(1, counter.get());
    }

    @Test
    void getOrCreateReadsFromL2() {
        InMemoryDistributedCache distributedCache = new InMemoryDistributedCache();
        DefaultHybridCache cache1 = createCache(new InMemoryMemoryCache(), distributedCache);

        AtomicInteger counter1 = new AtomicInteger();
        String initial = cache1.getOrCreate("l2-key",
            counter1,
            (state, ct) -> {
                state.incrementAndGet();
                return "l2-value";
            },
            null,
            null,
            CancellationToken.NONE
        );

        assertEquals("l2-value", initial);
        assertEquals(1, counter1.get());

        DefaultHybridCache cache2 = createCache(new InMemoryMemoryCache(), distributedCache);
        AtomicInteger counter2 = new AtomicInteger();

        String fromL2 = cache2.getOrCreate("l2-key",
            counter2,
            (state, ct) -> {
                state.incrementAndGet();
                return "should-not-run";
            },
            null,
            null,
            CancellationToken.NONE
        );

        assertEquals("l2-value", fromL2);
        assertEquals(0, counter2.get());
    }

    @Test
    void stampedeAllowsSingleFactoryExecution() throws Exception {
        DefaultHybridCache cache = createCache(new InMemoryMemoryCache(), new InMemoryDistributedCache());
        AtomicInteger counter = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> first = executor.submit(() -> cache.getOrCreate(
                "stampede",
                counter,
                (state, ct) -> {
                    state.incrementAndGet();
                    started.countDown();
                    try {
                        release.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "shared";
                },
                null,
                null,
                CancellationToken.NONE
            ));

            assertTrue(started.await(2, TimeUnit.SECONDS));

            Future<String> second = executor.submit(() -> cache.getOrCreate(
                "stampede",
                counter,
                (state, ct) -> {
                    state.incrementAndGet();
                    return "shared";
                },
                null,
                null,
                CancellationToken.NONE
            ));

            release.countDown();

            assertEquals("shared", first.get(2, TimeUnit.SECONDS));
            assertEquals("shared", second.get(2, TimeUnit.SECONDS));
        }

        assertEquals(1, counter.get());
    }

    private static class StringSerializer implements IHybridCacheSerializer<String> {
        @Override
        public String deserialize(ByteBuffer source) {
            byte[] data = new byte[source.remaining()];
            source.get(data);
            return new String(data, StandardCharsets.UTF_8);
        }

        @Override
        public byte[] serialize(String value) {
            return value.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static class StringSerializerFactory implements IHybridCacheSerializerFactory {
        @SuppressWarnings("unchecked")
        @Override
        public <T> IHybridCacheSerializer<T> tryCreateSerializer(Class<T> type) {
            if (type == String.class) {
                return (IHybridCacheSerializer<T>) new StringSerializer();
            }
            return null;
        }
    }

    private static class InMemoryDistributedCache implements IDistributedCache {
        private final Map<String, byte[]> store = new ConcurrentHashMap<>();

        @Override
        public byte[] get(String key) {
            return store.get(key);
        }

        @Override
        public void set(String key, byte[] value, CacheEntryOptions options) {
            store.put(key, value);
        }

        @Override
        public void refresh(String key) {
            // No-op for test
        }

        @Override
        public void remove(String key) {
            store.remove(key);
        }
    }

    private static class InMemoryMemoryCache implements IMemoryCache {
        private final Map<Object, Entry> store = new ConcurrentHashMap<>();

        @Override
        public Optional<Object> get(Object key) {
            Entry entry = store.get(key);
            if (entry == null) {
                return Optional.empty();
            }
            if (entry.hasExpiration && System.currentTimeMillis() > entry.expiresAtMillis) {
                store.remove(key);
                return Optional.empty();
            }
            return Optional.ofNullable(entry.value);
        }

        @Override
        public ICacheEntry set(Object key) {
            return new InMemoryCacheEntry(key, this);
        }

        @Override
        public void set(Object key, Object value, Duration absoluteExpirationRelativeToNow) {
            Entry entry = new Entry();
            entry.value = value;
            if (absoluteExpirationRelativeToNow != null) {
                entry.hasExpiration = true;
                entry.expiresAtMillis = System.currentTimeMillis() + absoluteExpirationRelativeToNow.toMillis();
            }
            store.put(key, entry);
        }

        @Override
        public void remove(Object key) {
            store.remove(key);
        }

        private void setFromEntry(Object key, Object value, Duration absoluteExpirationRelativeToNow) {
            set(key, value, absoluteExpirationRelativeToNow);
        }

        private static class Entry {
            Object value;
            boolean hasExpiration;
            long expiresAtMillis;
        }

        private static class InMemoryCacheEntry implements ICacheEntry {
            private final Object key;
            private final InMemoryMemoryCache cache;
            private Object value;
            private OffsetDateTime absoluteExpiration;
            private Duration absoluteExpirationRelativeToNow;
            private Duration slidingExpiration;

            InMemoryCacheEntry(Object key, InMemoryMemoryCache cache) {
                this.key = key;
                this.cache = cache;
            }

            @Override
            public Object getKey() {
                return key;
            }

            @Override
            public Object getValue() {
                return value;
            }

            @Override
            public void setValue(Object value) {
                this.value = value;
            }

            @Override
            public OffsetDateTime getAbsoluteExpiration() {
                return absoluteExpiration;
            }

            @Override
            public void setAbsoluteExpiration(OffsetDateTime expiration) {
                this.absoluteExpiration = expiration;
            }

            @Override
            public Duration getAbsoluteExpirationRelativeToNow() {
                return absoluteExpirationRelativeToNow;
            }

            @Override
            public void setAbsoluteExpirationRelativeToNow(Duration duration) {
                this.absoluteExpirationRelativeToNow = duration;
            }

            @Override
            public Duration getSlidingExpiration() {
                return slidingExpiration;
            }

            @Override
            public void setSlidingExpiration(Duration duration) {
                this.slidingExpiration = duration;
            }

            @Override
            public dev.shetty.internal.CacheItemPriority getPriority() {
                return dev.shetty.internal.CacheItemPriority.NORMAL;
            }

            @Override
            public void setPriority(dev.shetty.internal.CacheItemPriority priority) {
                // Not required for tests
            }

            @Override
            public Long getSize() {
                return null;
            }

            @Override
            public void setSize(Long size) {
                // Not required for tests
            }

            @Override
            public void close() {
                cache.setFromEntry(key, value, absoluteExpirationRelativeToNow);
            }
        }
    }
}
