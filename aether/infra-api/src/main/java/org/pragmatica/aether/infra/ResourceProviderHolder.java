package org.pragmatica.aether.infra;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.concurrent.atomic.AtomicReference;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.unitResult;

/// Thread-safe holder for the global ResourceProvider instance.
///
/// This is an internal implementation detail. Use {@link ResourceProvider#instance()}
/// to access the global instance.
sealed interface ResourceProviderHolder {
    AtomicReference<ResourceProvider> INSTANCE = new AtomicReference<>();

    static Option<ResourceProvider> instance() {
        return option(INSTANCE.get());
    }

    static Result<Unit> setInstance(ResourceProvider provider) {
        INSTANCE.set(provider);
        return unitResult();
    }

    static Result<Unit> clear() {
        INSTANCE.set(null);
        return unitResult();
    }

    record unused() implements ResourceProviderHolder {}
}
