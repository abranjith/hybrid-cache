# hybrid-cache

A Java implementation of .NET's [HybridCache](https://learn.microsoft.com/en-us/aspnet/core/performance/caching/hybrid) — a multi-tier caching library with L1 (in-process) + L2 (distributed) architecture, stampede protection, and configurable serialization.

## The Problem

Modern applications need caching at multiple levels. An in-process memory cache (L1) is fast but limited to a single instance and lost on restart. A distributed cache (L2) like Redis survives restarts and is shared across instances, but every access involves network I/O and serialization overhead.

Developers typically end up writing ad-hoc glue code that:
- Checks L1 first, falls back to L2, then calls the data source
- Serializes/deserializes for the distributed tier
- Handles cache stampedes (thundering herd) when many threads request the same uncached key simultaneously
- Manages cache invalidation across both tiers

**hybrid-cache** solves all of this in a single, composable library with a synchronous API designed for Java 21+ virtual threads.

## Features

- **Two-tier caching** — automatic L1 (memory) + L2 (distributed) with a single API call
- **Stampede protection** — concurrent requests for the same key share a single in-flight operation; the factory executes exactly once
- **Synchronous API** — no `CompletableFuture` chains; blocking is free on virtual threads
- **Cooperative cancellation** — `CancellationToken` for aborting long-running factory operations
- **Tag-based invalidation** — invalidate groups of related cache entries by tag
- **Configurable serialization** — plug in any serializer via `IHybridCacheSerializer<T>` / `IHybridCacheSerializerFactory`
- **Binary wire format** — compact L2 payload with sentinel validation, varint encoding, and integrity checks
- **Pooled buffer management** — `ByteArrayPool` and `BufferChunk` minimize GC pressure via thread-local + per-CPU partitioned pooling
- **Immutable type optimization** — immutable types (primitives, `String`, records, `@ImmutableObject`) are cached by reference without defensive copies
- **Zero external dependencies** — built entirely on `java.util.concurrent` and standard library APIs

## Quick Start

### Requirements

- Java 21+ (virtual threads required)
- Maven 3.8+

### Add to your project

```xml
<dependency>
    <groupId>dev.shetty</groupId>
    <artifactId>hybrid-cache</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

### Basic usage

```java
import dev.shetty.hybrid.DefaultHybridCache;
import dev.shetty.internal.HybridCacheOptions;

// 1. Create the cache (typically done once at startup)
var options = new HybridCacheOptions();
var cache = new DefaultHybridCache(options, myMemoryCache, myDistributedCache, null);

// 2. Get-or-create: fetch from cache, or invoke the factory on a miss
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    executor.submit(() -> {
        String user = cache.getOrCreate("user:123", ct -> {
            // This factory runs only on a cache miss.
            // ct is a CancellationToken for cooperative cancellation.
            return userService.loadUser("123");
        });
        process(user);
    });
}
```

### Direct set and remove

```java
// Store a value directly (overwrites any existing entry)
cache.set("config:feature-flags", featureFlags);

// Remove a single key
cache.remove("user:123");

// Remove by tag
cache.removeByTag("team:engineering");
```

### With options and tags

```java
import dev.shetty.internal.HybridCacheEntryOptions;
import dev.shetty.internal.HybridCacheEntryFlags;
import dev.shetty.internal.CancellationToken;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;

var entryOptions = new HybridCacheEntryOptions(
    Duration.ofMinutes(10),              // overall expiration
    Duration.ofMinutes(2),               // local cache expiration
    EnumSet.noneOf(HybridCacheEntryFlags.class)
);

CancellationToken ct = new CancellationToken();

String product = cache.getOrCreate(
    "product:456",
    ct2 -> productService.loadProduct("456"),
    entryOptions,
    List.of("catalog", "team:merchandising"),  // tags for grouped invalidation
    ct
);
```

### State-passing overload (avoid closure allocations)

```java
// The state (productId) is passed explicitly instead of captured in a lambda closure.
// This avoids allocating a new closure object per call — useful in hot paths.
String productId = "456";

