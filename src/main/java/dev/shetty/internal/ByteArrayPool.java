package dev.shetty.internal;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A specialized array pool for primitive {@code byte[]} arrays.
 * <p>
 * The generic {@link ArrayPool} cannot pool primitive arrays due to Java's type erasure.
 * This class provides the same tiered caching strategy (thread-local + per-CPU partitions)
 * as {@link SharedArrayPool}, but specialized for {@code byte[]} to avoid the massive
 * overhead of boxing each byte into a {@code Byte} object (~16 bytes per element on 64-bit JVMs).
 * <p>
 * <b>Example usage:</b>
 * <pre>{@code
 * ByteArrayPool pool = ByteArrayPool.getShared();
 *
 * // Request at least 100 bytes (may return a larger bucketed array, e.g. 128 bytes)
 * byte[] buffer = pool.take(100);
 * try {
 *     // use buffer for serialization / I/O work
 * } finally {
 *     // return to pool; pass true if sensitive data should be cleared first
 *     pool.giveBack(buffer, true);
 * }
 * }</pre>
 * <p>
 * This class is thread-safe. All members may be used by multiple threads concurrently.
 */
public final class ByteArrayPool {

    private static final int NUM_BUCKETS = 27;
    private static final byte[] EMPTY = new byte[0];

    private static final ByteArrayPool SHARED = new ByteArrayPool();

    /**
     * Returns the shared singleton instance.
     */
    public static ByteArrayPool getShared() {
        return SHARED;
    }

    /** Thread-local arrays, to cache one array per size per thread */
    private final ThreadLocal<ThreadLocalArrays> tlsArrays = ThreadLocal.withInitial(ThreadLocalArrays::new);

    /** Tracks all thread-local arrays for trimming under memory pressure */
    private final Map<ThreadLocalArrays, Boolean> allThreadLocalArrays =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Array of per-core partitions, lazily initialized */
    private final Partition[][] buckets = new Partition[NUM_BUCKETS][];

    private ByteArrayPool() {}

    /**
     * Retrieves a buffer that is at least the requested length.
     * The returned array may be larger than requested.
     *
     * @param minimumLength the minimum required array length
     * @return a pooled or freshly allocated byte array
     */
    public byte[] take(int minimumLength) {
        if (minimumLength < 0) {
            throw new IllegalArgumentException("Length must be non-negative");
        }
        if (minimumLength == 0) {
            return EMPTY;
        }

        int bucketIndex = selectBucketIndex(minimumLength);

        // Try thread-local cache first
        ThreadLocalArrays tlsBuckets = tlsArrays.get();
        if (bucketIndex < NUM_BUCKETS) {
            byte[] array = tlsBuckets.getArray(bucketIndex);
            if (array != null) {
                tlsBuckets.setArray(bucketIndex, null);
                return array;
            }
        }

        // Try shared per-core partitions
        if (bucketIndex < NUM_BUCKETS) {
            Partition[] partitions = getOrCreatePartitions(bucketIndex);
            int cpuId = getCurrentProcessorId() % getPartitionCount();
            for (int i = 0; i < partitions.length; i++) {
                byte[] array = partitions[cpuId].tryPop();
                if (array != null) {
                    return array;
                }
                cpuId = (cpuId + 1) % partitions.length;
            }
            // Allocate with correct bucket size
            minimumLength = getMaxSizeForBucket(bucketIndex);
        }

        return new byte[minimumLength];
    }

