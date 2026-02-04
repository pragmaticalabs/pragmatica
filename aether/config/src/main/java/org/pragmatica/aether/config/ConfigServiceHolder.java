package org.pragmatica.aether.config;

import org.pragmatica.lang.Option;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe holder for the global ConfigService instance.
 * <p>
 * This is an internal implementation detail. Use {@link ConfigService#instance()}
 * to access the global instance.
 */
final class ConfigServiceHolder {
    private static final AtomicReference<ConfigService> INSTANCE = new AtomicReference<>();

    private ConfigServiceHolder() {}

    static Option<ConfigService> instance() {
        return Option.option(INSTANCE.get());
    }

    static void setInstance(ConfigService service) {
        INSTANCE.set(service);
    }

    static void clear() {
        INSTANCE.set(null);
    }
}