String product = cache.getOrCreate(
    "product:" + productId,
    productId,                                          // state
    (id, ct2) -> productService.loadProduct(id),        // factory receives state + token
    entryOptions,
    List.of("catalog"),
    CancellationToken.NONE
);
```

---

## Detailed Walkthrough

### API Reference

#### `HybridCache` (abstract class)

The public API surface. All methods are synchronous — callers are expected to use virtual threads for non-blocking I/O.

| Method | Description |
|---|---|
| `<TState, T> T getOrCreate(key, state, factory, options, tags, ct)` | Primary method. Checks L1, then L2, then invokes the factory. State is passed to avoid closures. |
| `<T> T getOrCreate(key, factory, options, tags, ct)` | Convenience overload without state. |
| `<T> T getOrCreate(key, factory)` | Minimal overload — defaults for options, tags, and cancellation. |
| `<T> void set(key, value, options, tags, ct)` | Writes directly to L1 and L2. |
| `<T> void set(key, value)` | Minimal set overload. |
| `void remove(key, ct)` | Removes from L1 and L2. |
| `void remove(key)` | Remove without cancellation token. |
| `void remove(Collection<String> keys, ct)` | Batch remove. |
| `void removeByTag(tag, ct)` | Invalidates all entries associated with the tag. |
| `void removeByTag(tag)` | Tag removal without cancellation token. |
| `void removeByTag(Collection<String> tags, ct)` | Batch tag invalidation. |

**Parameters:**

| Parameter | Type | Description |
|---|---|---|
| `key` | `String` | Cache key. Must be non-blank, ≤ `maximumKeyLength` (default 1024), no control characters. |
| `state` | `TState` | Arbitrary state passed through to the factory to avoid closure allocation. |
| `factory` | `BiFunction<TState, CancellationToken, T>` | Produces the value on a cache miss. Receives state and a cancellation token. |
| `options` | `HybridCacheEntryOptions` | Expiration, local cache expiration, and flags. `null` for defaults. |
| `tags` | `Collection<String>` | Tags for grouped invalidation. `null` or empty for none. |
| `ct` | `CancellationToken` | Cooperative cancellation. Use `CancellationToken.NONE` when not needed. |

#### `HybridCacheEntryOptions`

| Field | Type | Default | Description |
|---|---|---|---|
| `expiration` | `Duration` | 5 minutes | Overall cache duration (L2 TTL). |
| `localCacheExpiration` | `Duration` | Same as `expiration` | L1 TTL, capped at the remaining overall lifetime. |
| `flags` | `EnumSet<HybridCacheEntryFlags>` | Empty | Controls which cache tiers to read/write. |

#### `HybridCacheEntryFlags`

| Flag | Effect |
|---|---|
| `DISABLE_LOCAL_CACHE_READ` | Skip L1 on read. |
| `DISABLE_LOCAL_CACHE_WRITE` | Don't store in L1 after fetch. |
| `DISABLE_DISTRIBUTED_CACHE_READ` | Skip L2 on read. |
| `DISABLE_DISTRIBUTED_CACHE_WRITE` | Don't store in L2 after fetch. |
| `DISABLE_UNDERLYING_DATA` | Don't invoke the factory — only return data already cached. |
| `DISABLE_COMPRESSION` | Skip payload compression (if applicable). |

#### `HybridCacheOptions`

Global defaults for `DefaultHybridCache`:

| Field | Type | Default | Description |
|---|---|---|---|
| `defaultEntryOptions` | `HybridCacheEntryOptions` | `null` | Default options applied to every operation. |
| `disableCompression` | `boolean` | `false` | Globally disable compression. |
| `maximumPayloadBytes` | `long` | 1 MiB | Maximum serialized payload size. |
| `maximumKeyLength` | `int` | 1024 | Maximum cache key length in characters. |
| `reportTagMetrics` | `boolean` | `false` | Enable tag operation metrics. |

#### `CancellationToken`

| Method | Description |
|---|---|
| `CancellationToken.NONE` | Shared singleton that is never cancelled. |
| `isCancelled()` | Returns `true` if cancellation was requested. |
| `cancel()` | Requests cancellation (thread-safe). |
| `throwIfCancelled()` | Throws `CancellationException` if cancelled. |

#### `IHybridCacheSerializer<T>`

Implement this interface to provide custom serialization for a type:

```java
public interface IHybridCacheSerializer<T> {
    T deserialize(ByteBuffer source);
    byte[] serialize(T value);
}
```

#### `IHybridCacheSerializerFactory`

Factory that produces type-specific serializers. Multiple factories can be registered and are tried in order:

```java
public interface IHybridCacheSerializerFactory {
    <T> IHybridCacheSerializer<T> tryCreateSerializer(Class<T> type);
}
```

#### `IMemoryCache` / `IDistributedCache`

Bring-your-own cache backends. The library doesn't ship concrete implementations — you provide adapters for your infrastructure:

```java
// L1 — in-process memory cache
public interface IMemoryCache {
    Optional<Object> get(Object key);
    ICacheEntry set(Object key);
    void set(Object key, Object value, Duration absoluteExpirationRelativeToNow);
    void remove(Object key);
}

