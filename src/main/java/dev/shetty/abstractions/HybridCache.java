package dev.shetty.abstractions;

import dev.shetty.internal.CancellationToken;
import dev.shetty.internal.HybridCacheEntryOptions;

import java.util.Collection;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Provides multi-tier caching services building on distributed cache backends.
 * <p>
 * This API is <strong>synchronous by design</strong>. Callers are expected to run on
 * virtual threads (Java 21+), which makes blocking on I/O operations free — the JVM
 * unmounts the virtual thread from its carrier platform thread during blocking calls.
 * <p>
 * Internally, the implementation uses {@code CompletableFuture} for stampede coordination
 * (so that multiple callers requesting the same key share a single in-flight operation).
 * Joiners call {@code .join()} on the shared future, which is cheap on a virtual thread.
 * <p>
 * Usage:
 * <pre>{@code
 * try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
 *     executor.submit(() -> {
 *         String value = cache.getOrCreate("user:123", ct -> loadUser("123"));
 *         process(value);
 *     });
 * }
 * }</pre>
 */
public abstract class HybridCache {

    /**
     * Gets the value associated with the key if it exists, or generates a new entry
     * using the provided factory if the key is not found.
     *
     * @param <TState> The type of the state passed to the factory
     * @param <T>      The type of the cached value
     * @param key      The cache key
     * @param state    State passed to the factory to avoid closure allocations
     * @param factory  Function that produces the value when a cache miss occurs.
     *                 Receives the state and a {@link CancellationToken} for cooperative cancellation.
     * @param options  Cache entry options (expiration, flags), or null for defaults
     * @param tags     Tags for tag-based invalidation, or null for none
     * @param cancellationToken Token for cooperative cancellation
     * @return The cached or newly created value
     */
    public abstract <TState, T> T getOrCreate(
            String key,
            TState state,
            BiFunction<TState, CancellationToken, T> factory,
            HybridCacheEntryOptions options,
            Collection<String> tags,
            CancellationToken cancellationToken
    );

    /**
     * Convenience overload without state — for factories that don't need external state.
     */
    public <T> T getOrCreate(
            String key,
            Function<CancellationToken, T> factory,
            HybridCacheEntryOptions options,
            Collection<String> tags,
            CancellationToken cancellationToken
    ) {
        return getOrCreate(
                key,
                factory,
                (f, ct) -> f.apply(ct),
                options,
                tags,
                cancellationToken
        );
    }

    /**
     * Minimal convenience overload — just key and factory.
     */
    public <T> T getOrCreate(
            String key,
            Function<CancellationToken, T> factory
    ) {
        return getOrCreate(key, factory, null, null, CancellationToken.NONE);
    }

    /**
     * Sets or overwrites the value associated with the key.
     *
     * @param <T>     The type of the value
     * @param key     The cache key
     * @param value   The value to store
     * @param options Cache entry options, or null for defaults
     * @param tags    Tags for tag-based invalidation, or null for none
     * @param cancellationToken Token for cooperative cancellation
     */
    public abstract <T> void set(
            String key,
            T value,
            HybridCacheEntryOptions options,
            Collection<String> tags,
            CancellationToken cancellationToken
    );

    /**
     * Minimal convenience overload for set.
     */
    public <T> void set(String key, T value) {
        set(key, value, null, null, CancellationToken.NONE);
    }

    /**
     * Removes the value associated with the key if it exists.
     *
     * @param key The cache key
     * @param cancellationToken Token for cooperative cancellation
     */
    public abstract void remove(String key, CancellationToken cancellationToken);

    /**
     * Convenience overload for remove without cancellation.
     */
    public void remove(String key) {
        remove(key, CancellationToken.NONE);
    }

    /**
     * Removes the values associated with the specified keys.
     * Implementors should treat null as empty.
     */
    public void remove(Collection<String> keys, CancellationToken cancellationToken) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        if (keys.size() == 1) {
            remove(keys.iterator().next(), cancellationToken);
            return;
        }
        for (String key : keys) {
            cancellationToken.throwIfCancelled();
            remove(key, cancellationToken);
        }
    }

    /**
     * Removes all values associated with the specified tag.
     *
     * @param tag The tag to invalidate
     * @param cancellationToken Token for cooperative cancellation
     */
    public abstract void removeByTag(String tag, CancellationToken cancellationToken);

    /**
     * Convenience overload for removeByTag without cancellation.
     */
    public void removeByTag(String tag) {
        removeByTag(tag, CancellationToken.NONE);
    }

    /**
     * Removes all values associated with the specified tags.
     * Implementors should treat null as empty.
     */
    public void removeByTag(Collection<String> tags, CancellationToken cancellationToken) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        if (tags.size() == 1) {
            removeByTag(tags.iterator().next(), cancellationToken);
            return;
        }
        for (String tag : tags) {
            cancellationToken.throwIfCancelled();
            removeByTag(tag, cancellationToken);
        }
    }
}