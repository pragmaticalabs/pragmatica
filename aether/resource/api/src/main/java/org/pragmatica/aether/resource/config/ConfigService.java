package org.pragmatica.aether.resource.config;

import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.List;


/// Configuration service providing hierarchical configuration management.
/// Supports GLOBAL, NODE, and SLICE scopes with override semantics.
/// More specific scopes override less specific ones.
public interface ConfigService extends Slice {
    Promise<Option<String>> getString(String section, String key);
    Promise<Option<String>> getString(ConfigScope scope, String section, String key);
    Promise<Option<Integer>> getInt(String section, String key);
    Promise<Option<Integer>> getInt(ConfigScope scope, String section, String key);
    Promise<Option<Boolean>> getBoolean(String section, String key);
    Promise<Option<Boolean>> getBoolean(ConfigScope scope, String section, String key);
    Promise<Option<Double>> getDouble(String section, String key);
    Promise<Option<Double>> getDouble(ConfigScope scope, String section, String key);
    Promise<Option<List<String>>> getStringList(String section, String key);
    Promise<Option<List<String>>> getStringList(ConfigScope scope, String section, String key);
    Promise<Unit> set(ConfigScope scope, String section, String key, Object value);
    Result<Unit> loadToml(ConfigScope scope, String content);
    TomlDocument getDocument(ConfigScope scope);
    Promise<ConfigSubscription> watch(String section, String key, Fn1<Unit, Option<String>> callback);

    static ConfigService configService() {
        return new InMemoryConfigService();
    }

    @Override default Promise<Unit> start() {
        return Promise.success(Unit.unit());
    }

    @Override default Promise<Unit> stop() {
        return Promise.success(Unit.unit());
    }

    @Override default List<SliceMethod<?, ?>> methods() {
        return List.of();
    }
}
