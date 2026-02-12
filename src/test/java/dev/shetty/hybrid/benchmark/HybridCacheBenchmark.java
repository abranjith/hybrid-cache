package dev.shetty.hybrid.benchmark;

import dev.shetty.abstractions.ICacheEntry;
import dev.shetty.abstractions.IDistributedCache;
import dev.shetty.abstractions.IHybridCacheSerializer;
import dev.shetty.abstractions.IHybridCacheSerializerFactory;
import dev.shetty.abstractions.IMemoryCache;
import dev.shetty.hybrid.DefaultHybridCache;
import dev.shetty.internal.CacheEntryOptions;
import dev.shetty.internal.CacheItemPriority;
import dev.shetty.internal.CancellationToken;
import dev.shetty.internal.HybridCacheEntryFlags;
import dev.shetty.internal.HybridCacheEntryOptions;
import dev.shetty.internal.HybridCacheOptions;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * JMH benchmarks for {@link DefaultHybridCache}.
 * <p>
 * Run via main() or with: mvn test-compile exec:java -Dexec.mainClass="dev.shetty.hybrid.benchmark.HybridCacheBenchmark"
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(0)
public class HybridCacheBenchmark {

    private DefaultHybridCache cacheL1Only;
    private DefaultHybridCache cacheL1L2;
    private AtomicInteger counter;

    /**
     * Number of distinct keys to pre-populate for hit benchmarks.
     */
    @Param({"100", "10000"})
    private int keyCount;

    @Setup(Level.Trial)
    public void setup() {
        HybridCacheOptions options = new HybridCacheOptions();
        options.setDefaultEntryOptions(new HybridCacheEntryOptions(
                Duration.ofMinutes(5),
                Duration.ofSeconds(30),
                EnumSet.noneOf(HybridCacheEntryFlags.class)
        ));

        cacheL1Only = new DefaultHybridCache(
                options,
                new InMemoryMemoryCache(),
                null,
                Logger.getLogger("bench"),
                List.of(new StringSerializerFactory())
        );

        cacheL1L2 = new DefaultHybridCache(
                options,
                new InMemoryMemoryCache(),
                new InMemoryDistributedCache(),
                Logger.getLogger("bench"),
                List.of(new StringSerializerFactory())
        );

        counter = new AtomicInteger();

        // Pre-populate caches
        for (int i = 0; i < keyCount; i++) {
            String key = "preloaded-" + i;
            String value = "value-" + i;
            cacheL1Only.getOrCreate(key, value,
                    (v, ct) -> v, null, null, CancellationToken.NONE);
            cacheL1L2.getOrCreate(key, value,
                    (v, ct) -> v, null, null, CancellationToken.NONE);
        }
    }

    // ---- L1-only benchmarks ----

    @Benchmark
    @Threads(1)
    public String l1Only_cacheHit_singleThread() {
        String key = "preloaded-" + ThreadLocalRandom.current().nextInt(keyCount);
        return cacheL1Only.getOrCreate(key, counter,
                (state, ct) -> "miss", null, null, CancellationToken.NONE);
    }

    @Benchmark
    @Threads(8)
    public String l1Only_cacheHit_8threads() {
        String key = "preloaded-" + ThreadLocalRandom.current().nextInt(keyCount);
        return cacheL1Only.getOrCreate(key, counter,
                (state, ct) -> "miss", null, null, CancellationToken.NONE);
    }

    @Benchmark
    @Threads(1)
    public String l1Only_cacheMiss_singleThread() {
        String key = "miss-" + ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE);
        return cacheL1Only.getOrCreate(key, counter,
                (state, ct) -> "new-value", null, null, CancellationToken.NONE);
    }

    @Benchmark
    @Threads(8)
    public String l1Only_cacheMiss_8threads() {
        String key = "miss-" + ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE);
        return cacheL1Only.getOrCreate(key, counter,
                (state, ct) -> "new-value", null, null, CancellationToken.NONE);
    }

    // ---- L1+L2 benchmarks ----

    @Benchmark
    @Threads(1)
    public String l1l2_cacheHit_singleThread() {
        String key = "preloaded-" + ThreadLocalRandom.current().nextInt(keyCount);
        return cacheL1L2.getOrCreate(key, counter,
                (state, ct) -> "miss", null, null, CancellationToken.NONE);
    }

    @Benchmark
    @Threads(8)
    public String l1l2_cacheHit_8threads() {
        String key = "preloaded-" + ThreadLocalRandom.current().nextInt(keyCount);
        return cacheL1L2.getOrCreate(key, counter,
                (state, ct) -> "miss", null, null, CancellationToken.NONE);
    }

    @Benchmark
    @Threads(8)
    public String l1l2_cacheMiss_8threads() {
        String key = "miss-" + ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE);
        return cacheL1L2.getOrCreate(key, counter,
                (state, ct) -> "new-value", null, null, CancellationToken.NONE);
    }

    // ---- Stampede contention benchmark ----

    /**
     * All threads hit the same key, forcing stampede protection to serialize factory calls.
     */
    @Benchmark
    @Threads(16)
    public String stampede_sameKey_16threads() {
        return cacheL1L2.getOrCreate("contended-key", counter,
                (state, ct) -> "shared-value", null, null, CancellationToken.NONE);
    }

    // ---- Mixed read/write benchmark ----

    @Benchmark
    @Threads(8)
    public String mixedReadWrite_8threads() {
        int r = ThreadLocalRandom.current().nextInt(100);
        if (r < 80) {
            // 80% reads from pre-populated keys
            String key = "preloaded-" + ThreadLocalRandom.current().nextInt(keyCount);
            return cacheL1L2.getOrCreate(key, counter,
                    (state, ct) -> "miss", null, null, CancellationToken.NONE);
        } else if (r < 95) {
            // 15% cache misses (new keys)
            String key = "new-" + ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE);
            return cacheL1L2.getOrCreate(key, counter,
                    (state, ct) -> "new-value", null, null, CancellationToken.NONE);
        } else {
            // 5% removes
            String key = "preloaded-" + ThreadLocalRandom.current().nextInt(keyCount);
            cacheL1L2.remove(key, CancellationToken.NONE);
            return null;
        }
    }

    // ---- Runner ----

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(HybridCacheBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }

    // ==== Test doubles (same as DefaultHybridCacheTest) ====

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
        public void refresh(String key) { }

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

            @Override public Object getKey() { return key; }
            @Override public Object getValue() { return value; }
            @Override public void setValue(Object value) { this.value = value; }
            @Override public OffsetDateTime getAbsoluteExpiration() { return absoluteExpiration; }
            @Override public void setAbsoluteExpiration(OffsetDateTime expiration) { this.absoluteExpiration = expiration; }
            @Override public Duration getAbsoluteExpirationRelativeToNow() { return absoluteExpirationRelativeToNow; }
            @Override public void setAbsoluteExpirationRelativeToNow(Duration duration) { this.absoluteExpirationRelativeToNow = duration; }
            @Override public Duration getSlidingExpiration() { return slidingExpiration; }
            @Override public void setSlidingExpiration(Duration duration) { this.slidingExpiration = duration; }
            @Override public CacheItemPriority getPriority() { return CacheItemPriority.NORMAL; }
            @Override public void setPriority(CacheItemPriority priority) { }
            @Override public Long getSize() { return null; }
            @Override public void setSize(Long size) { }

            @Override
            public void close() {
                cache.setFromEntry(key, value, absoluteExpirationRelativeToNow);
            }
        }
    }
}
