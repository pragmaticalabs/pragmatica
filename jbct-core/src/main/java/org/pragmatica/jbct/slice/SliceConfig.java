package org.pragmatica.jbct.slice;

import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.nio.file.Path;

/**
 * Slice configuration loaded from {SliceName}.toml files.
 * Located in src/main/resources/slices/
 */
public record SliceConfig(BlueprintConfig blueprint) {
    /**
     * Blueprint-related configuration.
     */
    public record BlueprintConfig(int instances) {
        public static BlueprintConfig blueprintConfig(int instances) {
            return new BlueprintConfig(instances);
        }

        public static BlueprintConfig defaults() {
            return blueprintConfig(3);
        }
    }

    /**
     * Default configuration when no config file exists.
     */
    public static SliceConfig defaults() {
        return new SliceConfig(BlueprintConfig.defaults());
    }

    /**
     * Load slice config from a TOML file.
     */
    public static Result<SliceConfig> load(Path configPath) {
        return TomlParser.parseFile(configPath)
                         .map(SliceConfig::fromTomlDocument);
    }

    private static SliceConfig fromTomlDocument(org.pragmatica.config.toml.TomlDocument toml) {
        var instances = toml.getInt("blueprint", "instances")
                            .or(3);
        var blueprint = BlueprintConfig.blueprintConfig(instances);
        return new SliceConfig(blueprint);
    }
}
