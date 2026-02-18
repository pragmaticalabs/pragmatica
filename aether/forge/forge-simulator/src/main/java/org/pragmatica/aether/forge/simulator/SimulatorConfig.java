package org.pragmatica.aether.forge.simulator;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.parse.Text;
import org.pragmatica.lang.utils.Causes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.cause;

/// Configuration for the simulator.
///
/// Supports per-entry-point rate configuration and slice settings.
/// Can be loaded from JSON file or constructed programmatically.
public record SimulatorConfig(Map<String, EntryPointConfig> entryPoints,
                              Map<String, SliceConfig> slices,
                              boolean loadGeneratorEnabled,
                              double globalRateMultiplier) {
    private static final Logger log = LoggerFactory.getLogger(SimulatorConfig.class);

    private static final Cause ENTRY_POINTS_NULL = cause("entryPoints cannot be null");
    private static final Cause SLICES_NULL = cause("slices cannot be null");
    private static final Cause INVALID_MULTIPLIER = cause("globalRateMultiplier must be >= 0 and finite");

    /// Configuration for a single entry point.
    public record EntryPointConfig(int callsPerSecond,
                                   boolean enabled,
                                   List<String> products,
                                   List<String> customerIds,
                                   int minQuantity,
                                   int maxQuantity) {
        private static final List<String> DEFAULT_PRODUCTS = List.of("PROD-ABC123", "PROD-DEF456", "PROD-GHI789");

        public static Result<EntryPointConfig> entryPointConfig(int callsPerSecond,
                                                                boolean enabled,
                                                                List<String> products,
                                                                List<String> customerIds,
                                                                int minQuantity,
                                                                int maxQuantity) {
            return success(new EntryPointConfig(callsPerSecond, enabled, products, customerIds, minQuantity, maxQuantity));
        }

        public static EntryPointConfig entryPointConfig() {
            return entryPointConfig(0, true, List.of(), List.of(), 1, 5).unwrap();
        }

        public static EntryPointConfig entryPointConfig(int callsPerSecond) {
            return entryPointConfig(callsPerSecond, true, List.of(), List.of(), 1, 5).unwrap();
        }

        /// Get products list, using defaults if empty.
        public List<String> effectiveProducts() {
            return option(products).filter(list -> !list.isEmpty())
                         .or(DEFAULT_PRODUCTS);
        }

        /// Build a DataGenerator for this entry point type.
        public DataGenerator buildGenerator(String entryPointName) {
            var productGen = buildProductGenerator();
            return switch (entryPointName) {
                case "placeOrder" -> buildOrderRequestGenerator(productGen);
                case "getOrderStatus", "cancelOrder" -> DataGenerator.OrderIdGenerator.orderIdGenerator();
                case "checkStock" -> buildStockCheckGenerator(productGen);
                case "getPrice" -> buildPriceCheckGenerator(productGen);
                default -> productGen;
            };
        }

        private DataGenerator.ProductIdGenerator buildProductGenerator() {
            return DataGenerator.ProductIdGenerator.productIdGenerator(effectiveProducts())
                                .unwrap();
        }

        private static DataGenerator buildStockCheckGenerator(DataGenerator.ProductIdGenerator productGen) {
            return DataGenerator.StockCheckGenerator.stockCheckGenerator(productGen)
                                .unwrap();
        }

        private static DataGenerator buildPriceCheckGenerator(DataGenerator.ProductIdGenerator productGen) {
            return DataGenerator.PriceCheckGenerator.priceCheckGenerator(productGen)
                                .unwrap();
        }

        private DataGenerator buildOrderRequestGenerator(DataGenerator.ProductIdGenerator productGen) {
            var customerGen = DataGenerator.CustomerIdGenerator.customerIdGenerator();
            var quantityRange = DataGenerator.IntRange.intRange(minQuantity, maxQuantity)
                                             .unwrap();
            return DataGenerator.OrderRequestGenerator.orderRequestGenerator(productGen, customerGen, quantityRange)
                                .unwrap();
        }

        public String toJson() {
            var productsJson = toJsonList(products);
            var customerIdsJson = toJsonList(customerIds);
            return formatEntryPointJson(callsPerSecond, enabled, productsJson, customerIdsJson, minQuantity, maxQuantity);
        }

        private static String formatEntryPointJson(int cps,
                                                   boolean en,
                                                   String prods,
                                                   String custs,
                                                   int minQ,
                                                   int maxQ) {
            return String.format("{\"callsPerSecond\":%d,\"enabled\":%b,\"products\":%s,\"customerIds\":%s,\"minQuantity\":%d,\"maxQuantity\":%d}",
                                 cps,
                                 en,
                                 prods,
                                 custs,
                                 minQ,
                                 maxQ);
        }

        private static String toJsonList(List<String> list) {
            return option(list).filter(l -> !l.isEmpty())
                         .map(SimulatorConfig.EntryPointConfig::nonEmptyListToJson)
                         .or("[]");
        }

        private static String nonEmptyListToJson(List<String> list) {
            return "[" + String.join(",",
                                     list.stream()
                                         .map(s -> "\"" + s + "\"")
                                         .toList()) + "]";
        }

        /// Create a copy with a new rate.
        public EntryPointConfig withRate(int rate) {
            return entryPointConfig(rate, enabled, products, customerIds, minQuantity, maxQuantity).unwrap();
        }
    }

    /// Configuration for a slice.
    public record SliceConfig(String stockMode,
                              int refillRate,
                              int baseLatencyMs,
                              int jitterMs,
                              double failureRate,
                              double spikeChance,
                              int spikeLatencyMs) {
        private static final Cause INVALID_STOCK_MODE = cause("stockMode must be 'infinite' or 'realistic'");
        private static final Cause BASE_LATENCY_NEGATIVE = cause("baseLatencyMs must be >= 0");
        private static final Cause JITTER_NEGATIVE = cause("jitterMs must be >= 0");
        private static final Cause FAILURE_RATE_OUT_OF_RANGE = cause("failureRate must be between 0 and 1");
        private static final Cause SPIKE_CHANCE_OUT_OF_RANGE = cause("spikeChance must be between 0 and 1");
        private static final Cause SPIKE_LATENCY_NEGATIVE = cause("spikeLatencyMs must be >= 0");

        public static Result<SliceConfig> sliceConfig(String stockMode,
                                                      int refillRate,
                                                      int baseLatencyMs,
                                                      int jitterMs,
                                                      double failureRate,
                                                      double spikeChance,
                                                      int spikeLatencyMs) {
            return ensureStockMode(stockMode).flatMap(_ -> validateLatencyParams(baseLatencyMs, jitterMs, spikeLatencyMs))
                                  .flatMap(_ -> validateRateParams(failureRate, spikeChance))
                                  .map(_ -> new SliceConfig(stockMode,
                                                            refillRate,
                                                            baseLatencyMs,
                                                            jitterMs,
                                                            failureRate,
                                                            spikeChance,
                                                            spikeLatencyMs));
        }

        private static Result<String> ensureStockMode(String stockMode) {
            return Verify.ensure(stockMode, Verify.Is::notNull, INVALID_STOCK_MODE)
                         .filter(INVALID_STOCK_MODE,
                                 m -> m.equals("infinite") || m.equals("realistic"));
        }

        private static Result<Integer> validateLatencyParams(int baseLatencyMs, int jitterMs, int spikeLatencyMs) {
            return Verify.ensure(baseLatencyMs, Verify.Is::nonNegative, BASE_LATENCY_NEGATIVE)
                         .flatMap(_ -> Verify.ensure(jitterMs, Verify.Is::nonNegative, JITTER_NEGATIVE))
                         .flatMap(_ -> Verify.ensure(spikeLatencyMs, Verify.Is::nonNegative, SPIKE_LATENCY_NEGATIVE));
        }

        private static Result<Double> validateRateParams(double failureRate, double spikeChance) {
            return Verify.ensure(failureRate, Verify.Is::between, 0.0, 1.0, FAILURE_RATE_OUT_OF_RANGE)
                         .flatMap(_ -> Verify.ensure(spikeChance,
                                                     Verify.Is::between,
                                                     0.0,
                                                     1.0,
                                                     SPIKE_CHANCE_OUT_OF_RANGE));
        }

        public static SliceConfig sliceConfig() {
            return sliceConfig("infinite", 0, 0, 0, 0.0, 0.0, 0).unwrap();
        }

        /// Build a BackendSimulation from this config.
        public BackendSimulation buildSimulation() {
            var hasLatency = baseLatencyMs > 0 || jitterMs > 0;
            var hasFailure = failureRate > 0;
            if (!hasLatency && !hasFailure) {
                return BackendSimulation.NoOp.noOp()
                                        .unwrap();
            }
            if (hasLatency && hasFailure) {
                return buildCompositeSimulation();
            }
            if (hasLatency) {
                return BackendSimulation.LatencySimulation.latencySimulation(baseLatencyMs, jitterMs);
            }
            return buildFailureSimulation();
        }

        private BackendSimulation buildCompositeSimulation() {
            var latency = BackendSimulation.LatencySimulation.latencySimulation(baseLatencyMs, jitterMs);
            var failure = buildFailureSimulation();
            return BackendSimulation.Composite.composite(latency, failure)
                                    .unwrap();
        }

        private BackendSimulation buildFailureSimulation() {
            var unavailable = BackendSimulation.SimulatedError.ServiceUnavailable.serviceUnavailable("backend")
                                               .unwrap();
            var timeout = BackendSimulation.SimulatedError.Timeout.timeout("operation", 5000)
                                           .unwrap();
            return BackendSimulation.FailureInjection.failureInjection(failureRate, unavailable, timeout)
                                    .unwrap();
        }

        public String toJson() {
            return formatSliceJson(stockMode,
                                   refillRate,
                                   baseLatencyMs,
                                   jitterMs,
                                   failureRate,
                                   spikeChance,
                                   spikeLatencyMs);
        }

        private static String formatSliceJson(String mode,
                                              int refill,
                                              int baseLat,
                                              int jit,
                                              double fail,
                                              double spike,
                                              int spikeLat) {
            return String.format("{\"stockMode\":\"%s\",\"refillRate\":%d,\"baseLatencyMs\":%d,\"jitterMs\":%d,\"failureRate\":%.4f,\"spikeChance\":%.4f,\"spikeLatencyMs\":%d}",
                                 mode,
                                 refill,
                                 baseLat,
                                 jit,
                                 fail,
                                 spike,
                                 spikeLat);
        }
    }

    public static Result<SimulatorConfig> simulatorConfig(Map<String, EntryPointConfig> entryPoints,
                                                          Map<String, SliceConfig> slices,
                                                          boolean loadGeneratorEnabled,
                                                          double globalRateMultiplier) {
        return ensureInputs(entryPoints, slices, globalRateMultiplier)
        .map(_ -> new SimulatorConfig(entryPoints, slices, loadGeneratorEnabled, globalRateMultiplier));
    }

    private static Result<Double> ensureInputs(Map<String, EntryPointConfig> entryPoints,
                                               Map<String, SliceConfig> slices,
                                               double globalRateMultiplier) {
        return ensureMapsNotNull(entryPoints, slices).flatMap(_ -> ensureFiniteMultiplier(globalRateMultiplier));
    }

    private static Result<Map<String, SliceConfig>> ensureMapsNotNull(Map<String, EntryPointConfig> entryPoints,
                                                                      Map<String, SliceConfig> slices) {
        return Verify.ensure(entryPoints, Verify.Is::notNull, ENTRY_POINTS_NULL)
                     .flatMap(_ -> Verify.ensure(slices, Verify.Is::notNull, SLICES_NULL));
    }

    private static Result<Double> ensureFiniteMultiplier(double globalRateMultiplier) {
        return Verify.ensure(globalRateMultiplier, Verify.Is::nonNegative, INVALID_MULTIPLIER)
                     .filter(INVALID_MULTIPLIER, Double::isFinite);
    }

    /// Create default configuration.
    public static SimulatorConfig simulatorConfig() {
        var entryPoints = buildDefaultEntryPoints();
        var slices = buildDefaultSlices();
        return simulatorConfig(entryPoints, slices, false, 1.0).unwrap();
    }

    private static HashMap<String, EntryPointConfig> buildDefaultEntryPoints() {
        var entryPoints = new HashMap<String, EntryPointConfig>();
        entryPoints.put("placeOrder", EntryPointConfig.entryPointConfig(0));
        entryPoints.put("getOrderStatus", EntryPointConfig.entryPointConfig(0));
        entryPoints.put("cancelOrder", EntryPointConfig.entryPointConfig(0));
        entryPoints.put("checkStock", EntryPointConfig.entryPointConfig(0));
        entryPoints.put("getPrice", EntryPointConfig.entryPointConfig(0));
        return entryPoints;
    }

    private static HashMap<String, SliceConfig> buildDefaultSlices() {
        var slices = new HashMap<String, SliceConfig>();
        slices.put("inventory-service", SliceConfig.sliceConfig());
        slices.put("pricing-service", SliceConfig.sliceConfig());
        return slices;
    }

    /// Get entry point config, returning default if not found.
    public EntryPointConfig entryPointConfig(String name) {
        return entryPoints.getOrDefault(name, EntryPointConfig.entryPointConfig());
    }

    /// Get slice config, returning default if not found.
    public SliceConfig sliceConfig(String name) {
        return slices.getOrDefault(name, SliceConfig.sliceConfig());
    }

    /// Get effective rate for an entry point (applies global multiplier).
    public int effectiveRate(String entryPoint) {
        var config = entryPointConfig(entryPoint);
        if (!config.enabled()) {
            return 0;
        }
        return (int)(config.callsPerSecond() * globalRateMultiplier);
    }

    /// Create a new config with updated entry point rate.
    public SimulatorConfig withEntryPointRate(String entryPoint, int rate) {
        var newEntryPoints = new HashMap<>(entryPoints);
        var existing = entryPointConfig(entryPoint);
        newEntryPoints.put(entryPoint, existing.withRate(rate));
        return simulatorConfig(newEntryPoints, slices, loadGeneratorEnabled, globalRateMultiplier).unwrap();
    }

    /// Create a new config with updated global rate multiplier.
    public SimulatorConfig withGlobalMultiplier(double multiplier) {
        return simulatorConfig(entryPoints, slices, loadGeneratorEnabled, multiplier).unwrap();
    }

    /// Create a new config with load generator enabled/disabled.
    public SimulatorConfig withLoadGeneratorEnabled(boolean enabled) {
        return simulatorConfig(entryPoints, slices, enabled, globalRateMultiplier).unwrap();
    }

    /// Serialize to JSON.
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
                                             Function<T, String> toJson) {
        var json = map.entrySet()
                      .stream()
                      .map(e -> entryJson(e, toJson))
                      .toList();
        sb.append(String.join(",", json));
    }

    private static <T> String entryJson(Map.Entry<String, T> entry, Function<T, String> toJson) {
        return "\"" + entry.getKey() + "\":" + toJson.apply(entry.getValue());
    }

    /// Load configuration from file, returning Result.
    public static Result<SimulatorConfig> simulatorConfig(Path path, Cause errorCause) {
        return Result.lift(errorCause, () -> simulatorConfig(Files.readString(path), true));
    }

    /// Load configuration from file, returning default if missing or invalid.
    public static SimulatorConfig simulatorConfig(Path path) {
        if (!Files.exists(path)) {
            log.info("Config file not found at {}, using defaults", path);
            return simulatorConfig();
        }
        var cause = cause("Failed to load config from " + path);
        return simulatorConfig(path, cause).onFailure(c -> log.warn("Failed to load config: {}, using defaults",
                                                                    c.message()))
                              .or(simulatorConfig());
    }

    /// Parse JSON configuration (simple parser, no external dependencies).
    public static SimulatorConfig simulatorConfig(String json, boolean fromJson) {
        var config = simulatorConfig();
        var parsedEntryPoints = new HashMap<>(config.entryPoints());
        var parsedSlices = new HashMap<>(config.slices());
        parseEntryPointRates(json, parsedEntryPoints);
        var multiplier = parseDoubleField(json, "globalRateMultiplier", config.globalRateMultiplier());
        var enabled = parseBooleanField(json, "loadGeneratorEnabled", config.loadGeneratorEnabled());
        return simulatorConfig(parsedEntryPoints, parsedSlices, enabled, multiplier).unwrap();
    }

    private static final Pattern ENTRY_POINT_PATTERN = Pattern.compile("\"(\\w+)\"\\s*:\\s*\\{[^}]*\"callsPerSecond\"\\s*:\\s*(\\d+)");

    private static void parseEntryPointRates(String json, Map<String, EntryPointConfig> entryPoints) {
        ENTRY_POINT_PATTERN.matcher(json)
                           .results()
                           .forEach(match -> updateEntryPointRate(match, entryPoints));
    }

    private static void updateEntryPointRate(MatchResult match, Map<String, EntryPointConfig> entryPoints) {
        var name = match.group(1);
        Number.parseInt(match.group(2))
              .onSuccess(rate -> rateUpdate(name, rate, entryPoints));
    }

    private static void rateUpdate(String name, int rate, Map<String, EntryPointConfig> entryPoints) {
        if (entryPoints.containsKey(name)) {
            var existing = entryPoints.get(name);
            entryPoints.put(name, existing.withRate(rate));
        }
    }

    private static double parseDoubleField(String json, String fieldName, double defaultValue) {
        var pattern = Text.compilePattern("\"" + fieldName + "\"\\s*:\\s*([\\d.]+)")
                          .unwrap();
        var matcher = pattern.matcher(json);
        if (matcher.find()) {
            return Number.parseDouble(matcher.group(1))
                         .or(defaultValue);
        }
        return defaultValue;
    }

    private static boolean parseBooleanField(String json, String fieldName, boolean defaultValue) {
        var pattern = Text.compilePattern("\"" + fieldName + "\"\\s*:\\s*(true|false)")
                          .unwrap();
        var matcher = pattern.matcher(json);
        if (matcher.find()) {
            return Boolean.parseBoolean(matcher.group(1));
        }
        return defaultValue;
    }

    /// Save configuration to file.
    public Result<Unit> saveToFile(Path path) {
        return Result.lift(Causes.forOneValue("Failed to save config to " + path),
                           () -> Files.writeString(path,
                                                   toJson()))
                     .mapToUnit();
    }
}
