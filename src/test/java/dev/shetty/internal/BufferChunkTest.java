package dev.shetty.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class BufferChunkTest {
    @Test
    void toArrayRespectsOffsetAndLength() {
        byte[] data = new byte[] {1, 2, 3, 4, 5};
        BufferChunk chunk = new BufferChunk(data, 1, 3, false);
        assertArrayEquals(new byte[] {2, 3, 4}, chunk.toArray());
    }

    @Test
    void doNotReturnToPoolCreatesNewInstance() {
        byte[] data = new byte[] {9, 8, 7};
        BufferChunk original = new BufferChunk(data, 0, 3, true);
        BufferChunk copy = original.doNotReturnToPool();
        assertNotSame(original, copy);
        assertFalse(copy.returnToPool());
        assertEquals(original.getLength(), copy.getLength());
    }

    @Test
    void recycleIfAppropriateDoesNotThrow() {
        ByteArrayPool pool = ByteArrayPool.getShared();
        byte[] data = pool.take(16);
        BufferChunk chunk = new BufferChunk(data, 0, data.length, true);
        assertDoesNotThrow(chunk::recycleIfAppropriate);
    }
}
