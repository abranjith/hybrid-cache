package dev.shetty.abstractions;

/**
 * Factory provider for per-type {@link IHybridCacheSerializer} instances.
 */
public interface IHybridCacheSerializerFactory {
    /**
     * Attempts to create a serializer for the specified type.
     *
     * @param <T>  the type to serialize
     * @param type the class of the type to serialize
     * @return a serializer for the type, or {@code null} if this factory does not support it
     */
    <T> IHybridCacheSerializer<T> tryCreateSerializer(Class<T> type);
}
