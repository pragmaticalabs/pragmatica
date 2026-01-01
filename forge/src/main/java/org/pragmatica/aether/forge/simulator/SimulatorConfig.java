package org.pragmatica.aether.forge.simulator;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 double globalRateMultiplier) {
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
    List<String> customerIds,
    int minQuantity,
    int maxQuantity) {
        private static final List<String>DEFAULT_PRODUCTS = List.of("PROD-ABC123", "PROD-DEF456", "PROD-GHI789");

        public static EntryPointConfig defaultConfig() {
            return new EntryPointConfig(0, true, List.of(), List.of(), 1, 5);
        }

        public static EntryPointConfig withRate(int callsPerSecond) {
            return new EntryPointConfig(callsPerSecond, true, List.of(), List.of(), 1, 5);
        }

        /**
         * Get products list, using defaults if empty.
         */
        public List<String> effectiveProducts() {
            return products.isEmpty()
                   ? DEFAULT_PRODUCTS
                   : products;
        }

        /**
         * Build a DataGenerator for this entry point type.
         */
        public DataGenerator buildGenerator(String entryPointName) {
            var productGen = new DataGenerator.ProductIdGenerator(effectiveProducts());
            return switch (entryPointName) {
                case"placeOrder" -> new DataGenerator.OrderRequestGenerator(
                productGen,
                DataGenerator.CustomerIdGenerator.withDefaults(),
                DataGenerator.IntRange.of(minQuantity, maxQuantity));
                case"getOrderStatus", "cancelOrder" -> DataGenerator.OrderIdGenerator.withSharedPool();
                case"checkStock" -> new DataGenerator.StockCheckGenerator(productGen);
                case"getPrice" -> new DataGenerator.PriceCheckGenerator(productGen);
                default -> productGen;
            };
        }

        public String toJson() {
            var productsJson = products.isEmpty()
                               ? "[]"
                               : "[" + String.join(",",
                                                   products.stream()
                                                           .map(p -> "\"" + p + "\"")
                                                           .toList()) + "]";
            var customerIdsJson = customerIds.isEmpty()
                                  ? "[]"
                                  : "[" + String.join(",",
                                                      customerIds.stream()
                                                                 .map(c -> "\"" + c + "\"")
                                                                 .toList()) + "]";
            return String.format(
            "{\"callsPerSecond\":%d,\"enabled\":%b,\"products\":%s,\"customerIds\":%s,\"minQuantity\":%d,\"maxQuantity\":%d}",
            callsPerSecond,
            enabled,
            productsJson,
            customerIdsJson,
            minQuantity,
            maxQuantity);
        }
    }

    /**
     * Configuration for a slice.
     */
    public record SliceConfig(
    String stockMode,
    // "infinite" or "realistic"
    int refillRate,
    int baseLatencyMs,
    int jitterMs,
    double failureRate,
    double spikeChance,
    int spikeLatencyMs) {
        public SliceConfig {
            if (stockMode == null || (!stockMode.equals("infinite") && !stockMode.equals("realistic"))) {
                throw new IllegalArgumentException("stockMode must be 'infinite' or 'realistic'");
            }
            if (baseLatencyMs < 0) {
                throw new IllegalArgumentException("baseLatencyMs must be >= 0");
            }
            if (jitterMs < 0) {
                throw new IllegalArgumentException("jitterMs must be >= 0");
            }
            if (failureRate < 0 || failureRate > 1) {
                throw new IllegalArgumentException("failureRate must be between 0 and 1");
            }
            if (spikeChance < 0 || spikeChance > 1) {
                throw new IllegalArgumentException("spikeChance must be between 0 and 1");
            }
            if (spikeLatencyMs < 0) {
                throw new IllegalArgumentException("spikeLatencyMs must be >= 0");
            }
        }

        public static SliceConfig defaultConfig() {
            return new SliceConfig("infinite", 0, 0, 0, 0.0, 0.0, 0);
        }

        /**
         * Build a BackendSimulation from this config.
         */
        public BackendSimulation buildSimulation() {
            var hasLatency = baseLatencyMs > 0 || jitterMs > 0;
            var hasFailure = failureRate > 0;
            if (!hasLatency && !hasFailure) {
                return BackendSimulation.NoOp.INSTANCE;
            }
            if (hasLatency && hasFailure) {
                return BackendSimulation.Composite.of(
                new BackendSimulation.LatencySimulation(baseLatencyMs, jitterMs, spikeChance, spikeLatencyMs),
                BackendSimulation.FailureInjection.withRate(
                failureRate,
                new BackendSimulation.SimulatedError.ServiceUnavailable("backend"),
                new BackendSimulation.SimulatedError.Timeout("operation", 5000)));
            }
            if (hasLatency) {
                return new BackendSimulation.LatencySimulation(baseLatencyMs, jitterMs, spikeChance, spikeLatencyMs);
            }
            return BackendSimulation.FailureInjection.withRate(
            failureRate,
            new BackendSimulation.SimulatedError.ServiceUnavailable("backend"),
            new BackendSimulation.SimulatedError.Timeout("operation", 5000));
        }

        public String toJson() {
            return String.format(
            "{\"stockMode\":\"%s\",\"refillRate\":%d,\"baseLatencyMs\":%d,\"jitterMs\":%d,\"failureRate\":%.4f,\"spikeChance\":%.4f,\"spikeLatencyMs\":%d}",
            stockMode,
            refillRate,
            baseLatencyMs,
            jitterMs,
            failureRate,
            spikeChance,
            spikeLatencyMs);
        }
    }

    /**
     * Create default configuration.
     */
    public static SimulatorConfig defaultConfig() {
        var entryPoints = new HashMap<String, EntryPointConfig>();
        entryPoints.put("placeOrder",
                        new EntryPointConfig(
        500, true, List.of("PROD-ABC123", "PROD-DEF456", "PROD-GHI789"), List.of(), 1, 5));
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
        return (int)(config.callsPerSecond() * globalRateMultiplier);
    }

    /**
     * Create a new config with updated entry point rate.
     */
    public SimulatorConfig withEntryPointRate(String entryPoint, int rate) {
        var newEntryPoints = new HashMap<>(entryPoints);
        var existing = entryPointConfig(entryPoint);
        newEntryPoints.put(entryPoint,
                           new EntryPointConfig(
        rate,
        existing.enabled(),
        existing.products(),
        existing.customerIds(),
        existing.minQuantity(),
        existing.maxQuantity()));
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
            sb.append("\"")
              .append(entry.getKey())
              .append("\":")
              .append(entry.getValue()
                           .toJson());
        }
        sb.append("},\"slices\":{");
        first = true;
        for (var entry : slices.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"")
              .append(entry.getKey())
              .append("\":")
              .append(entry.getValue()
                           .toJson());
        }
        sb.append("},\"loadGeneratorEnabled\":")
          .append(loadGeneratorEnabled);
        sb.append(",\"globalRateMultiplier\":")
          .append(globalRateMultiplier);
        sb.append("}");
        return sb.toString();
    }

    /**
     * Load configuration from file.
     */
    public static Result<SimulatorConfig> loadFromFile(Path path) {
        return Result.lift(
        Causes.forOneValue("Failed to load config from " + path), () -> parseJson(Files.readString(path)));
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
               .onFailure(cause -> log.warn("Failed to load config: {}, using defaults",
                                            cause.message()))
               .fold(_ -> defaultConfig(),
                     config -> config);
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
                entryPoints.put(name,
                                new EntryPointConfig(
                rate,
                existing.enabled(),
                existing.products(),
                existing.customerIds(),
                existing.minQuantity(),
                existing.maxQuantity()));
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
        });
    }
}
