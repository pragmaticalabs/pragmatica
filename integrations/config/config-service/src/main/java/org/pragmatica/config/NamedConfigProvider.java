package org.pragmatica.config;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// Decorator that wraps a delegate `ConfigurationProvider` with an explicit short display name.
///
/// Intended for layer attribution in [LayeredConfigProvider.sourceOf]: when a composite is
/// assembled out of leaf providers whose own `name()` is implementation-specific (e.g.
/// `"DynamicConfigurationProvider[...]"`), wrap each leaf with a canonical short label
/// (`"KV"`, `"node.toml"`, `"slice.toml"`) so operator-facing tooling can render which
/// layer produced a given value.
///
/// All `ConfigurationProvider` operations are delegated unchanged. Only [#displayName()]
/// and [#name()] reflect the override label.
///
/// Thread safety mirrors the delegate.
public final class NamedConfigProvider implements ConfigurationProvider {
    private final String displayName;
    private final ConfigurationProvider delegate;

    private NamedConfigProvider(String displayName, ConfigurationProvider delegate) {
        this.displayName = displayName;
        this.delegate = delegate;
    }

    /// Wrap a provider with a canonical short display name.
    ///
    /// @param displayName Short label used for layer attribution (e.g. `"KV"`, `"node.toml"`,
    ///                    `"slice.toml"`)
    /// @param delegate    Backing provider; all operations delegate to it
    /// @return New `NamedConfigProvider` instance
    public static NamedConfigProvider namedConfigProvider(String displayName, ConfigurationProvider delegate) {
        return new NamedConfigProvider(displayName, delegate);
    }

    /// Underlying provider (for callers that need to introspect past the label).
    public ConfigurationProvider delegate() {
        return delegate;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public Option<String> getString(String key) {
        return delegate.getString(key);
    }

    @Override
    public Set<String> keys() {
        return delegate.keys();
    }

    @Override
    public Set<String> staticKeys() {
        return delegate.staticKeys();
    }

    @Override
    public Map<String, String> asMap() {
        return delegate.asMap();
    }

    @Override
    public List<ConfigSource> sources() {
        return delegate.sources();
    }

    @Override
    public String name() {
        return displayName;
    }

    @Override
    public Result<ConfigSource> reload() {
        return delegate.reload()
                       .map(reloaded -> reloaded instanceof ConfigurationProvider cp
                                        ? namedConfigProvider(displayName, cp)
                                        : reloaded);
    }
}