    /**
     * Returns a buffer to the pool that was previously obtained via {@link #take}.
     *
     * @param array     the array to return
     * @param clearArray whether to zero the array before pooling
     */
    public void giveBack(byte[] array, boolean clearArray) {
        if (array == null) {
            throw new IllegalArgumentException("Array cannot be null");
        }
        if (array.length == 0) {
            return;
        }

        int bucketIndex = selectBucketIndex(array.length);

        ThreadLocalArrays tlsBuckets = tlsArrays.get();
        allThreadLocalArrays.putIfAbsent(tlsBuckets, Boolean.TRUE);

        if (bucketIndex < NUM_BUCKETS) {
            if (clearArray) {
                Arrays.fill(array, (byte) 0);
            }
            if (array.length != getMaxSizeForBucket(bucketIndex)) {
                throw new IllegalArgumentException("Array was not rented from this pool");
            }

            byte[] previousArray = tlsBuckets.getArray(bucketIndex);
            tlsBuckets.setArray(bucketIndex, array);

            if (previousArray != null) {
                Partition[] partitions = getOrCreatePartitions(bucketIndex);
                partitions[getCurrentProcessorId() % partitions.length].tryPush(previousArray);
            }
        }
    }

    /**
     * Returns a buffer to the pool without clearing it.
     */
    public void giveBack(byte[] array) {
        giveBack(array, false);
    }

    private Partition[] getOrCreatePartitions(int bucketIndex) {
        Partition[] partitions = buckets[bucketIndex];
        if (partitions == null) {
            synchronized (this) {
                partitions = buckets[bucketIndex];
                if (partitions == null) {
                    int partitionCount = getPartitionCount();
                    partitions = new Partition[partitionCount];
                    for (int i = 0; i < partitionCount; i++) {
                        partitions[i] = new Partition();
                    }
                    buckets[bucketIndex] = partitions;
                }
            }
        }
        return partitions;
    }

    private static int getCurrentProcessorId() {
        return Math.abs((int) (Thread.currentThread().threadId() % Runtime.getRuntime().availableProcessors()));
    }

    private static int getPartitionCount() {
        return Math.min(Runtime.getRuntime().availableProcessors(), 64);
    }

    private static int selectBucketIndex(int length) {
        length = length - 1 | 15;
        int index = 0;
        while (length > 0) {
            length >>= 1;
            if (length <= 0) break;
            index++;
        }
        return Math.min(index - 3, NUM_BUCKETS - 1);
    }

    private static int getMaxSizeForBucket(int bucketIndex) {
        return 16 << bucketIndex;
    }

    /** Helper class to hold thread-local arrays for each bucket. */
    private static final class ThreadLocalArrays {
        private final byte[][] arrays = new byte[NUM_BUCKETS][];

        byte[] getArray(int index) {
            return arrays[index];
        }

        void setArray(int index, byte[] array) {
            arrays[index] = array;
        }
    }

    /**
     * A chunk of a larger byte array, with metadata about the active portion and whether it should be returned to the pool.
     * This is used internally to manage buffers that may be larger than the requested size, and to track whether they should be returned to the pool when done.
     * <p>
     * The {@code lengthAndPoolFlag} field encodes both the active length of the buffer and a flag indicating whether it should be returned to the pool.
     * The most significant bit is used as the flag, and the remaining bits represent the length. This allows us to store both pieces of information in a single int without additional fields.
     * <p>
     * This class is immutable and thread-safe. It is used as a return type for methods that need to return a buffer along with metadata about how to manage it.
     */
    private static final class Partition {
        private static final int MAX_ARRAYS = 32;
        private final byte[][] arrays = new byte[MAX_ARRAYS][];
        private final Lock lock = new ReentrantLock(true);
        private final AtomicInteger count = new AtomicInteger(0);

        byte[] tryPop() {
            lock.lock();
            try {
                int index = count.get() - 1;
                if (index >= 0 && index < arrays.length) {
                    byte[] array = arrays[index];
                    arrays[index] = null;
                    count.decrementAndGet();
                    return array;
                }
                return null;
            } finally {
                lock.unlock();
            }
        }

        boolean tryPush(byte[] array) {
            lock.lock();
            try {
                int index = count.get();
                if (index < MAX_ARRAYS) {
                    arrays[index] = array;
                    count.incrementAndGet();
                    return true;
                }
                return false;
            } finally {
                lock.unlock();
            }
        }
    }
}
