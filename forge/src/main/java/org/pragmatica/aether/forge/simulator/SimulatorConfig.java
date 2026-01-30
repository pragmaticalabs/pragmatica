package org.pragmatica.aether.forge.simulator;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;

/**
 * Configuration for the simulator.
 * <p>
 * Supports per-entry-point rate configuration and slice settings.
 * Can be loaded from JSON file or constructed programmatically.
 */
public record SimulatorConfig(Map<String, EntryPointConfig> entryPoints,
                              Map<String, SliceConfig> slices,
                              boolean loadGeneratorEnabled,
                              double globalRateMultiplier) {
    private static final Logger log = LoggerFactory.getLogger(SimulatorConfig.class);

    private static final Cause ENTRY_POINTS_NULL = Causes.cause("entryPoints cannot be null");
    private static final Cause SLICES_NULL = Causes.cause("slices cannot be null");
    private static final Cause INVALID_MULTIPLIER = Causes.cause("globalRateMultiplier must be >= 0 and finite");

    /**
     * Configuration for a single entry point.
     */
    public record EntryPointConfig(int callsPerSecond,
                                   boolean enabled,
                                   List<String> products,
                                   List<String> customerIds,
                                   int minQuantity,
                                   int maxQuantity) {
        private static final List<String> DEFAULT_PRODUCTS = List.of("PROD-ABC123", "PROD-DEF456", "PROD-GHI789");

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
                case "placeOrder" -> new DataGenerator.OrderRequestGenerator(productGen,
                                                                             DataGenerator.CustomerIdGenerator.withDefaults(),
                                                                             DataGenerator.IntRange.intRange(minQuantity,
                                                                                                             maxQuantity)
                                                                                          .unwrap());
                case "getOrderStatus", "cancelOrder" -> DataGenerator.OrderIdGenerator.withSharedPool();
                case "checkStock" -> new DataGenerator.StockCheckGenerator(productGen);
                case "getPrice" -> new DataGenerator.PriceCheckGenerator(productGen);
                default -> productGen;
            };
        }

        public String toJson() {
            var productsJson = formatStringList(products);
            var customerIdsJson = formatStringList(customerIds);
            return String.format("{\"callsPerSecond\":%d,\"enabled\":%b,\"products\":%s,\"customerIds\":%s,\"minQuantity\":%d,\"maxQuantity\":%d}",
                                 callsPerSecond,
                                 enabled,
                                 productsJson,
                                 customerIdsJson,
                                 minQuantity,
                                 maxQuantity);
        }

        private static String formatStringList(List<String> list) {
            if (list.isEmpty()) {
                return "[]";
            }
            return "[" + String.join(",",
                                     list.stream()
                                         .map(s -> "\"" + s + "\"")
                                         .toList()) + "]";
        }
    }

    /**
     * Configuration for a slice.
     */
    public record SliceConfig(String stockMode,
                              int refillRate,
                              int baseLatencyMs,
                              int jitterMs,
                              double failureRate,
                              double spikeChance,
                              int spikeLatencyMs) {
        private static final Cause INVALID_STOCK_MODE = Causes.cause("stockMode must be 'infinite' or 'realistic'");
        private static final Cause BASE_LATENCY_NEGATIVE = Causes.cause("baseLatencyMs must be >= 0");
        private static final Cause JITTER_NEGATIVE = Causes.cause("jitterMs must be >= 0");
        private static final Cause FAILURE_RATE_OUT_OF_RANGE = Causes.cause("failureRate must be between 0 and 1");
        private static final Cause SPIKE_CHANCE_OUT_OF_RANGE = Causes.cause("spikeChance must be between 0 and 1");
        private static final Cause SPIKE_LATENCY_NEGATIVE = Causes.cause("spikeLatencyMs must be >= 0");

        public static Result<SliceConfig> sliceConfig(String stockMode,
                                                      int refillRate,
                                                      int baseLatencyMs,
                                                      int jitterMs,
                                                      double failureRate,
                                                      double spikeChance,
                                                      int spikeLatencyMs) {
            if (stockMode == null || (!stockMode.equals("infinite") && !stockMode.equals("realistic"))) {
                return INVALID_STOCK_MODE.result();
            }
            if (baseLatencyMs < 0) {
                return BASE_LATENCY_NEGATIVE.result();
            }
            if (jitterMs < 0) {
                return JITTER_NEGATIVE.result();
            }
            if (failureRate < 0 || failureRate > 1) {
                return FAILURE_RATE_OUT_OF_RANGE.result();
            }
            if (spikeChance < 0 || spikeChance > 1) {
                return SPIKE_CHANCE_OUT_OF_RANGE.result();
            }
            if (spikeLatencyMs < 0) {
                return SPIKE_LATENCY_NEGATIVE.result();
            }
            return Result.success(new SliceConfig(stockMode,
                                                  refillRate,
                                                  baseLatencyMs,
                                                  jitterMs,
                                                  failureRate,
                                                  spikeChance,
                                                  spikeLatencyMs));
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
                return buildCompositeSimulation();
            }
            if (hasLatency) {
                return new BackendSimulation.LatencySimulation(baseLatencyMs, jitterMs, spikeChance, spikeLatencyMs);
            }
            return buildFailureSimulation();
        }

        private BackendSimulation buildCompositeSimulation() {
            var latency = new BackendSimulation.LatencySimulation(baseLatencyMs, jitterMs, spikeChance, spikeLatencyMs);
            var failure = buildFailureSimulation();
            return BackendSimulation.Composite.composite(latency, failure)
                                    .unwrap();
        }

        private BackendSimulation buildFailureSimulation() {
            return BackendSimulation.FailureInjection.withRate(failureRate,
                                                               new BackendSimulation.SimulatedError.ServiceUnavailable("backend"),
                                                               new BackendSimulation.SimulatedError.Timeout("operation",
                                                                                                            5000))
                                    .unwrap();
        }

        public String toJson() {
            return String.format("{\"stockMode\":\"%s\",\"refillRate\":%d,\"baseLatencyMs\":%d,\"jitterMs\":%d,\"failureRate\":%.4f,\"spikeChance\":%.4f,\"spikeLatencyMs\":%d}",
                                 stockMode,
                                 refillRate,
                                 baseLatencyMs,
                                 jitterMs,
                                 failureRate,
                                 spikeChance,
                                 spikeLatencyMs);
        }
    }

    public static Result<SimulatorConfig> simulatorConfig(Map<String, EntryPointConfig> entryPoints,
                                                          Map<String, SliceConfig> slices,
                                                          boolean loadGeneratorEnabled,
                                                          double globalRateMultiplier) {
        if (entryPoints == null) {
            return ENTRY_POINTS_NULL.result();
        }
        if (slices == null) {
            return SLICES_NULL.result();
        }
        if (globalRateMultiplier < 0 || !Double.isFinite(globalRateMultiplier)) {
            return INVALID_MULTIPLIER.result();
        }
        return Result.success(new SimulatorConfig(entryPoints, slices, loadGeneratorEnabled, globalRateMultiplier));
    }

    /**
     * Create default configuration.
     */
    public static SimulatorConfig defaultConfig() {
        var entryPoints = new HashMap<String, EntryPointConfig>();
        entryPoints.put("placeOrder", EntryPointConfig.withRate(0));
        entryPoints.put("getOrderStatus", EntryPointConfig.withRate(0));
        entryPoints.put("cancelOrder", EntryPointConfig.withRate(0));
        entryPoints.put("checkStock", EntryPointConfig.withRate(0));
        entryPoints.put("getPrice", EntryPointConfig.withRate(0));
        var slices = new HashMap<String, SliceConfig>();
        slices.put("inventory-service", SliceConfig.defaultConfig());
        slices.put("pricing-service", SliceConfig.defaultConfig());
        return new SimulatorConfig(entryPoints, slices, false, 1.0);
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
                           new EntryPointConfig(rate,
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
        appendMapEntries(sb, entryPoints, EntryPointConfig::toJson);
        sb.append("},\"slices\":{");
        appendMapEntries(sb, slices, SliceConfig::toJson);
        sb.append("},\"loadGeneratorEnabled\":")
          .append(loadGeneratorEnabled);
        sb.append(",\"globalRateMultiplier\":")
          .append(globalRateMultiplier);
        sb.append("}");
        return sb.toString();
    }

    private static <T> void appendMapEntries(StringBuilder sb,
                                             Map<String, T> map,
                                             java.util.function.Function<T, String> toJson) {
        var first = true;
        for (var entry : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"")
              .append(entry.getKey())
              .append("\":")
              .append(toJson.apply(entry.getValue()));
        }
    }

    /**
     * Load configuration from file.
     */
    public static Result<SimulatorConfig> loadFromFile(Path path) {
        return Result.lift(Causes.forOneValue("Failed to load config from " + path),
                           () -> parseJson(Files.readString(path)));
    }

    /**
     * Try to load from file, return default if file doesn't exist.
     */
    public static SimulatorConfig loadOrDefault(Path path) {
        if (!Files.exists(path)) {
            log.info("Config file not found at {}, using defaults", path);
            return defaultConfig();
        }
        return loadFromFile(path).onFailure(cause -> log.warn("Failed to load config: {}, using defaults",
                                                              cause.message()))
                           .or(defaultConfig());
    }

    /**
     * Parse JSON configuration (simple parser, no external dependencies).
     */
    public static SimulatorConfig parseJson(String json) {
        var config = defaultConfig();
        var entryPoints = new HashMap<>(config.entryPoints());
        var slices = new HashMap<>(config.slices());
        parseEntryPointRates(json, entryPoints);
        var multiplier = parseDoubleField(json, "globalRateMultiplier", config.globalRateMultiplier());
        var enabled = parseBooleanField(json, "loadGeneratorEnabled", config.loadGeneratorEnabled());
        return new SimulatorConfig(entryPoints, slices, enabled, multiplier);
    }

    private static void parseEntryPointRates(String json, Map<String, EntryPointConfig> entryPoints) {
        var entryPointPattern = Pattern.compile("\"(\\w+)\"\\s*:\\s*\\{[^}]*\"callsPerSecond\"\\s*:\\s*(\\d+)");
        var matcher = entryPointPattern.matcher(json);
        while (matcher.find()) {
            var name = matcher.group(1);
            var rate = Integer.parseInt(matcher.group(2));
            if (entryPoints.containsKey(name)) {
                var existing = entryPoints.get(name);
                entryPoints.put(name,
                                new EntryPointConfig(rate,
                                                     existing.enabled(),
                                                     existing.products(),
                                                     existing.customerIds(),
                                                     existing.minQuantity(),
                                                     existing.maxQuantity()));
            }
        }
    }

    private static double parseDoubleField(String json, String fieldName, double defaultValue) {
        var pattern = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*([\\d.]+)");
        var matcher = pattern.matcher(json);
        return matcher.find()
               ? Double.parseDouble(matcher.group(1))
               : defaultValue;
    }

    private static boolean parseBooleanField(String json, String fieldName, boolean defaultValue) {
        var pattern = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*(true|false)");
        var matcher = pattern.matcher(json);
        return matcher.find()
               ? Boolean.parseBoolean(matcher.group(1))
               : defaultValue;
    }

    /**
     * Save configuration to file.
     */
    public Result<Unit> saveToFile(Path path) {
        return Result.lift(Causes.forOneValue("Failed to save config to " + path),
                           () -> {
                               Files.writeString(path, toJson());
                               return unit();
                           });
    }
}
