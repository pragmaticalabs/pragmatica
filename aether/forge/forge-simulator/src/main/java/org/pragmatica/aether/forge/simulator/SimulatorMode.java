package org.pragmatica.aether.forge.simulator;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.Result.success;

/// Simulator operating modes for different testing scenarios.
/// Each mode provides different defaults for load generation, backend simulation, and chaos testing.
public enum SimulatorMode {
    /// Development mode - fast iteration, minimal simulation.
    /// Load generator disabled, no latency, no chaos.
    DEVELOPMENT("Development", false, false, false, 0.0),
    /// Load test mode - high throughput, metrics focus.
    /// Load generator enabled at full rate, minimal latency, no chaos.
    LOAD_TEST("Load Test", true, false, false, 1.0),
    /// Chaos test mode - failure injection enabled.
    /// Load generator at reduced rate, realistic latency, chaos enabled.
    CHAOS_TEST("Chaos Test", true, true, true, 0.5),
    /// Integration mode - realistic backends, no chaos.
    /// Load generator at low rate, realistic latency, no chaos.
    INTEGRATION("Integration", true, true, false, 0.1);
    private final String displayName;
    private final boolean loadGeneratorEnabled;
    private final boolean realisticLatency;
    private final boolean chaosEnabled;
    private final double rateMultiplier;
    SimulatorMode(String displayName,
                  boolean loadGeneratorEnabled,
                  boolean realisticLatency,
                  boolean chaosEnabled,
                  double rateMultiplier) {
        this.displayName = displayName;
        this.loadGeneratorEnabled = loadGeneratorEnabled;
        this.realisticLatency = realisticLatency;
        this.chaosEnabled = chaosEnabled;
        this.rateMultiplier = rateMultiplier;
    }
    public String displayName() {
        return displayName;
    }
    public boolean loadGeneratorEnabled() {
        return loadGeneratorEnabled;
    }
    public boolean realisticLatency() {
        return realisticLatency;
    }
    public boolean chaosEnabled() {
        return chaosEnabled;
    }
    public double rateMultiplier() {
        return rateMultiplier;
    }
    /// Get the default backend simulation for this mode.
    public BackendSimulation defaultBackendSimulation() {
        if (!realisticLatency) {
            return BackendSimulation.NoOp.noOp()
                                    .unwrap();
        }
        return BackendSimulation.LatencySimulation.latencySimulation(10, 5, 0.01, 100)
                                .map(sim -> (BackendSimulation) sim)
                                .unwrap();
    }
    /// Create a SimulatorConfig for this mode based on a template config.
    public SimulatorConfig applyTo(SimulatorConfig template) {
        return template.withLoadGeneratorEnabled(loadGeneratorEnabled)
                       .withGlobalMultiplier(rateMultiplier);
    }
    public String toJson() {
        return String.format("{\"mode\":\"%s\",\"displayName\":\"%s\",\"loadGeneratorEnabled\":%b,"
                             + "\"realisticLatency\":%b,\"chaosEnabled\":%b,\"rateMultiplier\":%.2f}",
                             name(),
                             displayName,
                             loadGeneratorEnabled,
                             realisticLatency,
                             chaosEnabled,
                             rateMultiplier);
    }
    /// Parse mode from string, case-insensitive.
    /// Returns Result for proper error handling per JBCT patterns.
    public static Result<SimulatorMode> simulatorMode(String value) {
        return ensureNotBlank(value).map(SimulatorMode::normalizeModeName)
                             .flatMap(SimulatorMode::normalizedMode);
    }
    private static Result<String> ensureNotBlank(String value) {
        return Verify.ensure(value, Verify.Is::notNull, ModeError.Empty.INSTANCE)
                     .filter(ModeError.Empty.INSTANCE,
                             v -> !v.isBlank());
    }
    private static String normalizeModeName(String value) {
        return value.toUpperCase()
                    .replace("-", "_")
                    .replace(" ", "_");
    }
    private static Result<SimulatorMode> normalizedMode(String normalized) {
        try{
            return success(valueOf(normalized));
        } catch (IllegalArgumentException e) {
            return new ModeError.Unknown(normalized).result();
        }
    }
    /// Get all modes as JSON array.
    public static String allModesJson() {
        var sb = new StringBuilder("[");
        var first = true;
        for (var mode : values()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append(mode.toJson());
        }
        sb.append("]");
        return sb.toString();
    }
    /// Mode parsing errors.
    public sealed interface ModeError extends Cause {
        record Empty() implements ModeError {
            private static final Empty INSTANCE = empty().unwrap();

            public static Result<Empty> empty() {
                return success(new Empty());
            }

            @Override
            public String message() {
                return "Mode name cannot be empty";
            }
        }

        record Unknown(String value) implements ModeError {
            public static Result<Unknown> unknown(String value) {
                return success(new Unknown(value));
            }

            @Override
            public String message() {
                return "Unknown simulator mode: " + value;
            }
        }

        record unused() implements ModeError {
            @Override
            public String message() {
                return "";
            }
        }
    }
}
