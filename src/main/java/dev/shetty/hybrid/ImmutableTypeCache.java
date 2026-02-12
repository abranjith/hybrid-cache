package dev.shetty.hybrid;

import java.lang.reflect.Modifier;

/**
 * Provides utility methods to determine if a type is immutable.
 * <p>
 * This class is a Java conversion of the C# {@code ImmutableTypeCache} and is used
 * internally by the caching system to optimize how cache entries are stored and handled.
 * By identifying immutable types, the system can avoid the overhead of creating defensive
 * copies of cached objects, leading to improved performance and reduced memory allocation.
 * The logic checks for well-known immutable types, primitive types, and types explicitly
 * marked with the {@link ImmutableObject} annotation.
 */
public final class ImmutableTypeCache {

    private static final java.util.Set<Class<?>> IMMUTABLE_TYPES = java.util.Set.of(
            String.class,
            Integer.class,
            Long.class,
            Boolean.class,
            Double.class,
            Float.class,
            Character.class,
            Byte.class,
            Short.class,
            java.time.Instant.class,
            java.time.LocalDate.class,
            java.time.LocalDateTime.class,
            java.util.UUID.class
    );

    private ImmutableTypeCache() {
        // Prevent instantiation of this utility class.
    }

    public static boolean isImmutable(Class<?> type) {
        return isTypeImmutable(type);
    }


    /**
     * Checks if a given type is considered immutable.
     *
     * @param type The type to check.
     * @return {@code true} if the type is immutable; otherwise, {@code false}.
     */
    static boolean isTypeImmutable(Class<?> type) {
        if (type.isPrimitive()) {
            return true;
        }

        if (isImmutableInternal(type)) {
            return true;
        }

        // For primitive types or final classes, we check for an explicit immutability annotation.
        // This corresponds to IsValueType or a sealed class in C#.
        if (Modifier.isFinal(type.getModifiers())) {
            // Check for @ImmutableObject, trusting the annotation's declaration.
            ImmutableObject annotation = type.getAnnotation(ImmutableObject.class);
            return annotation != null && annotation.value();
        }

        //TODO - may be find a way to handle Optional<T>

        return false;
    }

    /**
     * Internal method to check if a type is immutable.
     * This method is used by the caching system to determine if defensive copies are needed.
     *
     * @param type The type to check.
     * @return {@code true} if the type is immutable; otherwise, {@code false}.
     */
    private static boolean isImmutableInternal(Class<?> type) {
        Class<?> unwrappedType = getPrimitiveType(type);
        if (unwrappedType != null && unwrappedType.isPrimitive()) {
            return true;
        }
        return IMMUTABLE_TYPES.contains(type) ||
                type.isEnum() ||
                type.isRecord() ||
                (type.isArray() && isImmutable(type.getComponentType()));
    }

    /**
     * Gets the primitive type for a given wrapper class.
     *
     * @param wrapperClass The wrapper class (e.g., Integer.class).
     * @return The corresponding primitive type (e.g., int.class), or {@code null} if the
     *         class is not a standard wrapper type.
     */
    private static Class<?> getPrimitiveType(Class<?> wrapperClass) {
        try {
            // All standard wrapper classes (Integer, Double, etc.) have a public static final
            // field named "TYPE" that holds the Class object for the primitive type.
            return (Class<?>) wrapperClass.getField("TYPE").get(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // This exception occurs if the class is not a standard wrapper type.
            return null;
        }
    }
}