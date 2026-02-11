package dev.shetty.internal;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ArrayPoolTest {
    @Test
    void testGetByteArray() {
        ArrayPool<Byte> pool = ArrayPool.getShared(Byte.class);
        Byte[] arr = pool.take(10);
        assertNotNull(arr);
        assertEquals(16, arr.length);
        pool.giveBack(arr);
    }

    @Test
    void testGetStringArray() {
        ArrayPool<String> pool = ArrayPool.getShared(String.class);
        String[] arr = pool.take(5);
        assertNotNull(arr);
        assertEquals(16, arr.length);
        pool.giveBack(arr);
    }

    @Test
    void testGetCustomClassArray() {
        class Dummy {}
        ArrayPool<Dummy> pool = ArrayPool.getShared(Dummy.class);
        Dummy[] arr = pool.take(17);
        assertNotNull(arr);
        assertEquals(32, arr.length);
        pool.giveBack(arr);
    }

    @Test
    void testSharedInstance() {
        ArrayPool<Byte> pool1 = ArrayPool.getShared(Byte.class);
        ArrayPool<Byte> pool2 = ArrayPool.getShared(Byte.class);
        assertSame(pool1, pool2, "getShared should return the same instance for the same type");

        Byte[] arr1 = pool1.take(10);
        Byte[] arr2 = pool2.take(10);
        assertNotEquals(arr1, arr2, "Both pools should return different array instances");

        pool1.giveBack(arr1);
        pool2.giveBack(arr2);
    }
}