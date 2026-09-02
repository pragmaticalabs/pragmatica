package org.pragmatica.lang.concurrent;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;


final class AtomicHolderImpl<T> implements AtomicHolder<T> {
    private static final VarHandle VALUE = lookupValueHandle();

    @Contract
    private static VarHandle lookupValueHandle() {
        try {
            return MethodHandles.lookup().findVarHandle(AtomicHolderImpl.class, "value", Object.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private volatile Object value;

    AtomicHolderImpl() {}

    AtomicHolderImpl(T initial) {
        this.value = initial;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Option<T> get() {
        return Option.option((T) VALUE.getVolatile(this));
    }

    @Contract
    @Override
    public void set(T value) {
        VALUE.setVolatile(this, value);
    }

    @Contract
    @Override
    public void clear() {
        VALUE.setVolatile(this, null);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Option<T> getAndClear() {
        return Option.option((T) VALUE.getAndSet(this, null));
    }

    @SuppressWarnings("unchecked")
    @Override
    public Option<T> getAndSet(T value) {
        return Option.option((T) VALUE.getAndSet(this, value));
    }

    @Override
    public boolean isEmpty() {
        return VALUE.getVolatile(this) == null;
    }
}
