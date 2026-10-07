package org.pragmatica.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.unitResult;


/// Wraps a base ConfigurationProvider with a mutable overlay.
///
/// Values in the overlay take precedence over the base provider.
/// The overlay is thread-safe via ConcurrentHashMap.
/// Removing an overlay key restores visibility of the base value.
public final class DynamicConfigurationProvider implements ConfigurationProvider {
    private final ConfigurationProvider base;
    private final ConcurrentHashMap<String, String> overlay;

    private DynamicConfigurationProvider(ConfigurationProvider base, ConcurrentHashMap<String, String> overlay) {
        this.base = base;
        this.overlay = overlay;
    }

    public static DynamicConfigurationProvider dynamicConfigurationProvider(ConfigurationProvider base) {
        return new DynamicConfigurationProvider(base, new ConcurrentHashMap<>());
    }

    @Override
    public Option<String> getString(String key) {
        return option(overlay.get(key)).orElse(() -> base.getString(key));
    }

    @Override
    public Set<String> keys() {
        var allKeys = new LinkedHashSet<>(base.keys());

        allKeys.addAll(overlay.keySet());

        return Collections.unmodifiableSet(allKeys);
    }

    /// Excludes the mutable overlay entirely — it IS the KV-replayed-at-startup dynamic layer
    /// [ConfigurationProvider#staticKeys()] exists to keep out of [StrictKeys] validation.
    @Override
    public Set<String> staticKeys() {
        return base.staticKeys();
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

    /// #1326 sibling: this used to reload the base, discard the result and return `this`, so the
    /// returned provider still read the PRE-reload base. It now returns a provider over the reloaded
    /// base that SHARES this overlay: the overlay is the live dynamic layer writers hold this instance
    /// for, so a write made through either is visible through both.
    @Override
    public Result<ConfigSource> reload() {
        return base.reload()
                   .map(reloaded -> new DynamicConfigurationProvider(asProvider(reloaded),
                                                                     overlay));
    }

    private static ConfigurationProvider asProvider(ConfigSource source) {
        return source instanceof ConfigurationProvider provider
               ? provider
               : ConfigurationProvider.configurationProvider(source);
    }

    public Result<Unit> put(String key, String value) {
        overlay.put(key, value);

        return unitResult();
    }

    public Result<Unit> remove(String key) {
        overlay.remove(key);

        return unitResult();
    }

    public Map<String, String> overlayMap() {
        return Map.copyOf(overlay);
    }
}
