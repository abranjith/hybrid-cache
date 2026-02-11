package dev.shetty.internal;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Used to convey buffer status where Offset is always zero,
 * and we use the most significant bit of the length to track whether or not to recycle this value.
 * <p>
 * This class uses primitive {@code byte[]} to avoid the massive overhead of boxing
 * (each {@code Byte} object is ~16 bytes on a 64-bit JVM).
 */
public final class BufferChunk {
    private static final int FLAG_RETURN_TO_POOL = 1 << 31;

    private final byte[] oversizedArray;
    private final int offset;
    private final int lengthAndPoolFlag;

    public BufferChunk(byte[] array) {
        this.oversizedArray = array;
        this.offset = 0;
        this.lengthAndPoolFlag = array.length;
    }

    public BufferChunk(byte[] array, int offset, int length, boolean returnToPool) {
        if (length < 0) throw new IllegalArgumentException("length must be non-negative");
        this.oversizedArray = array;
        this.offset = offset;
        this.lengthAndPoolFlag = length | (returnToPool ? FLAG_RETURN_TO_POOL : 0);
    }

    public boolean hasValue() {
        return oversizedArray != null;
    }

    public int getOffset() {
        return offset;
    }

    public int getLength() {
        return lengthAndPoolFlag & ~FLAG_RETURN_TO_POOL;
    }

    public boolean returnToPool() {
        return (lengthAndPoolFlag & FLAG_RETURN_TO_POOL) != 0;
    }

    public byte[] getOversizedArray() {
        return oversizedArray;
    }

    /**
     * Returns a copy of the buffer's data as a new array, trimmed to the actual length.
     */
    public byte[] toArray() {
        int len = getLength();
        if (len == 0) {
            return new byte[0];
        }
        return Arrays.copyOfRange(oversizedArray, offset, offset + len);
    }

    /**
     * Returns a read-only ByteBuffer view of the active portion.
     */
    public ByteBuffer asReadOnlyBuffer() {
        return ByteBuffer.wrap(oversizedArray, offset, getLength()).asReadOnlyBuffer();
    }

    /**
     * Recycle the buffer if appropriate (returns to pool if flagged).
     * The caller should discard this instance after recycling.
     */
    public void recycleIfAppropriate() {
        if (returnToPool() && oversizedArray != null) {
            ByteArrayPool.getShared().giveBack(oversizedArray);
        }
    }

    /**
     * Returns a <em>new</em> BufferChunk with the return-to-pool flag cleared.
     * The original instance is not modified.
     */
    public BufferChunk doNotReturnToPool() {
        return new BufferChunk(oversizedArray, offset, getLength(), false);
    }

    /**
     * Returns the buffer as a byte array segment (copy of active range).
     */
    public byte[] asArraySegment() {
        if (!hasValue()) {
            return new byte[0];
        }
        return toArray();
    }
}