package org.pragmatica.aether.slice;

@FunctionalInterface public interface Aspect<T> {
    T apply(T instance);

    static <T> Aspect<T> identity() {
        return instance -> instance;
    }

    default Aspect<T> compose(Aspect<T> before) {
        return instance -> apply(before.apply(instance));
    }

    default Aspect<T> andThen(Aspect<T> after) {
        return instance -> after.apply(apply(instance));
    }
}
