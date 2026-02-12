package dev.shetty.utils;

/**
 * System utility methods for retrieving environment configuration and system information.
 */
public final class SystemUtils {
    
    private SystemUtils() {
        // Prevent instantiation
    }

    /**
     * Gets an environment variable as an int.
     * @param variable the environment variable name
     * @param defaultValue the default value if the variable is not set or cannot be parsed
     * @return the parsed integer value or the default value
     */
    public static int getEnvironmentInt(String variable, int defaultValue) {
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
     * Gets the current processor ID for the executing thread.
     * This is a simplified approach - Java doesn't directly expose thread affinity.
     * @return an approximate processor ID based on the current thread
     */
    public static int getCurrentProcessorId() {
        return Math.abs((int)(Thread.currentThread().threadId() % Runtime.getRuntime().availableProcessors()));
    }

    /**
     * Estimates current memory pressure based on available memory.
     * @return the current memory pressure level
     */
    public static MemoryPressure getMemoryPressure() {
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
     * Enum representing different memory pressure levels.
     */
    public enum MemoryPressure {
        LOW, MEDIUM, HIGH
    }
}
