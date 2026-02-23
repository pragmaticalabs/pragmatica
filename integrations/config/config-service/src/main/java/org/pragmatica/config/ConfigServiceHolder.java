package org.pragmatica.config;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.concurrent.atomic.AtomicReference;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Result.unitResult;

/// Thread-safe holder for the global ConfigService instance.
///
/// This is an internal implementation detail. Use {@link ConfigService#instance()}
/// to access the global instance.
sealed interface ConfigServiceHolder {
    AtomicReference<ConfigService> INSTANCE = new AtomicReference<>();

    static Option<ConfigService> instance() {
        return option(INSTANCE.get());
    }

    static Result<Unit> setInstance(ConfigService service) {
        INSTANCE.set(service);
        return unitResult();
    }

    static Result<Unit> clear() {
        INSTANCE.set(null);
        return unitResult();
    }

    record unused() implements ConfigServiceHolder {
        public static Result<unused> unused() {
            return success(new unused());
        }
    }
}