// L2 — distributed cache (Redis, Memcached, etc.)
public interface IDistributedCache {
    byte[] get(String key);
    void set(String key, byte[] value, CacheEntryOptions options);
    void refresh(String key);
    void remove(String key);
}
```

---

### Architecture

#### High-Level Design

```
┌──────────────────────────────────────────────────────────┐
│                  Public API (Synchronous)                 │
│    T getOrCreate(key, factory, ...)                      │
│    void set(key, value, ...)                             │
│    void remove(key) / void removeByTag(tag)              │
├──────────────────────────────────────────────────────────┤
│                Stampede Coordination                      │
│    CompletableFuture<CacheItem<T>> (internal only)       │
│    Multiple callers share a single in-flight operation   │
├──────────────────────────────────────────────────────────┤
│          L1 (IMemoryCache)    L2 (IDistributedCache)     │
│          In-process cache     Distributed/network cache  │
│          Reference-based      Serialized byte[]          │
└──────────────────────────────────────────────────────────┘
```

#### Request Flow: `getOrCreate`

```
  Caller
    │
    ▼
  validate key
    │
    ▼
  ┌──────────────┐    hit
  │   L1 lookup  │──────────► return cached value
  └──────┬───────┘
         │ miss
         ▼
  ┌──────────────────────┐
  │  Stampede check       │
  │  (ConcurrentHashMap)  │
  └──────┬───────┬───────┘
         │       │
    initiator   joiner
         │       │
         ▼       ▼
  ┌──────────┐  join()  ┌──────────────────┐
  │ L2 read  │◄────────►│ Wait on shared   │
  │ (if miss)│          │ CompletableFuture │
  │ factory()│          └──────────────────┘
  │ serialize│
  │ L1 write │
  │ L2 write │
  └──────────┘
         │
         ▼
  return value (to all waiters)
