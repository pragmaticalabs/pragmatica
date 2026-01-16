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
    public record BlueprintConfig(int instances,
                                   Option<Integer> timeoutMs,
                                   Option<Integer> memoryMb,
                                   Option<String> loadBalancing,
                                   Option<String> affinityKey) {

        public static BlueprintConfig blueprintConfig(int instances,
                                                       Option<Integer> timeoutMs,
                                                       Option<Integer> memoryMb,
                                                       Option<String> loadBalancing,
                                                       Option<String> affinityKey) {
            return new BlueprintConfig(instances, timeoutMs, memoryMb, loadBalancing, affinityKey);
        }

        public static BlueprintConfig defaults() {
            return blueprintConfig(1, Option.empty(), Option.empty(), Option.empty(), Option.empty());
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
                            .or(() -> 1);
        var timeoutMs = toml.getInt("blueprint", "timeout_ms");
        var memoryMb = toml.getInt("blueprint", "memory_mb");
        var loadBalancing = toml.getString("blueprint", "load_balancing");
        var affinityKey = toml.getString("blueprint", "affinity_key");

        var blueprint = BlueprintConfig.blueprintConfig(instances,
                                                        timeoutMs,
                                                        memoryMb,
                                                        loadBalancing,
                                                        affinityKey);

        return new SliceConfig(blueprint);
    }
}
