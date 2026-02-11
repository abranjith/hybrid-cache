package dev.shetty.abstractions;

import java.nio.ByteBuffer;

/**
 * Provides per-type serialization and deserialization support for HybridCache.
 * @param <T> The type being serialized/deserialized.
 */
public interface IHybridCacheSerializer<T> {
    /**
     * Deserializes a value of type T from the provided ByteBuffer.
     * @param source The buffer containing the serialized data.
     * @return The deserialized value.
     */
    T deserialize(ByteBuffer source);

    /**
     * Serializes the given value to a byte array.
     * @param value The value to serialize.
     * @return The serialized data.
     */
    byte[] serialize(T value);
}
