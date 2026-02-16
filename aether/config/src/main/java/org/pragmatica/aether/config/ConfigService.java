package org.pragmatica.aether.config;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

/// Service for loading typed configuration sections from aether.toml.
///
/// Provides synchronous and asynchronous methods for loading configuration
/// sections and binding them to typed config classes.
///
/// Example usage:
/// ```{@code
/// ConfigService.instance()
///     .flatMap(service -> service.config("database.primary", DatabaseConnectorConfig.class))
///     .onSuccess(config -> // use config);
/// }```
public interface ConfigService {
    /// Load a configuration section and bind it to the specified type.
    ///
    /// @param section     Dot-separated section path (e.g., "database.primary")
    /// @param configClass The configuration class to bind to
    /// @param <T>         Configuration type
    /// @return Result containing the configuration or error
    <T> Result<T> config(String section, Class<T> configClass);

    /// Load a configuration section asynchronously.
    ///
    /// @param section     Dot-separated section path (e.g., "database.primary")
    /// @param configClass The configuration class to bind to
    /// @param <T>         Configuration type
    /// @return Promise containing the configuration or error
    default <T> Promise<T> configAsync(String section, Class<T> configClass) {
        return config(section, configClass).async();
    }

    /// Check if a configuration section exists.
    ///
    /// @param section Dot-separated section path
    /// @return true if section exists
    boolean hasSection(String section);

    /// Get raw string value from configuration.
    ///
    /// @param key Dot-separated key path
    /// @return Option containing the value if present
    Option<String> getString(String key);

    /// Get raw integer value from configuration.
    ///
    /// @param key Dot-separated key path
    /// @return Option containing the value if present
    Option<Integer> getInt(String key);

    /// Get raw boolean value from configuration.
    ///
    /// @param key Dot-separated key path
    /// @return Option containing the value if present
    Option<Boolean> getBoolean(String key);

    // Static accessor pattern
    /// Get the global ConfigService instance.
    ///
    /// @return ConfigService if configured, empty otherwise
    static Option<ConfigService> instance() {
        return ConfigServiceHolder.instance();
    }

    /// Set the global ConfigService instance.
    ///
    /// Called by AetherNode during startup.
    ///
    /// @param service ConfigService implementation
    /// @return Result indicating success
    static Result<Unit> setInstance(ConfigService service) {
        return ConfigServiceHolder.setInstance(service);
    }

    /// Clear the global ConfigService instance.
    ///
    /// Called during shutdown or in tests.
    /// @return Result indicating success
    static Result<Unit> clear() {
        return ConfigServiceHolder.clear();
    }
}
