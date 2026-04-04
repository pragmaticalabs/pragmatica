package org.pragmatica.aether.slice;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.List;


/// No-op implementation of ConfigFacade for backward compatibility.
///
/// All `require*` methods return failure; all `get*` methods return empty.
/// Used when no config service is wired (e.g., testing or slices without config).
enum NoOpConfigFacade implements ConfigFacade {
    INSTANCE;
    private static final Cause NO_CONFIG = Causes.cause("Config service not available");
    @Override public Result<String> requireString(String section, String key) {
        return NO_CONFIG.result();
    }
    @Override public Result<Integer> requireInt(String section, String key) {
        return NO_CONFIG.result();
    }
    @Override public Result<Long> requireLong(String section, String key) {
        return NO_CONFIG.result();
    }
    @Override public Result<Double> requireDouble(String section, String key) {
        return NO_CONFIG.result();
    }
    @Override public Result<Boolean> requireBoolean(String section, String key) {
        return NO_CONFIG.result();
    }
    @Override public Result<List<String>> requireStringList(String section, String key) {
        return NO_CONFIG.result();
    }
    @Override public Option<String> getString(String section, String key) {
        return Option.none();
    }
    @Override public Option<Integer> getInt(String section, String key) {
        return Option.none();
    }
    @Override public Option<Long> getLong(String section, String key) {
        return Option.none();
    }
    @Override public Option<Double> getDouble(String section, String key) {
        return Option.none();
    }
    @Override public Option<Boolean> getBoolean(String section, String key) {
        return Option.none();
    }
}
