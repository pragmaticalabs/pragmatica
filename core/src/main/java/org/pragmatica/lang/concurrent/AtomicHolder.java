package org.pragmatica.lang.concurrent;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;

/// JBCT-compatible atomic holder that returns Option instead of nullable values.
/// Uses VarHandle internally for lock-free atomic operations.
public interface AtomicHolder<T> {
    Option<T> get();

    @Contract
    void set(T value);

    @Contract
    void clear();

    Option<T> getAndClear();

    Option<T> getAndSet(T value);

    boolean isEmpty();

    static <T> AtomicHolder<T> atomicHolder() {
        return new AtomicHolderImpl<>();
    }

    static <T> AtomicHolder<T> atomicHolder(T initial) {
        return new AtomicHolderImpl<>(initial);
    }
}
