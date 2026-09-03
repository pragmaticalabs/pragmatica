package org.pragmatica.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


/// Immutable configuration provider over an in-memory `Map<String, String>` of dot-notation keys.
///
/// Intended primarily as the slice-intrinsic layer in a [LayeredConfigProvider]: the parsed
/// `META-INF/resources.toml` of a slice is flattened to `section.key → value` form and wrapped
/// once at slice load. The provider holds no mutable state and is safe to share.
///
/// Reload is a no-op (returns `this`): the underlying map is captured at construction. If the
/// slice changes, a new provider is constructed by the loader (typically on slice reload).
public final class IntrinsicConfigProvider implements ConfigurationProvider {
    private final String name;
    private final Map<String, String> values;

    private IntrinsicConfigProvider(String name, Map<String, String> values) {
        this.name = name;
        this.values = Map.copyOf(values);
    }

    /// Create an intrinsic provider over a flat dot-notation map.
    ///
    /// @param name   Human-readable provider name for logging
    /// @param values Flat map of `section.key → value` pairs (defensive-copied)
    /// @return New `IntrinsicConfigProvider` instance
    public static IntrinsicConfigProvider intrinsicConfigProvider(String name, Map<String, String> values) {
        return new IntrinsicConfigProvider(name, values);
    }

    @Override
    public Option<String> getString(String key) {
        return option(values.get(key));
    }

    @Override
    public Set<String> keys() {
        return values.keySet();
    }

    /// Explicit override, though it matches [ConfigurationProvider#staticKeys()]'s default: this
    /// provider IS the static/file-backed layer — a slice's own `resources.toml`, flattened once
    /// at load — and no dynamic/environmental source is ever wrapped into this type, so every key
    /// held here counts.
    @Override
    public Set<String> staticKeys() {
        return keys();
    }

    @Override
    public Map<String, String> asMap() {
        return new LinkedHashMap<>(values);
    }

    @Override
    public List<ConfigSource> sources() {
        return List.of();
    }

    @Override
    public String name() {
        return "IntrinsicConfigProvider[" + name + "]";
    }

    @Override
    public Result<ConfigSource> reload() {
        return success(this);
    }
}
