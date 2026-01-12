package org.pragmatica.aether.infra.config;

import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.List;

/**
 * Configuration service providing hierarchical configuration management.
 * Supports GLOBAL, NODE, and SLICE scopes with override semantics.
 * More specific scopes override less specific ones.
 */
public interface ConfigService extends Slice {
    /**
     * Get a string configuration value with hierarchical lookup.
     * Looks up in SLICE -> NODE -> GLOBAL order.
     *
     * @param section Configuration section
     * @param key     Configuration key
     * @return Option containing the value if found
     */
    Promise<Option<String>> getString(String section, String key);

    /**
     * Get a string configuration value at a specific scope.
     *
     * @param scope   Configuration scope
     * @param section Configuration section
     * @param key     Configuration key
     * @return Option containing the value if found
     */
    Promise<Option<String>> getString(ConfigScope scope, String section, String key);

    /**
     * Get an integer configuration value with hierarchical lookup.
     *
     * @param section Configuration section
     * @param key     Configuration key
     * @return Option containing the value if found and valid
     */
    Promise<Option<Integer>> getInt(String section, String key);

    /**
     * Get an integer configuration value at a specific scope.
     *
     * @param scope   Configuration scope
     * @param section Configuration section
     * @param key     Configuration key
     * @return Option containing the value if found and valid
     */
    Promise<Option<Integer>> getInt(ConfigScope scope, String section, String key);

    /**
     * Get a boolean configuration value with hierarchical lookup.
     *
     * @param section Configuration section
     * @param key     Configuration key
     * @return Option containing the value if found and valid
     */
    Promise<Option<Boolean>> getBoolean(String section, String key);

    /**
     * Get a boolean configuration value at a specific scope.
     *
     * @param scope   Configuration scope
     * @param section Configuration section
     * @param key     Configuration key
     * @return Option containing the value if found and valid
     */
    Promise<Option<Boolean>> getBoolean(ConfigScope scope, String section, String key);

    /**
     * Get a double configuration value with hierarchical lookup.
     *
     * @param section Configuration section
     * @param key     Configuration key
     * @return Option containing the value if found and valid
     */
    Promise<Option<Double>> getDouble(String section, String key);

    /**
     * Get a double configuration value at a specific scope.
     *
     * @param scope   Configuration scope
     * @param section Configuration section
     * @param key     Configuration key
     * @return Option containing the value if found and valid
     */
    Promise<Option<Double>> getDouble(ConfigScope scope, String section, String key);

    /**
     * Get a string list configuration value with hierarchical lookup.
     *
     * @param section Configuration section
     * @param key     Configuration key
     * @return Option containing the list if found
     */
    Promise<Option<List<String>>> getStringList(String section, String key);

    /**
     * Get a string list configuration value at a specific scope.
     *
     * @param scope   Configuration scope
     * @param section Configuration section
     * @param key     Configuration key
     * @return Option containing the list if found
     */
    Promise<Option<List<String>>> getStringList(ConfigScope scope, String section, String key);

    /**
     * Set a configuration value at a specific scope.
     *
     * @param scope   Configuration scope
     * @param section Configuration section
     * @param key     Configuration key
     * @param value   Configuration value
     * @return Unit on success
     */
    Promise<Unit> set(ConfigScope scope, String section, String key, Object value);

    /**
     * Load configuration from TOML content at a specific scope.
     *
     * @param scope   Configuration scope
     * @param content TOML content string
     * @return Result of parsing, Unit on success
     */
    Result<Unit> loadToml(ConfigScope scope, String content);

    /**
     * Get the full TomlDocument for a scope.
     *
     * @param scope Configuration scope
     * @return The TomlDocument for the scope
     */
    TomlDocument getDocument(ConfigScope scope);

    /**
     * Watch a configuration key for changes.
     *
     * @param section  Configuration section
     * @param key      Configuration key
     * @param callback Callback invoked on value change
     * @return Subscription handle to cancel the watch
     */
    Promise<ConfigSubscription> watch(String section, String key, Fn1<Unit, Option<String>> callback);

    /**
     * Factory method for in-memory implementation.
     *
     * @return ConfigService instance
     */
    static ConfigService configService() {
        return new InMemoryConfigService();
    }

    @Override
    default Promise<Unit> start() {
        return Promise.success(Unit.unit());
    }

    @Override
    default Promise<Unit> stop() {
        return Promise.success(Unit.unit());
    }

    @Override
    default List<SliceMethod< ?, ?>> methods() {
        return List.of();
    }
}
