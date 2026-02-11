package dev.shetty.internal;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SharedArrayPoolTest {
    private final SharedArrayPool<Byte> pool = new SharedArrayPool<Byte>(Byte.class);

    @Test
    void testTakeWithMinimumLengthLessThan16() {
        Byte[] arr = pool.take(0);
        assertNotNull(arr);
        assertEquals(0, arr.length);

        arr = pool.take(3);
        assertNotNull(arr);
        assertEquals(16, arr.length);
    }

    @Test
    void testTakeWithMinimumLengthGreaterThan16() {
        Byte[] arr = pool.take(17);
        assertNotNull(arr);
        assertEquals(32, arr.length);
    }

    @Test
    void testMultipleCallsToTake() {
        Byte[] arr1 = pool.take(16);
        Byte[] arr2 = pool.take(5);
        assertNotNull(arr1);
        assertNotNull(arr2);
        assertEquals(16, arr1.length);
        assertEquals(16, arr2.length);
        // Should not be the same instance unless given back
        assertNotEquals(arr1, arr2);
    }

    @Test
    void testMultipleCallsToTakeWithReuse() {
        Byte[] arr1 = pool.take(16);
        pool.giveBack(arr1);
        Byte[] arr2 = pool.take(5);
        assertNotNull(arr1);
        assertNotNull(arr2);
        assertEquals(16, arr1.length);
        assertEquals(16, arr2.length);
        // Should be the same instance as it was given back
        assertEquals(arr1, arr2);
    }


    @Test
    void testMultipleCallsToTakeWithReuseFromDifferentThreads() throws Exception {
        final Byte[][] arrHolder = new Byte[2][];
        Thread t1 = new Thread(() -> {
            //Have to do this a couple of times because unless there are more than 1 arrays created by the pool
            //of the same size, parition poll will not get returned arrays (only TLS will hold the single array instance)
            arrHolder[0] = pool.take(16);
            var arr2 = pool.take(5);
            // Give back the first array, this now sits in TLS
            pool.giveBack(arrHolder[0]);
            // Now give back the second array, the previous one in TLS will be moved to parition array (can be used by other threads)
            // and this one will be moved to TLS
            pool.giveBack(arr2);
        });
        Thread t2 = new Thread(() -> arrHolder[1] = pool.take(5));

        t1.start();
        t1.join();
        t2.start();
        t2.join();

        assertNotNull(arrHolder[0]);
        assertNotNull(arrHolder[1]);
        assertEquals(16, arrHolder[0].length);
        assertEquals(16, arrHolder[1].length);
        // Should be the same instance as it was given back, even across threads
        assertEquals(arrHolder[0], arrHolder[1]);
    }
}