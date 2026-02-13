package org.pragmatica.aether.config;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/// Wraps a base ConfigurationProvider with a mutable overlay.
///
/// Values in the overlay take precedence over the base provider.
/// The overlay is thread-safe via ConcurrentHashMap.
/// Removing an overlay key restores visibility of the base value.
public final class DynamicConfigurationProvider implements ConfigurationProvider {
    private final ConfigurationProvider base;
    private final ConcurrentHashMap<String, String> overlay = new ConcurrentHashMap<>();

    private DynamicConfigurationProvider(ConfigurationProvider base) {
        this.base = base;
    }

    public static DynamicConfigurationProvider dynamicConfigurationProvider(ConfigurationProvider base) {
        return new DynamicConfigurationProvider(base);
    }

    @Override
    public Option<String> getString(String key) {
        return Option.option(overlay.get(key))
                     .orElse(() -> base.getString(key));
    }

    @Override
    public Set<String> keys() {
        var allKeys = new LinkedHashSet<>(base.keys());
        allKeys.addAll(overlay.keySet());
        return Collections.unmodifiableSet(allKeys);
    }

    @Override
    public Map<String, String> asMap() {
        var merged = new LinkedHashMap<>(base.asMap());
        merged.putAll(overlay);
        return Collections.unmodifiableMap(merged);
    }

    @Override
    public List<ConfigSource> sources() {
        return base.sources();
    }

    @Override
    public String name() {
        return "DynamicConfigurationProvider[" + base.name() + "]";
    }

    @Override
    public Result<ConfigSource> reload() {
        return base.reload()
                   .map(_ -> this);
    }

    public void put(String key, String value) {
        overlay.put(key, value);
    }

    public void remove(String key) {
        overlay.remove(key);
    }

    public Map<String, String> overlayMap() {
        return Map.copyOf(overlay);
    }
}
