package dev.shetty.internal;

import java.lang.ref.Cleaner;
import java.lang.reflect.Array;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An optimized implementation of ArrayPool intended to be used as the shared singleton instance.
 * <p>
 * Uses a tiered caching approach with:
 * - Thread-local caching: one array per thread per size
 * - Per-CPU partitions: shared pool split by processor core
 * <p>
 * This implementation minimizes allocations by efficiently reusing arrays.
 */
public class SharedArrayPool<T> extends ArrayPool<T> {
    /** Number of buckets (array sizes) in the pool, starting from length 16 */
    private static final int NUM_BUCKETS = 27;
    private final Class<T> tClass;
    private final T[] emptyArray;

    /** Thread-local arrays, to cache one array per size per thread */
    private final ThreadLocal<ThreadLocalArrays> tlsArrays = ThreadLocal.withInitial(ThreadLocalArrays::new);

    /** Tracks all thread-local arrays for trimming under memory pressure */
    private final Map<ThreadLocalArrays, Boolean> allThreadLocalArrays =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Array of per-core partitions, lazily initialized */
    private final Partition<T>[][] buckets;

    /** Whether the memory pressure handler has been registered */
    private final AtomicBoolean trimCallbackRegistered = new AtomicBoolean(false);

    /** The cleaner used for registering memory pressure callbacks */
    private static final Cleaner CLEANER = Cleaner.create();

    /**
     * Creates a new SharedArrayPool.
     */
    @SuppressWarnings("unchecked")
    public SharedArrayPool(Class<T> tClass) {
        // Create array of per-core partitions (arrays of Partition<T>)
        this.buckets = new Partition[NUM_BUCKETS][];
        this.tClass = tClass;
        // Create an empty array of the component type
        this.emptyArray = (T[])Array.newInstance(tClass, 0);
        // Register memory pressure callback
        registerMemoryPressureCallback();
    }

