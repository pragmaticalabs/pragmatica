package org.pragmatica.aether.slice;
/**
 * Aspect for intercepting slice method invocations.
 * Wraps a slice instance to add cross-cutting behavior (logging, metrics, etc.).
 *
 * @param <T> The slice type being wrapped
 */
@FunctionalInterface
public interface Aspect<T> {
    /**
     * Wrap a slice instance with aspect behavior.
     *
     * @param instance The slice instance to wrap
     * @return Wrapped instance with aspect behavior
     */
    T apply(T instance);

    /**
     * Identity aspect - passes through unchanged.
     */
    static <T> Aspect<T> identity() {
        return instance -> instance;
    }

    /**
     * Compose this aspect with another.
     * The other aspect is applied first, then this one.
     *
     * @param before Aspect to apply before this one
     * @return Composed aspect
     */
    default Aspect<T> compose(Aspect<T> before) {
        return instance -> apply(before.apply(instance));
    }

    /**
     * Compose this aspect with another.
     * This aspect is applied first, then the other one.
     *
     * @param after Aspect to apply after this one
     * @return Composed aspect
     */
    default Aspect<T> andThen(Aspect<T> after) {
        return instance -> after.apply(apply(instance));
    }
}
