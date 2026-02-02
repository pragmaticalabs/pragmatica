package org.pragmatica.aether.infra;

import org.pragmatica.lang.Option;

/**
 * Thread-safe holder for the global InfraStore instance.
 */
final class InfraStoreHolder {
    private static volatile Option<InfraStore> instance = Option.none();

    private InfraStoreHolder() {}

    static Option<InfraStore> instance() {
        return instance;
    }

    static void setInstance(InfraStore store) {
        instance = Option.some(store);
    }

    static void clear() {
        instance = Option.none();
    }
}