```

#### Stampede Protection

When multiple threads request the same uncached key simultaneously, only one thread (the **initiator**) executes the factory. All other threads (the **joiners**) block on a shared `CompletableFuture` — which is cheap on virtual threads since the JVM unmounts the virtual thread from its carrier during the wait.

Key components:

| Class | Role |
|---|---|
| `StampedeKey` | Composite key: cache key + `EnumSet<HybridCacheEntryFlags>`. Different flag combinations are treated as separate stampede groups. |
| `BaseStampedeState` | Abstract base with ref-counting (`tryAddCaller()`, `cancelCaller()`). Tracks how many callers are waiting. |
| `StampedeState<TState, T>` | Typed stampede state. Holds the `CompletableFuture<CacheItem<T>>` that joiners wait on. Executes the background fetch (L2 read → factory → serialize → L1/L2 write). |
| `PartitionedSyncLock` | 8 `ReentrantLock` instances selected by `hashCode & 0x7`. Minimizes contention when multiple keys are being coordinated simultaneously. |
| `ConcurrentHashMap<StampedeKey, StampedeState>` | The stampede registry. Lock-free check first, then partitioned lock for insert coordination. |

The coordination flow:

1. **Lock-free check**: `stampedeStates.get(key)` — if found and same type, `tryAddCaller()` to join
2. **Speculative create**: allocate a new `StampedeState`, `putIfAbsent()` into the map
3. **Partitioned lock fallback**: on contention, acquire partitioned lock, re-check, insert or join

#### Cache Items and Immutability

The library distinguishes between **immutable** and **mutable** cached types:

| Type | Class | Storage | When returned |
|---|---|---|---|
| Immutable (`String`, primitives, records, `@ImmutableObject`) | `ImmutableCacheItem<T>` | Stores the value directly by reference | Returns the same object reference (zero-copy) |
| Mutable (everything else) | `MutableCacheItem<T>` | Stores serialized `BufferChunk` + serializer | Deserializes on each access (defensive copy) |

Immutability detection is handled by `ImmutableTypeCache`, which checks:
- Java primitives and their wrappers
- `String`, `UUID`, `Instant`, `LocalDate`, `LocalDateTime`
- Enums and records
- Types annotated with `@ImmutableObject(true)`

#### Binary Wire Format (`HybridCachePayload`)

L2 storage uses a compact binary format:

```
┌──────────┬──────────┬────────────┬──────────────┬──────────────┬──────────┐
│ Sentinel │ Entropy  │ Creation   │ Varint fields│ UTF-8 strings│ Trailing │
│ + Version│ (2B)     │ Time (8B)  │ (flags, size,│ (key, tags)  │ Sentinel │
│ (2B)     │          │            │  duration,   │ + payload    │          │
│          │          │            │  tag count)  │              │          │
└──────────┴──────────┴────────────┴──────────────┴──────────────┴──────────┘
```

- **Sentinel validation**: header and trailer bytes detect corruption and format mismatches
- **Varint encoding**: 7-bit variable-length integers minimize overhead for small values
- **Integrity checks**: key validation, expiration checks, tag expiration checks
- **Parse results**: `SUCCESS`, `FORMAT_NOT_RECOGNIZED`, `INVALID_DATA`, `INVALID_KEY`, `EXPIRED_BY_ENTRY`, etc.

#### Buffer Management

The library pools `byte[]` arrays to reduce GC pressure from serialization:

| Component | Purpose |
|---|---|
| `ByteArrayPool` | Thread-local + per-CPU partitioned pool for primitive `byte[]`. `take(minimumLength)` / `giveBack(array)`. |
| `BufferChunk` | Wraps a pooled `byte[]` with offset, length, and a return-to-pool flag. `recycleIfAppropriate()` returns the array to the pool when done. |
| `SharedArrayPool<T>` | Generic pooling for object arrays (used internally). |

`BufferChunk` is immutable — `doNotReturnToPool()` returns a **new** instance with the pool flag cleared, preserving the original.

#### Tag-Based Invalidation

Tags allow grouping related cache entries for bulk invalidation:

```java
// Cache entries tagged with "team:engineering"
cache.getOrCreate("user:1", ct -> loadUser(1), opts, List.of("team:engineering"), ct);
cache.getOrCreate("user:2", ct -> loadUser(2), opts, List.of("team:engineering"), ct);

// Invalidate all entries with this tag
cache.removeByTag("team:engineering");
```

Tags are stored efficiently using `TagSet`, which is optimized for the common cases:
- **0 tags**: singleton `TagSet.EMPTY` (no allocation)
- **1 tag**: stores a single `String` (no array allocation)
- **N tags**: sorted `String[]` for consistent hashing

#### Serialization Pipeline

1. `DefaultHybridCache` maintains a `ConcurrentHashMap<Class<?>, IHybridCacheSerializer<?>>` for resolved serializers
2. On first use of a type, each registered `IHybridCacheSerializerFactory` is queried in order
3. The first non-null serializer is cached for subsequent lookups
4. Serialized bytes are written through `HybridCachePayload` format for L2 storage

---

### Best Practices

#### Use Virtual Threads

The API is synchronous by design. Blocking on L2 I/O is free on virtual threads — the JVM unmounts the carrier thread during the wait. Always dispatch cache operations from virtual threads in production:

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    executor.submit(() -> {
        String value = cache.getOrCreate("key", ct -> expensiveComputation());
        // ...
    });
}
```

