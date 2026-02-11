package dev.shetty.hybrid;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Specifies that an object's type is immutable. An immutable type is one whose instances
 * cannot be modified after they are created.
 * <p>
 * This annotation allows types to be treated as immutable even if they do not meet
 * the strict criteria of being a primitive or a final class with all final fields.
 * When this annotation is present with its value set to {@code true}, it is a signal
 * to the caching system that instances of this type can be shared without the need

 * for defensive copies.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ImmutableObject {
    /**
     * Gets a value indicating whether the object is immutable.
     *
     * @return {@code true} if the object is immutable; otherwise, {@code false}.
     */
    boolean value() default true;
}
