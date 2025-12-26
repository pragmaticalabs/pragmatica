package org.pragmatica.aether.demo.simulator;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Configuration for the simulator.
 * <p>
 * Supports per-entry-point rate configuration and slice settings.
 * Can be loaded from JSON file or constructed programmatically.
 */
public record SimulatorConfig(
    Map<String, EntryPointConfig> entryPoints,
    Map<String, SliceConfig> slices,
    boolean loadGeneratorEnabled,
    double globalRateMultiplier
) {
    private static final Logger log = LoggerFactory.getLogger(SimulatorConfig.class);

    /**
     * Compact constructor with validation.
     */
    public SimulatorConfig {
        if (entryPoints == null) {
            throw new IllegalArgumentException("entryPoints cannot be null");
        }
        if (slices == null) {
            throw new IllegalArgumentException("slices cannot be null");
        }
        if (globalRateMultiplier < 0 || !Double.isFinite(globalRateMultiplier)) {
            throw new IllegalArgumentException("globalRateMultiplier must be >= 0 and finite, got: " + globalRateMultiplier);
        }
    }

    /**
     * Configuration for a single entry point.
     */
    public record EntryPointConfig(
        int callsPerSecond,
        boolean enabled,
        List<String> products,
        List<String> customerIds
    ) {
        public static EntryPointConfig defaultConfig() {
            return new EntryPointConfig(0, true, List.of(), List.of());
        }

        public static EntryPointConfig withRate(int callsPerSecond) {
            return new EntryPointConfig(callsPerSecond, true, List.of(), List.of());
        }

        public String toJson() {
            var productsJson = products.isEmpty() ? "[]" :
                "[" + String.join(",", products.stream().map(p -> "\"" + p + "\"").toList()) + "]";
            var customerIdsJson = customerIds.isEmpty() ? "[]" :
                "[" + String.join(",", customerIds.stream().map(c -> "\"" + c + "\"").toList()) + "]";
            return String.format(
                "{\"callsPerSecond\":%d,\"enabled\":%b,\"products\":%s,\"customerIds\":%s}",
                callsPerSecond, enabled, productsJson, customerIdsJson
            );
        }
    }

    /**
     * Configuration for a slice.
     */
    public record SliceConfig(
        String stockMode,  // "infinite" or "realistic"
        int refillRate,
        int baseLatencyMs,
        int jitterMs,
        double failureRate
    ) {
        public static SliceConfig defaultConfig() {
            return new SliceConfig("infinite", 0, 0, 0, 0.0);
        }

        public String toJson() {
            return String.format(
                "{\"stockMode\":\"%s\",\"refillRate\":%d,\"baseLatencyMs\":%d,\"jitterMs\":%d,\"failureRate\":%.4f}",
                stockMode, refillRate, baseLatencyMs, jitterMs, failureRate
            );
        }
    }

    /**
     * Create default configuration.
     */
    public static SimulatorConfig defaultConfig() {
        var entryPoints = new HashMap<String, EntryPointConfig>();
        entryPoints.put("placeOrder", new EntryPointConfig(
            500, true,
            List.of("PROD-ABC123", "PROD-DEF456", "PROD-GHI789"),
            List.of()
        ));
        entryPoints.put("getOrderStatus", EntryPointConfig.withRate(0));
        entryPoints.put("cancelOrder", EntryPointConfig.withRate(0));
        entryPoints.put("checkStock", EntryPointConfig.withRate(0));
        entryPoints.put("getPrice", EntryPointConfig.withRate(0));

        var slices = new HashMap<String, SliceConfig>();
        slices.put("inventory-service", SliceConfig.defaultConfig());
        slices.put("pricing-service", SliceConfig.defaultConfig());

        return new SimulatorConfig(entryPoints, slices, true, 1.0);
    }

    /**
     * Get entry point config, returning default if not found.
     */
    public EntryPointConfig entryPointConfig(String name) {
        return entryPoints.getOrDefault(name, EntryPointConfig.defaultConfig());
    }

    /**
     * Get slice config, returning default if not found.
     */
    public SliceConfig sliceConfig(String name) {
        return slices.getOrDefault(name, SliceConfig.defaultConfig());
    }

    /**
     * Get effective rate for an entry point (applies global multiplier).
     */
    public int effectiveRate(String entryPoint) {
        var config = entryPointConfig(entryPoint);
        if (!config.enabled()) return 0;
        return (int) (config.callsPerSecond() * globalRateMultiplier);
    }

    /**
     * Create a new config with updated entry point rate.
     */
    public SimulatorConfig withEntryPointRate(String entryPoint, int rate) {
        var newEntryPoints = new HashMap<>(entryPoints);
        var existing = entryPointConfig(entryPoint);
        newEntryPoints.put(entryPoint, new EntryPointConfig(
            rate, existing.enabled(), existing.products(), existing.customerIds()
        ));
        return new SimulatorConfig(newEntryPoints, slices, loadGeneratorEnabled, globalRateMultiplier);
    }

    /**
     * Create a new config with updated global rate multiplier.
     */
    public SimulatorConfig withGlobalMultiplier(double multiplier) {
        return new SimulatorConfig(entryPoints, slices, loadGeneratorEnabled, multiplier);
    }

    /**
     * Create a new config with load generator enabled/disabled.
     */
    public SimulatorConfig withLoadGeneratorEnabled(boolean enabled) {
        return new SimulatorConfig(entryPoints, slices, enabled, globalRateMultiplier);
    }

    /**
     * Serialize to JSON.
     */
    public String toJson() {
        var sb = new StringBuilder();
        sb.append("{\"entryPoints\":{");

        var first = true;
        for (var entry : entryPoints.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(entry.getKey()).append("\":").append(entry.getValue().toJson());
        }

        sb.append("},\"slices\":{");

        first = true;
        for (var entry : slices.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(entry.getKey()).append("\":").append(entry.getValue().toJson());
        }

        sb.append("},\"loadGeneratorEnabled\":").append(loadGeneratorEnabled);
        sb.append(",\"globalRateMultiplier\":").append(globalRateMultiplier);
        sb.append("}");

        return sb.toString();
    }

    /**
     * Load configuration from file.
     */
    public static Result<SimulatorConfig> loadFromFile(Path path) {
        return Result.lift(
            Causes.forOneValue("Failed to load config from " + path),
            () -> parseJson(Files.readString(path))
        );
    }

    /**
     * Try to load from file, return default if file doesn't exist.
     */
    public static SimulatorConfig loadOrDefault(Path path) {
        if (!Files.exists(path)) {
            log.info("Config file not found at {}, using defaults", path);
            return defaultConfig();
        }

        return loadFromFile(path)
            .onFailure(cause -> log.warn("Failed to load config: {}, using defaults", cause.message()))
            .fold(_ -> defaultConfig(), config -> config);
    }

    /**
     * Parse JSON configuration (simple parser, no external dependencies).
     */
    public static SimulatorConfig parseJson(String json) {
        var config = defaultConfig();
        var entryPoints = new HashMap<>(config.entryPoints());
        var slices = new HashMap<>(config.slices());

        // Parse entry point rates using regex (simple approach)
        var entryPointPattern = Pattern.compile("\"(\\w+)\"\\s*:\\s*\\{[^}]*\"callsPerSecond\"\\s*:\\s*(\\d+)");
        var matcher = entryPointPattern.matcher(json);

        while (matcher.find()) {
            var name = matcher.group(1);
            var rate = Integer.parseInt(matcher.group(2));
            if (entryPoints.containsKey(name)) {
                var existing = entryPoints.get(name);
                entryPoints.put(name, new EntryPointConfig(
                    rate, existing.enabled(), existing.products(), existing.customerIds()
                ));
            }
        }

        // Parse global settings
        var multiplierPattern = Pattern.compile("\"globalRateMultiplier\"\\s*:\\s*([\\d.]+)");
        var multiplierMatcher = multiplierPattern.matcher(json);
        double multiplier = config.globalRateMultiplier();
        if (multiplierMatcher.find()) {
            multiplier = Double.parseDouble(multiplierMatcher.group(1));
        }

        var enabledPattern = Pattern.compile("\"loadGeneratorEnabled\"\\s*:\\s*(true|false)");
        var enabledMatcher = enabledPattern.matcher(json);
        boolean enabled = config.loadGeneratorEnabled();
        if (enabledMatcher.find()) {
            enabled = Boolean.parseBoolean(enabledMatcher.group(1));
        }

        return new SimulatorConfig(entryPoints, slices, enabled, multiplier);
    }

    /**
     * Save configuration to file.
     */
    public Result<Void> saveToFile(Path path) {
        return Result.lift(
            Causes.forOneValue("Failed to save config to " + path),
            () -> {
                Files.writeString(path, toJson());
                return null;
            }
        );
    }
}
