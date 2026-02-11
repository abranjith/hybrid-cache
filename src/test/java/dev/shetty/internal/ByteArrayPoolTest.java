package dev.shetty.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ByteArrayPoolTest {
    @Test
    void takeReturnsEmptyForZero() {
        ByteArrayPool pool = ByteArrayPool.getShared();
        byte[] result = pool.take(0);
        assertNotNull(result);
        assertEquals(0, result.length);
    }

    @Test
    void takeReturnsBucketSize() {
        ByteArrayPool pool = ByteArrayPool.getShared();
        byte[] result = pool.take(10);
        assertNotNull(result);
        assertEquals(16, result.length);
    }

    @Test
    void giveBackReusesThreadLocalArray() {
        ByteArrayPool pool = ByteArrayPool.getShared();
        byte[] first = pool.take(10);
        pool.giveBack(first);
        byte[] second = pool.take(10);
        assertSame(first, second);
    }
}
