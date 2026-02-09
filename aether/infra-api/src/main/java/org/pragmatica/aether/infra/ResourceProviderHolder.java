package org.pragmatica.aether.infra;

import org.pragmatica.lang.Option;

import java.util.concurrent.atomic.AtomicReference;

/// Thread-safe holder for the global ResourceProvider instance.
///
/// This is an internal implementation detail. Use {@link ResourceProvider#instance()}
/// to access the global instance.
final class ResourceProviderHolder {
    private static final AtomicReference<ResourceProvider> INSTANCE = new AtomicReference<>();

    private ResourceProviderHolder() {}

    static Option<ResourceProvider> instance() {
        return Option.option(INSTANCE.get());
    }

    static void setInstance(ResourceProvider provider) {
        INSTANCE.set(provider);
    }

    static void clear() {
        INSTANCE.set(null);
    }
}