If you must call from a platform thread, be aware that L2 operations will block the thread.

#### Prefer the State-Passing Overload in Hot Paths

The `getOrCreate(key, state, factory, ...)` overload avoids allocating a new lambda closure on every call. In high-throughput scenarios, this reduces GC pressure:

```java
// Avoids capturing 'userId' in a closure
cache.getOrCreate("user:" + userId, userId, (id, ct) -> loadUser(id), opts, tags, ct);
```

#### Use Appropriate Expiration Durations

- **`expiration`**: The overall TTL, applied to L2. Set this to match your data's staleness tolerance.
- **`localCacheExpiration`**: L1 TTL, always capped at the remaining overall lifetime. Keep this shorter than `expiration` in multi-instance deployments so instances pick up L2 updates from other nodes.

```java
var opts = new HybridCacheEntryOptions(
    Duration.ofMinutes(30),   // L2: data is valid for 30 minutes
    Duration.ofMinutes(5),    // L1: re-check L2 every 5 minutes
    EnumSet.noneOf(HybridCacheEntryFlags.class)
);
```

#### Implement `IHybridCacheSerializer` for Your Types

The library does not ship with a default serializer. Register at least one `IHybridCacheSerializerFactory` that covers your domain types:

```java
public class JsonSerializerFactory implements IHybridCacheSerializerFactory {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public <T> IHybridCacheSerializer<T> tryCreateSerializer(Class<T> type) {
        return new IHybridCacheSerializer<T>() {
            @Override
            public T deserialize(ByteBuffer source) {
                byte[] bytes = new byte[source.remaining()];
                source.get(bytes);
                return mapper.readValue(bytes, type);
            }

            @Override
            public byte[] serialize(T value) {
                return mapper.writeValueAsBytes(value);
            }
        };
    }
}
```

Pass factories when constructing the cache:

```java
var cache = new DefaultHybridCache(
    options, memoryCache, distributedCache, logger,
    List.of(new JsonSerializerFactory())
);
```

#### Mark Immutable Types with `@ImmutableObject`

For your own value types that are effectively immutable, annotate them so the cache can store them by reference instead of serializing on every read:

```java
@ImmutableObject(true)
public final class AppConfig {
    private final String region;
    private final int maxRetries;
    // ... (all fields final, no mutating methods)
}
```

Java records are automatically detected as immutable — no annotation needed.

#### Use Flags to Control Cache Behavior Per-Call

Flags let you fine-tune which tiers are involved in a specific operation:

```java
// Read-through from L2 only (skip L1), don't write back to L1
var opts = new HybridCacheEntryOptions(
    Duration.ofHours(1), null,
    EnumSet.of(
        HybridCacheEntryFlags.DISABLE_LOCAL_CACHE_READ,
        HybridCacheEntryFlags.DISABLE_LOCAL_CACHE_WRITE
    )
);

// Only check what's already cached — never invoke the factory
var checkOnly = new HybridCacheEntryOptions(
    Duration.ofMinutes(5), null,
    EnumSet.of(HybridCacheEntryFlags.DISABLE_UNDERLYING_DATA)
);
```

#### Use Cancellation for Long-Running Factories

If your factory performs expensive I/O, check the `CancellationToken` periodically:

```java
cache.getOrCreate("report:monthly", ct -> {
    var results = new ArrayList<Row>();
    for (var partition : partitions) {
        ct.throwIfCancelled();  // exits early if another thread called ct.cancel()
        results.addAll(queryPartition(partition));
    }
    return generateReport(results);
});
```

#### Key Naming Conventions

- Use a namespace prefix: `"user:123"`, `"product:456"`, `"config:feature-flags"`
- Keep keys short — the default maximum is 1024 characters
- Avoid control characters (codepoints ≤ 31)

