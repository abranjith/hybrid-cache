package dev.shetty.internal;

import dev.shetty.abstractions.IBufferWriter;

import java.lang.ref.Cleaner;
import java.lang.reflect.Array;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A recyclable buffer writer implementation that uses pooled arrays for better memory management.
 * This is a Java 24 implementation inspired by .NET's ArrayBufferWriter but using array pooling.
 *
 * @param <T> The type of elements in the buffer
 */
public final class RecyclableArrayBufferWriter<T> implements IBufferWriter<T>, AutoCloseable {
    // Usage note: While this class implements AutoCloseable, caution should be exercised
    // in exception scenarios where we don't 100% know that the caller has stopped touching
    // the buffer; in particular, this means scenarios involving a combination of external code
    // and (for example) "CompletableFuture". In those cases, it may be preferable to manually
    // close in the success case, and just drop the buffers in the failure case.

    private static final int ARRAY_MAX_LENGTH = Integer.MAX_VALUE - 56;
    private static final int DEFAULT_INITIAL_BUFFER_SIZE = 256;

    private T[] buffer;
    private int index;
    private int maxLength;
    private boolean quotaExceeded;
    private final Class<T> arrayClazz;

    // Using a Cleaner to handle resources if clients forget to close
    private static final Cleaner CLEANER = Cleaner.create();
    private final Cleaner.Cleanable cleanable;

    // Spare instance for object pooling
    private static final Map<Class<?>, RecyclableArrayBufferWriter<?>> SPARE_POOL = new ConcurrentHashMap<>();

    /**
     * Creates a new recyclable array buffer writer with the specified maximum length.
     *
     * @param <T> The type of elements in the buffer
     * @param maxLength The maximum allowed length for the buffer
     * @return A new or recycled RecyclableArrayBufferWriter instance
     */
    @SuppressWarnings("unchecked")
    public static <T> RecyclableArrayBufferWriter<T> create(Class<T> type, int maxLength) {
        RecyclableArrayBufferWriter<T> writer = (RecyclableArrayBufferWriter<T>) SPARE_POOL.getOrDefault(type, null);
        if (writer == null) {
            writer = new RecyclableArrayBufferWriter<>(type);
        }
        writer.initialize(maxLength);
        return writer;
    }

    /**
     * Private constructor to enforce usage of static factory method.
     */
    @SuppressWarnings("unchecked")
    private RecyclableArrayBufferWriter(Class<T> type) {
        arrayClazz = type;
        this.buffer = (T[]) Array.newInstance(arrayClazz, 0);
        // Register a cleaning action
        this.cleanable = CLEANER.register(this, new BufferCleaner<>(buffer, type));
    }

    /**
     * A cleaner class to handle resource cleanup if client forgets to close
     */
    private static class BufferCleaner<T> implements Runnable {
        private T[] buffer;
        private Class<T> type;

        BufferCleaner(T[] buffer, Class<T> type) {
            this.buffer = buffer;
            this.type = type;
        }

        @Override
        public void run() {
            if (buffer != null && buffer.length > 0) {
                // Return to array pool
                ArrayPool.getShared(type).giveBack(buffer);
                buffer = null;
            }
        }
    }

    /**
     * Get the number of bytes already committed to the buffer.
     *
     * @return The number of committed bytes
     */
    public int getCommittedBytes() {
        return index;
    }

    /**
     * Get the remaining capacity in the buffer.
     *
     * @return The free capacity
     */
    public int getFreeCapacity() {
        return buffer.length - index;
    }

    /**
     * Checks if the quota has been exceeded.
     *
     * @return True if the quota has been exceeded, false otherwise
     */
    public boolean isQuotaExceeded() {
        return quotaExceeded;
    }