    /**
     * Registers a callback to trim arrays when system memory becomes constrained.
     */
    private void registerMemoryPressureCallback() {
        if (!trimCallbackRegistered.getAndSet(true)) {
            CLEANER.register(this, this::trim);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T[] take(int minimumLength) {
        if (minimumLength < 0) {
            throw new IllegalArgumentException("Length must be non-negative");
        }

        if (minimumLength == 0) {
            // Special case for zero-length arrays
            return emptyArray;
        }

        // Get the bucket index for this length
        int bucketIndex = selectBucketIndex(minimumLength);

        // First, try to get from the thread-local cache
        ThreadLocalArrays tlsBuckets = tlsArrays.get();
        if (bucketIndex < NUM_BUCKETS) {
            @SuppressWarnings("unchecked")
            T[] array = (T[]) tlsBuckets.getArray(bucketIndex);
            if (array != null) {
                tlsBuckets.setArray(bucketIndex, null);
                return array;
            }
        }

        // Next, try to get from the shared per-core buckets
        if (bucketIndex < NUM_BUCKETS) {
            Partition<T>[] partitions = getOrCreatePartitions(bucketIndex);

            // Try current CPU's partition first, then others
            int cpuId = getCurrentProcessorId() % getPartitionCount();
            for (int i = 0; i < partitions.length; i++) {
                T[] array = partitions[cpuId].tryPop();
                if (array != null) {
                    return array;
                }
                cpuId = (cpuId + 1) % partitions.length;
            }

            // No array available, allocate with correct bucket size
            minimumLength = getMaxSizeForBucket(bucketIndex);
        }

        // Allocate new array
        @SuppressWarnings("unchecked")
        T[] newArray = (T[])Array.newInstance(tClass, minimumLength);
        return newArray;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void giveBack(T[] array, boolean clearArray) {
        if (array == null) {
            throw new IllegalArgumentException("Array cannot be null");
        }

        if (array.length == 0) {
            // Don't pool zero-length arrays
            return;
        }

        // Get the bucket index for this array length
        int bucketIndex = selectBucketIndex(array.length);

        // Get thread-local arrays (initializing if needed)
        ThreadLocalArrays tlsBuckets = tlsArrays.get();
        allThreadLocalArrays.putIfAbsent(tlsBuckets, Boolean.TRUE);

        if (bucketIndex < NUM_BUCKETS) {
            // Clear array if requested
            if (clearArray) {
                Arrays.fill(array, null);
            }

            // Check if array is correct size for this bucket
            if (array.length != getMaxSizeForBucket(bucketIndex)) {
                throw new IllegalArgumentException("Array was not rented from this pool");
            }

            // Try to store in thread-local first
            Object previousArray = tlsBuckets.getArray(bucketIndex);
            tlsBuckets.setArray(bucketIndex, array);

            // If there was a previous array, push it down to the shared bucket
            if (previousArray != null) {
                @SuppressWarnings("unchecked")
                T[] prevArray = (T[]) previousArray;
                Partition<T>[] partitions = getOrCreatePartitions(bucketIndex);
                partitions[getCurrentProcessorId() % partitions.length].tryPush(prevArray);
            }
        }
    }

    /**
     * Trims arrays from the pool when memory pressure occurs.
     */
    private boolean trim() {
        int currentMillis = (int) (System.currentTimeMillis() & 0x7FFFFFFF);
        MemoryPressure pressure = getMemoryPressure();

        // Trim per-core buckets
        for (int i = 0; i < NUM_BUCKETS; i++) {
            Partition<T>[][] bucketArray = buckets;
            Partition<T>[] partitions = bucketArray[i];
            if (partitions != null) {
                for (Partition<T> partition : partitions) {
                    partition.trim(currentMillis, pressure);
                }
            }
        }

        // Trim thread-local buckets
        if (pressure == MemoryPressure.HIGH) {
            // Under high pressure, release all thread-locals
            synchronized (allThreadLocalArrays) {
                allThreadLocalArrays.forEach((buckets, unused) -> buckets.clear());
            }
        } else {
            // For medium/low pressure, age out arrays based on timestamp
            long thresholdMillis = pressure == MemoryPressure.MEDIUM ? 15_000 : 30_000;

            synchronized (allThreadLocalArrays) {
                allThreadLocalArrays.forEach((buckets, unused) ->
                        buckets.trimArrays(currentMillis, thresholdMillis));
            }
        }

        return true;
    }

    /**
     * Gets or creates the partitions for a given bucket index.
     */
    @SuppressWarnings("unchecked")
    private Partition<T>[] getOrCreatePartitions(int bucketIndex) {
        Partition<T>[] partitions = buckets[bucketIndex];
        if (partitions == null) {
            synchronized (this) {
                partitions = buckets[bucketIndex];
                if (partitions == null) {
                    int partitionCount = getPartitionCount();
                    partitions = new Partition[partitionCount];
                    for (int i = 0; i < partitionCount; i++) {
                        partitions[i] = new Partition<>();
                    }
                    buckets[bucketIndex] = partitions;
                }
            }
        }
        return partitions;
    }

    /**
     * Gets the current processor ID for the executing thread.
     * This is a simplified approach - Java doesn't directly expose thread affinity.
     */
    private static int getCurrentProcessorId() {
        return Math.abs((int)(Thread.currentThread().threadId() % Runtime.getRuntime().availableProcessors()));
    }

    /**
     * Gets the number of partitions to use, based on available processors.
     */
    private static int getPartitionCount() {
        return Math.min(Runtime.getRuntime().availableProcessors(),
                getEnvironmentInt("ARRAY_POOL_MAX_PARTITION_COUNT", Integer.MAX_VALUE));
    }

    /**
     * Gets the maximum arrays per partition.
     */
    private static int getMaxArraysPerPartition() {
        return getEnvironmentInt("ARRAY_POOL_MAX_ARRAYS_PER_PARTITION", 32);
    }

    /**
     * Selects the bucket index for a given array length.
     */
    private static int selectBucketIndex(int length) {
        length = length - 1 | 15;
        int index = 0;
        while (length > 0) {
            length >>= 1;
            if (length <= 0) break;
            index++;
        }
        return Math.min(index - 3, NUM_BUCKETS - 1); // -3 because we start at 16 (2^4)
    }

    /**
     * Gets the maximum size for a given bucket index.
     */
    private static int getMaxSizeForBucket(int bucketIndex) {
        return 16 << bucketIndex;
    }

    /**
     * Gets an environment variable as an int.
     */
    private static int getEnvironmentInt(String variable, int defaultValue) {
        String value = System.getenv(variable);
        if (value != null) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                // Ignore and return default
            }
        }
        return defaultValue;
    }

    /**
     * Estimates current memory pressure based on available memory.
     */
    private static MemoryPressure getMemoryPressure() {
        // Simple approximation based on available memory percentage
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalFree = runtime.freeMemory();
        long usedMemory = maxMemory - totalFree;
        double memoryUsage = (double) usedMemory / maxMemory;

        if (memoryUsage > 0.85) {
            return MemoryPressure.HIGH;
        } else if (memoryUsage > 0.70) {
            return MemoryPressure.MEDIUM;
        } else {
            return MemoryPressure.LOW;
        }
    }

    /**
     * Thread-local arrays for fast per-thread caching.
     */
    private static class ThreadLocalArrays {
        private final Object[] arrays = new Object[NUM_BUCKETS];
        private final int[] timestamps = new int[NUM_BUCKETS];

        Object getArray(int index) {
            return arrays[index];
        }

        void setArray(int index, Object array) {
            arrays[index] = array;
            if (array != null) {
                timestamps[index] = 0; // Reset timestamp
            }
        }

        void clear() {
            Arrays.fill(arrays, null);
        }

        void trimArrays(int currentMillis, long thresholdMillis) {
            synchronized (arrays) {
                for (int i = 0; i < arrays.length; i++) {
                    if (arrays[i] != null) {
                        if (timestamps[i] == 0) {
                            timestamps[i] = currentMillis;
                        } else if (currentMillis - timestamps[i] > thresholdMillis) {
                            arrays[i] = null; // Release for garbage collection
                        }
                    }
                }
            }
        }
    }

    /**
     * A thread-safe partition holding arrays of a specific size.
     */
    private static class Partition<T> {
        private static final int MAX_ARRAYS = getMaxArraysPerPartition();
        private final Object[] arrays;
        private final Lock lock = new ReentrantLock(true);
        private final AtomicInteger count = new AtomicInteger(0);
        private int millisecondsTimestamp;

        public Partition() {
            arrays = new Object[MAX_ARRAYS];
        }

        @SuppressWarnings("unchecked")
        public T[] tryPop() {
            lock.lock();
            try {
                int index = count.get() - 1;
                if (index >= 0 && index < arrays.length) {
                    T[] array = (T[]) arrays[index];
                    arrays[index] = null;
                    count.decrementAndGet();
                    return array;
                }
                return null;
            } finally {
                lock.unlock();
            }
        }

        public boolean tryPush(T[] array) {
            lock.lock();
            try {
                int index = count.get();
                if (index == 0) {
                    millisecondsTimestamp = 0; // Reset timestamp
                }

                if (index < array.length) {
                    arrays[index] = array;
                    count.incrementAndGet();
                    return true;
                }
                return false;
            } finally {
                lock.unlock();
            }
        }

        public void trim(int currentMillis, MemoryPressure pressure) {
            if (count.get() == 0) {
                return;
            }

            int trimMilliseconds = (pressure == MemoryPressure.HIGH) ? 10_000 : 60_000;

            lock.lock();
            try {
                if (count.get() == 0) {
                    return;
                }

                if (millisecondsTimestamp == 0) {
                    // Initialize timestamp on first trim
                    millisecondsTimestamp = currentMillis;
                    return;
                }

                if ((currentMillis - millisecondsTimestamp) <= trimMilliseconds) {
                    return;
                }

                // Drop items from the partition based on memory pressure
                int trimCount = switch (pressure) {
                    case HIGH -> MAX_ARRAYS; // All of them
                    case MEDIUM -> 2;
                    default -> 1;
                };

                while (count.get() > 0 && trimCount-- > 0) {
                    int index = count.decrementAndGet() - 1;
                    arrays[index] = null;
                }

                if (count.get() > 0) {
                    millisecondsTimestamp = millisecondsTimestamp + (trimMilliseconds / 4);
                } else {
                    millisecondsTimestamp = 0;
                }
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Enum representing different memory pressure levels.
     */
    private enum MemoryPressure {
        LOW, MEDIUM, HIGH
    }
}
