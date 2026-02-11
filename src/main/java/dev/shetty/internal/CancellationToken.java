package dev.shetty.internal;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A lightweight cooperative cancellation token.
 * <p>
 * Factories and long-running operations can check {@link #isCancelled()} periodically
 * and abort early when cancellation is requested. This is modeled after C#'s CancellationToken
 * but implemented as a simple wrapper around {@link AtomicBoolean}.
 * <p>
 * Usage:
 * <pre>{@code
 * CancellationToken ct = new CancellationToken();
 * // Pass to factory
 * cache.getOrCreate("key", (token) -> {
 *     for (var item : items) {
 *         token.throwIfCancelled();
 *         process(item);
 *     }
 *     return result;
 * }, options, tags, ct);
 *
 * // Cancel from another thread
 * ct.cancel();
 * }</pre>
 */
public final class CancellationToken {

    /**
     * A shared instance that is never cancelled. Use when cancellation is not needed.
     */
    public static final CancellationToken NONE = new CancellationToken();

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /**
     * Returns {@code true} if cancellation has been requested.
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Requests cancellation. This is thread-safe and can be called from any thread.
     * All code checking this token will see the cancellation.
     */
    public void cancel() {
        cancelled.set(true);
    }

    /**
     * Throws {@link CancellationException} if cancellation has been requested.
     * Use this in factory methods for cooperative cancellation.
     */
    public void throwIfCancelled() {
        if (cancelled.get()) {
            throw new CancellationException("Operation was cancelled");
        }
    }
}