    @SuppressWarnings({"unchecked", "resource"})
    @Override
    public void close() {
        // Invalidate the cleanable since we're handling cleanup manually
        cleanable.clean();

        // Attempt to reuse via "spare"; if that isn't possible, recycle the buffer
        index = 0;
        var existingRef = SPARE_POOL.getOrDefault(arrayClazz, null);
        if (existingRef != null) {
            T[] tmp = buffer;
            buffer = (T[]) Array.newInstance(arrayClazz, 0);
            ArrayPool.getShared(arrayClazz).giveBack(tmp);
        }
        SPARE_POOL.computeIfAbsent(arrayClazz, k -> this);
    }

    @Override
    public void advance(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Count must be non-negative");
        }

        if (index > buffer.length - count) {
            throw new IndexOutOfBoundsException("Cannot advance past the end of the buffer");
        }

        if (index + count > maxLength) {
            quotaExceeded = true;
            throw new IllegalStateException("Max length exceeded");
        }

        index += count;
    }

    /**
     * Resets the writer without resetting the buffer.
     * The existing memory should be considered "gone"
     */
    public void resetInPlace() {
        index = 0;
    }

    /**
     * Returns a read-only view of the committed memory.
     *
     * @return A read-only memory view of the committed portion
     */
    public ArraySegment<T> getCommittedSpan() {
        return new ArraySegment<>(buffer, 0, index);
    }

    public ArraySegment<T> getSpan(int sizeHint) {
        checkAndResizeBuffer(sizeHint);
        return new ArraySegment<>(buffer, index, buffer.length - index);
    }

    /**
     * Creates a standalone isolated copy of the buffer.
     *
     * @return A new array containing the committed data
     */
    public T[] toArray() {
        return Arrays.copyOfRange(buffer, 0, index);
    }

    /**
     * Returns the buffer as a ReadOnly List.
     *
     * @return A ReadOnly List view of the buffer
     */
    public List<T> asReadOnlyList() {
        return Collections.unmodifiableList(Arrays.asList(getCommittedSpan().getArray()));
    }

    /**
     * Disconnects the current buffer so that we can store it without it being recycled.
     *
     * @return The detached array buffer
     */
    @SuppressWarnings("unchecked")
    T[] detachCommitted() {
        T[] tmp = index == 0 ? (T[]) Array.newInstance(arrayClazz, 0) : buffer;

        buffer = (T[]) Array.newInstance(arrayClazz, 0);
        index = 0;

        return tmp;
    }

    /**
     * Gets the current buffer and its committed length.
     *
     * @param length Output parameter that receives the length of the committed data
     * @return The current buffer array
     */
    @SuppressWarnings("unchecked")
    T[] getBuffer(int[] length) {
        length[0] = index;
        return index == 0 ? (T[]) Array.newInstance(arrayClazz, 0) : buffer;
    }

    private void checkAndResizeBuffer(int sizeHint) {
        if (sizeHint <= 0) {
            sizeHint = 1;
        }

        if (sizeHint > getFreeCapacity()) {
            int currentLength = buffer.length;

            // Attempt to grow by the larger of the sizeHint and double the current size
            int growBy = Math.max(sizeHint, currentLength);

            if (currentLength == 0) {
                growBy = Math.max(growBy, DEFAULT_INITIAL_BUFFER_SIZE);
            }

            long newSizeLong = (long) currentLength + growBy;
            int newSize;

            if (newSizeLong > Integer.MAX_VALUE) {
                // Attempt to grow to ARRAY_MAX_LENGTH
                long needed = currentLength - getFreeCapacity() + sizeHint;

                if (needed > ARRAY_MAX_LENGTH) {
                    throw new OutOfMemoryError("Unable to grow buffer as requested");
                }

                newSize = ARRAY_MAX_LENGTH;
            } else {
                newSize = (int) newSizeLong;
            }

            // Resize the backing buffer
            T[] oldArray = buffer;
            buffer = ArrayPool.getShared(arrayClazz).take(newSize);
            System.arraycopy(oldArray, 0, buffer, 0, index);

            if (oldArray.length != 0) {
                ArrayPool.getShared(arrayClazz).giveBack(oldArray);
            }
        }
    }

    private void initialize(int maxLength) {
        index = 0;
        this.maxLength = maxLength;
        quotaExceeded = false;
    }
}