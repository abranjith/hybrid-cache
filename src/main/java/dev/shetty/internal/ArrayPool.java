package dev.shetty.internal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides a resource pool that enables reusing instances of arrays.
 * <p>
 * Renting and returning buffers with an ArrayPool can increase performance
 * in situations where arrays are created and destroyed frequently, resulting in significant
 * memory pressure on the garbage collector.
 * <p>
 * This class is thread-safe. All members may be used by multiple threads concurrently.
 */
public abstract class ArrayPool<T> {

    // A map to store shared instances of ArrayPool (which will be SubArrayPool)
    // The key is the Class<T> (e.g., byte[].class, String.class)
    // The value is the specific ArrayPool instance for that type.
    private static final Map<Class<?>, ArrayPool<?>> SHARED_POOLS = new ConcurrentHashMap<>();

    // Static factory method to get a shared instance for a specific type T
    @SuppressWarnings("unchecked") // Suppress warning for type cast, safe due to map key
    public static <T> ArrayPool<T> getShared(Class<T> tClass) {
        // Use computeIfAbsent to create and store the instance if it doesn't exist
        return (ArrayPool<T>) SHARED_POOLS.computeIfAbsent(tClass, k -> {
            // Here, we create an instance of our generic subclass
            // For example, if you have a specific SubArrayPool that extends ArrayPool<T>
            return new SharedArrayPool<T>(tClass);
        });
    }

    /**
     * Retrieves a buffer that is at least the requested length.
     * This buffer is loaned to the caller and should be returned to the same pool via {@link #giveBack}.
     */
    public abstract T[] take(int minimumLength);

    /**
     * Returns to the pool an array that was previously obtained via {@link #take}.
     * If clearArray is true, the array will be cleared before being pooled.
     */
    public abstract void giveBack(T[] array, boolean clearArray);

    /**
     * Returns to the pool an array that was previously obtained via {@link #take}.
     * The array is not cleared before being pooled.
     */
    public void giveBack(T[] array) {
        giveBack(array, false);
    }
}