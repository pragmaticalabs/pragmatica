package org.pragmatica.aether.infra;

import org.pragmatica.lang.Option;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe holder for the global InfraStore instance.
 */
final class InfraStoreHolder {
    private static final AtomicReference<InfraStore> INSTANCE = new AtomicReference<>();

    private InfraStoreHolder() {}

    static Option<InfraStore> instance() {
        return Option.option(INSTANCE.get());
    }

    static void setInstance(InfraStore store) {
        INSTANCE.set(store);
    }

    static void clear() {
        INSTANCE.set(null);
    }
}
