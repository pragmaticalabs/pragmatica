package org.pragmatica.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Result.success;


/// Hierarchical configuration provider that composes layers with strict left-wins precedence.
///
/// Walk semantics: for each query, layers are consulted in declaration order (index `0` first).
/// The first layer that returns a present value wins; no merging occurs at the value level.
///
/// Layer composition is the foundation of Aether's node- and slice-level configuration:
/// ```
/// node-composite  = KV-overlay ⊕ node.toml
/// slice-composite = slice.toml ⊕ node-composite
/// ```
///
/// Where `⊕` means "left wins on hit; else fall through to right". Effective precedence for
/// slice queries: `KV > node.toml > slice.toml`.
///
/// Differences vs. [ConfigurationProvider.builder()]:
/// - No priority arithmetic — order is declarative
/// - No deep-merge — only the declaring layer's value is returned for any given key
/// - Layers may be other `LayeredConfigProvider` instances (composition is associative)
///
/// Thread safety: this provider is itself stateless beyond the immutable layer list. Each
/// underlying layer must provide its own thread-safety guarantees (e.g., a mutable
/// `DynamicConfigurationProvider` overlay is thread-safe via its internal `ConcurrentHashMap`).
public final class LayeredConfigProvider implements ConfigurationProvider {
    private final List<ConfigurationProvider> layers;

    private LayeredConfigProvider(List<ConfigurationProvider> layers) {
        this.layers = List.copyOf(layers);
    }

    /// Create a new layered provider with the given layers (left = top priority).
    ///
    /// @param layers Ordered list of providers; index 0 wins on conflict
    /// @return New `LayeredConfigProvider` instance
    public static LayeredConfigProvider layered(List<ConfigurationProvider> layers) {
        return new LayeredConfigProvider(layers);
    }

    /// Get the ordered list of layers (index 0 = top priority).
    public List<ConfigurationProvider> layers() {
        return layers;
    }

    @Override
    public Option<String> getString(String key) {
        for (var layer : layers) {
            var value = layer.getString(key);

            if (value.isPresent()) {
                return value;
            }
        }

        return none();
    }

    /// Resolve the layer that first matched the given key, plus the value it yielded.
    ///
    /// Walks layers in declaration order (left wins). When the matching layer is itself a
    /// `LayeredConfigProvider`, recursively unwraps it so the returned `layerName` is always
    /// the leaf (e.g. for `slice-composite = slice.toml ⊕ node-composite` where
    /// `node-composite = KV ⊕ node.toml`, a hit in KV reports `layerName="KV"`, not
    /// `layerName="LayeredConfigProvider[...]"`).
    ///
    /// Returns [Option#none()] when no layer matches.
    public Option<SourceAttribution> sourceOf(String key) {
        for (var layer : layers) {
            var hit = attributeIn(layer, key);

            if (hit.isPresent()) {
                return hit;
            }
        }

        return none();
    }

    private static Option<SourceAttribution> attributeIn(ConfigurationProvider layer, String key) {
        return layer instanceof LayeredConfigProvider nested
               ? nested.sourceOf(key)
               : layer.getString(key)
                      .map(value -> new SourceAttribution(layer.displayName(),
                                                          value));
    }

    /// Attribution record describing which layer of a layered provider matched a key.
    ///
    /// `layerName` is the leaf layer's [ConfigurationProvider#displayName()]; nested
    /// composites are unwrapped by [LayeredConfigProvider#sourceOf]. `value` is the
    /// resolved value as returned by [ConfigurationProvider#getString].
    public record SourceAttribution(String layerName, String value) {}

    @Override
    public Set<String> keys() {
        var unionKeys = new LinkedHashSet<String>();

        for (var layer : layers) {
            unionKeys.addAll(layer.keys());
        }

        return Collections.unmodifiableSet(unionKeys);
    }

    /// Union of every layer's [ConfigurationProvider#staticKeys()] — mirrors [#keys()] exactly,
    /// just calling the static-only accessor, so a dynamic layer (e.g. a KV-overlay
    /// [DynamicConfigurationProvider]) contributes nothing here regardless of its position.
    @Override
    public Set<String> staticKeys() {
        var unionKeys = new LinkedHashSet<String>();

        for (var layer : layers) {
            unionKeys.addAll(layer.staticKeys());
        }

        return Collections.unmodifiableSet(unionKeys);
    }

    @Override
    public Map<String, String> asMap() {
        var merged = new LinkedHashMap<String, String>();
        // Walk layers in reverse so top-priority layer (index 0) overwrites lower layers.
        for (var i = layers.size() - 1; i >= 0; i--) {
            merged.putAll(layers.get(i).asMap());
        }

        return Collections.unmodifiableMap(merged);
    }

    @Override
    public List<ConfigSource> sources() {
        return layers.stream()
                     .flatMap(layer -> layer.sources()
                                            .stream())
                     .toList();
    }

    @Override
    public String name() {
        return layers.stream()
                     .map(ConfigurationProvider::name)
                     .collect(Collectors.joining(", ", "LayeredConfigProvider[", "]"));
    }

    @Override
    public Result<ConfigSource> reload() {
        var reloadedLayers = new ArrayList<ConfigurationProvider>();

        for (var layer : layers) {
            var reloaded = layer.reload();

            if (reloaded.isFailure()) {
                return reloaded;
            }

            reloadedLayers.add((ConfigurationProvider) reloaded.unwrap());
        }

        return success(new LayeredConfigProvider(reloadedLayers));
    }
}
