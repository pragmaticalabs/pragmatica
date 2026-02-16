package org.pragmatica.aether.config;

import org.pragmatica.aether.config.internal.DeepMerger;
import org.pragmatica.aether.config.source.EnvironmentConfigSource;
import org.pragmatica.aether.config.source.MapConfigSource;
import org.pragmatica.aether.config.source.SystemPropertyConfigSource;
import org.pragmatica.aether.config.source.TomlConfigSource;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Layered configuration provider that merges multiple sources.
///
/// Sources are merged in priority order (higher priority wins).
/// Nested maps are deep-merged, other values are replaced.
///
/// Typical layering (lowest to highest priority):
/// <ol>
///   - Default values (built-in)
///   - TOML file (aether.toml)
///   - Environment variables (AETHER_*)
///   - System properties (-Daether.*)
/// </ol>
///
/// Example usage:
/// ```{@code
/// var config = ConfigurationProvider.builder()
///     .withDefaults(Map.of("server.port", "8080"))
///     .withTomlFile(Path.of("aether.toml"))
///     .withEnvironment("AETHER_")
///     .withSystemProperties("aether.")
///     .build();
///
/// config.getString("server.port")  // Returns merged value
/// }```
public interface ConfigurationProvider extends ConfigSource {
    /// Get all registered sources in priority order (highest first).
    ///
    /// @return List of configuration sources
    List<ConfigSource> sources();

    /// Create a new builder for ConfigurationProvider.
    ///
    /// @return Builder instance
    static Builder builder() {
        return new Builder();
    }

    /// Create a minimal configuration provider from a single source.
    ///
    /// @param source The configuration source
    /// @return ConfigurationProvider wrapping the source
    static ConfigurationProvider configurationProvider(ConfigSource source) {
        return builder().withSource(source)
                      .build();
    }

    /// Resolve ${secrets:path} placeholders in all config values.
    ///
    /// Scans all merged values for ${secrets:path} patterns and resolves them
    /// using the provided resolver function. Resolution is eager (at call time).
    ///
    /// @param provider       The configuration provider with unresolved values
    /// @param secretResolver Function that resolves secret paths to values
    /// @return New ConfigurationProvider with all secrets resolved, or failure
    static Result<ConfigurationProvider> withSecretResolution(ConfigurationProvider provider,
                                                              Fn1<Promise<String>, String> secretResolver) {
        return SecretResolvingConfigurationProvider.resolve(provider, secretResolver);
    }

    /// Builder for creating layered ConfigurationProvider instances.
    final class Builder {
        private final List<ConfigSource> sources = new ArrayList<>();

        private Builder() {}

        /// Add a configuration source.
        ///
        /// @param source The source to add
        /// @return This builder
        public Builder withSource(ConfigSource source) {
            sources.add(source);
            return this;
        }

        /// Add default values (lowest priority: -1000).
        ///
        /// @param defaults Map of default key-value pairs
        /// @return This builder
        public Builder withDefaults(Map<String, String> defaults) {
            return withSource(MapConfigSource.mapConfigSource("defaults", defaults, - 1000));
        }

        /// Add a TOML file source (priority: 0).
        ///
        /// @param path Path to the TOML file
        /// @return This builder
        public Builder withTomlFile(Path path) {
            TomlConfigSource.tomlConfigSource(path)
                            .onSuccess(this::withSource);
            return this;
        }

        /// Add a TOML file source, returning error if file cannot be loaded.
        ///
        /// @param path Path to the TOML file
        /// @return Result containing this builder or error
        public Result<Builder> withRequiredTomlFile(Path path) {
            return TomlConfigSource.tomlConfigSource(path)
                                   .map(source -> {
                                       sources.add(source);
                                       return this;
                                   });
        }

        /// Add environment variables with prefix (priority: 100).
        ///
        /// Variables are matched by prefix and converted from SCREAMING_SNAKE_CASE
        /// to dot.notation (e.g., AETHER_DATABASE_HOST -> database.host).
        ///
        /// @param prefix Prefix to filter environment variables (e.g., "AETHER_")
        /// @return This builder
        public Builder withEnvironment(String prefix) {
            return withSource(EnvironmentConfigSource.environmentConfigSource(prefix));
        }

        /// Add system properties with prefix (priority: 200).
        ///
        /// Properties are matched by prefix and the prefix is stripped
        /// (e.g., -Daether.database.host -> database.host).
        ///
        /// @param prefix Prefix to filter system properties (e.g., "aether.")
        /// @return This builder
        public Builder withSystemProperties(String prefix) {
            return withSource(SystemPropertyConfigSource.systemPropertyConfigSource(prefix));
        }

        /// Build the ConfigurationProvider.
        ///
        /// @return Configured provider with all sources merged
        public ConfigurationProvider build() {
            var sortedSources = sources.stream()
                                       .sorted(Comparator.comparingInt(ConfigSource::priority)
                                                         .reversed())
                                       .toList();
            var mergedMap = DeepMerger.mergeSources(sortedSources);
            return new LayeredConfigurationProvider(sortedSources, mergedMap);
        }
    }
}

/// Implementation of ConfigurationProvider with layered sources.
final class LayeredConfigurationProvider implements ConfigurationProvider {
    private final List<ConfigSource> sources;
    private final Map<String, String> mergedValues;

    LayeredConfigurationProvider(List<ConfigSource> sources, Map<String, String> mergedValues) {
        this.sources = List.copyOf(sources);
        this.mergedValues = Map.copyOf(mergedValues);
    }

    @Override
    public List<ConfigSource> sources() {
        return sources;
    }

    @Override
    public Option<String> getString(String key) {
        return Option.option(mergedValues.get(key));
    }

    @Override
    public Set<String> keys() {
        return mergedValues.keySet();
    }

    @Override
    public Map<String, String> asMap() {
        return new LinkedHashMap<>(mergedValues);
    }

    @Override
    public String name() {
        return "LayeredConfigurationProvider[" + sources.size() + " sources]";
    }

    @Override
    public Result<ConfigSource> reload() {
        var reloadedSources = new ArrayList<ConfigSource>();
        for (var source : sources) {
            var reloaded = source.reload();
            if (reloaded.isFailure()) {
                return reloaded;
            }
            reloadedSources.add(reloaded.unwrap());
        }
        var newMerged = DeepMerger.mergeSources(reloadedSources);
        return Result.success(new LayeredConfigurationProvider(reloadedSources, newMerged));
    }
}
