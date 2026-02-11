package dev.shetty.internal;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Provides a partitioned set of locks to minimize contention during stampede coordination.
 * <p>
 * Uses 8 {@link ReentrantLock} instances, selected by hash code modulo 8.
 * This reduces contention when multiple threads are coordinating cache operations
 * for different keys simultaneously.
 * <p>
 * With Java 24's JEP 491 fixing {@code synchronized} pinning, we could use plain
 * {@code synchronized} blocks instead, but {@code ReentrantLock} remains cleaner
 * for this use case and provides better diagnostics.
 */
public final class PartitionedSyncLock {
    
    private static final int PARTITION_COUNT = 8;
    private static final int PARTITION_MASK = PARTITION_COUNT - 1;
    
    private final ReentrantLock[] locks;
    
    /**
     * Creates a new partitioned lock with the default number of partitions (8).
     */
    public PartitionedSyncLock() {
        this.locks = new ReentrantLock[PARTITION_COUNT];
        for (int i = 0; i < PARTITION_COUNT; i++) {
            locks[i] = new ReentrantLock();
        }
    }
    
    /**
     * Executes an action while holding the lock for the specified key.
     * <p>
     * The lock is selected based on the key's hash code to distribute
     * contention across partitions.
     * 
     * @param key the key to lock on
     * @param action the action to execute while holding the lock
     */
    public void execute(Object key, Runnable action) {
        ReentrantLock lock = selectLock(key);
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Executes a supplier while holding the lock for the specified key.
     * <p>
     * The lock is selected based on the key's hash code to distribute
     * contention across partitions.
     * 
     * @param <T> the return type
     * @param key the key to lock on
     * @param supplier the supplier to execute while holding the lock
     * @return the result of the supplier
     */
    public <T> T execute(Object key, Supplier<T> supplier) {
        ReentrantLock lock = selectLock(key);
        lock.lock();
        try {
            return supplier.get();
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Acquires the lock for the specified key.
     * <p>
     * Callers must ensure they call {@link #unlock(Object)} with the same key.
     * Consider using {@link #execute(Object, Runnable)} or {@link #execute(Object, Supplier)}
     * instead to ensure proper unlock.
     * 
     * @param key the key to lock on
     */
    public void lock(Object key) {
        selectLock(key).lock();
    }
    
    /**
     * Releases the lock for the specified key.
     * <p>
     * Must be called with the same key used in {@link #lock(Object)}.
     * 
     * @param key the key to unlock
     */
    public void unlock(Object key) {
        selectLock(key).unlock();
    }
    
    /**
     * Attempts to acquire the lock for the specified key without blocking.
     * <p>
     * If successful, callers must ensure they call {@link #unlock(Object)}.
     * 
     * @param key the key to try locking on
     * @return true if the lock was acquired, false otherwise
     */
    public boolean tryLock(Object key) {
        return selectLock(key).tryLock();
    }
    
    /**
     * Selects a lock based on the key's hash code.
     */
    private ReentrantLock selectLock(Object key) {
        int hash = key == null ? 0 : key.hashCode();
        int index = hash & PARTITION_MASK;
        return locks[index];
    }
}