---

## Project Structure

```
src/main/java/dev/shetty/
├── abstractions/                          # Public interfaces and abstract types
│   ├── HybridCache.java                   # Main public API (abstract class)
│   ├── IDistributedCache.java             # L2 backend interface
│   ├── IMemoryCache.java                  # L1 backend interface
│   ├── ICacheEntry.java                   # Cache entry abstraction
│   ├── IBufferWriter.java                 # Buffer writer interface
│   ├── IHybridCacheSerializer.java        # Per-type serializer interface
│   └── IHybridCacheSerializerFactory.java # Serializer factory interface
├── hybrid/                                # Core cache implementation
│   ├── DefaultHybridCache.java            # Primary HybridCache implementation
│   ├── BaseCacheItem.java                 # Base class with ref-counting
│   ├── CacheItem.java                     # Typed cache item (abstract)
│   ├── ImmutableCacheItem.java            # Zero-copy for immutable types
│   ├── MutableCacheItem.java              # Defensive-copy for mutable types
│   └── ImmutableTypeCache.java            # Immutability detection
├── internal/                              # Internal implementation details
│   ├── ByteArrayPool.java                 # Pooled byte[] management
│   ├── BufferChunk.java                   # Pooled buffer wrapper
│   ├── SharedArrayPool.java               # Generic object array pool
│   ├── HybridCachePayload.java            # Binary wire format for L2
│   ├── StampedeKey.java                   # Stampede group identity
│   ├── BaseStampedeState.java             # Stampede coordination base
│   ├── StampedeState.java                 # Typed stampede state
│   ├── PartitionedSyncLock.java           # Low-contention lock partitioning
│   ├── TagSet.java                        # Optimized tag storage
│   ├── CancellationToken.java             # Cooperative cancellation
│   ├── HybridCacheOptions.java            # Global cache configuration
│   ├── HybridCacheEntryOptions.java       # Per-entry configuration
│   ├── HybridCacheEntryFlags.java         # Cache behavior flags
│   └── CacheFeatures.java                 # Feature detection enum
└── utils/                                 # Utility classes
```

---

## Why Synchronous? (Design Rationale)

The C# `HybridCache` uses `ValueTask<T>` because .NET's `async/await` is pervasive and zero-allocation. Java's `CompletableFuture` doesn't offer the same ergonomics:

| Factor | `CompletableFuture` API | Synchronous + Virtual Threads |
|---|---|---|
| Code complexity | `.thenApply()`, `.thenCompose()`, `.exceptionally()` | Plain `try/catch` |
| Debuggability | Fragmented stack traces | Full, readable stack traces |
| Memory | ~40 bytes per `CompletableFuture` + closure | No per-call wrapper objects |
| Blocking | Must never `.join()` on a platform thread | Blocking is free on virtual threads |
| Learning curve | High | Standard Java |

`CompletableFuture` is still used **internally** for stampede coordination — it's the natural shared result holder when multiple callers wait on the same operation.

---

## References

- [.NET HybridCache documentation](https://learn.microsoft.com/en-us/aspnet/core/performance/caching/hybrid) — Official Microsoft documentation
- [.NET HybridCache source code](https://github.com/dotnet/aspnetcore/tree/main/src/Caching/Hybrid/src) — The C# implementation this project is ported from
- [HybridCache API reference](https://learn.microsoft.com/en-us/dotnet/api/microsoft.extensions.caching.hybrid.hybridcache) — .NET API docs
- [ASP.NET Core caching overview](https://learn.microsoft.com/en-us/aspnet/core/performance/caching/overview) — Context on the caching landscape
- [JEP 444: Virtual Threads](https://openjdk.org/jeps/444) — Java 21 virtual threads (the foundation for this library's synchronous API)
- [JEP 491: Synchronize Virtual Threads without Pinning](https://openjdk.org/jeps/491) — Java 24 improvement that eliminates `synchronized` pinning concerns

## License

This project is a community port and is not affiliated with Microsoft or the .NET Foundation.
