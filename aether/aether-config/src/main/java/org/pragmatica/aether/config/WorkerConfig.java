package org.pragmatica.aether.config;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.util.List;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;

/// Configuration for a worker node.
/// Worker nodes are passive compute nodes that run slices without
/// participating in Rabia consensus.
///
/// @param coreNodes    Core cluster addresses to connect to (host:port)
/// @param clusterPort  Port for cluster TCP communication
/// @param swimPort     Port for SWIM UDP failure detection
/// @param swimSettings SWIM protocol tuning
/// @param sliceConfig  Slice repository configuration
@SuppressWarnings({"JBCT-ZONE-02", "JBCT-ZONE-03"})
public record WorkerConfig(List<String> coreNodes,
                           int clusterPort,
                           int swimPort,
                           SwimSettings swimSettings,
                           SliceConfig sliceConfig,
                           String groupName,
                           String zone,
                           int maxGroupSize) {
    public static final int DEFAULT_CLUSTER_PORT = 7100;
    public static final int DEFAULT_SWIM_PORT = 7200;
    public static final String DEFAULT_GROUP_NAME = "default";
    public static final String DEFAULT_ZONE = "local";
    public static final int DEFAULT_MAX_GROUP_SIZE = 100;

    /// Compact constructor to normalize null/blank/invalid values to defaults.
    public WorkerConfig {
        if (groupName == null || groupName.isBlank()) {
            groupName = DEFAULT_GROUP_NAME;
        }
        if (zone == null || zone.isBlank()) {
            zone = DEFAULT_ZONE;
        }
        if (maxGroupSize < 2) {
            maxGroupSize = DEFAULT_MAX_GROUP_SIZE;
        }
    }

    /// Factory method with validation for all fields following JBCT naming convention.
    public static Result<WorkerConfig> workerConfig(List<String> coreNodes,
                                                    int clusterPort,
                                                    int swimPort,
                                                    SwimSettings swimSettings,
                                                    SliceConfig sliceConfig,
                                                    String groupName,
                                                    String zone,
                                                    int maxGroupSize) {
        return checkCoreNodes(coreNodes).flatMap(_ -> checkPort("clusterPort", clusterPort))
                             .flatMap(_ -> checkPort("swimPort", swimPort))
                             .flatMap(_ -> checkNotBlank("groupName", groupName))
                             .flatMap(_ -> checkNotBlank("zone", zone))
                             .flatMap(_ -> checkMinValue("maxGroupSize", maxGroupSize, 2))
                             .map(_ -> new WorkerConfig(List.copyOf(coreNodes),
                                                        clusterPort,
                                                        swimPort,
                                                        swimSettings,
                                                        sliceConfig,
                                                        groupName,
                                                        zone,
                                                        maxGroupSize));
    }

    /// Factory method with defaults for group fields following JBCT naming convention.
    public static Result<WorkerConfig> workerConfig(List<String> coreNodes,
                                                    int clusterPort,
                                                    int swimPort,
                                                    SwimSettings swimSettings,
                                                    SliceConfig sliceConfig) {
        return workerConfig(coreNodes,
                            clusterPort,
                            swimPort,
                            swimSettings,
                            sliceConfig,
                            DEFAULT_GROUP_NAME,
                            DEFAULT_ZONE,
                            DEFAULT_MAX_GROUP_SIZE);
    }

    private static Result<List<String>> checkCoreNodes(List<String> coreNodes) {
        return option(coreNodes).filter(nodes -> !nodes.isEmpty())
                     .toResult(WorkerConfigError.invalidWorkerConfig("coreNodes must not be empty"));
    }

    private static Result<Integer> checkPort(String name, int port) {
        return port >= 1 && port <= 65535
               ? success(port)
               : WorkerConfigError.invalidWorkerConfig(name + " must be 1-65535, got: " + port)
                                  .result();
    }

    private static Result<String> checkNotBlank(String name, String value) {
        return option(value).filter(v -> !v.isBlank())
                     .toResult(WorkerConfigError.invalidWorkerConfig(name + " must not be blank"));
    }

    private static Result<Integer> checkMinValue(String name, int value, int min) {
        return value >= min
               ? success(value)
               : WorkerConfigError.invalidWorkerConfig(name + " must be >= " + min + ", got: " + value)
                                  .result();
    }

    /// SWIM protocol tuning settings.
    ///
    /// @param periodMs         SWIM probe period in ms
    /// @param probeTimeoutMs   Timeout waiting for ack in ms
    /// @param indirectProbes   Number of indirect probes on timeout
    /// @param suspectTimeoutMs Time in suspect state before marking faulty in ms
    /// @param maxPiggyback     Max piggyback updates per message
    public record SwimSettings(long periodMs,
                               long probeTimeoutMs,
                               int indirectProbes,
                               long suspectTimeoutMs,
                               int maxPiggyback) {
        public static final long DEFAULT_PERIOD_MS = 1000;
        public static final long DEFAULT_PROBE_TIMEOUT_MS = 500;
        public static final int DEFAULT_INDIRECT_PROBES = 3;
        public static final long DEFAULT_SUSPECT_TIMEOUT_MS = 5000;
        public static final int DEFAULT_MAX_PIGGYBACK = 8;

        @SuppressWarnings("JBCT-VO-02") // Bootstrap default — factory delegates here
        private static final SwimSettings DEFAULT = new SwimSettings(DEFAULT_PERIOD_MS,
                                                                     DEFAULT_PROBE_TIMEOUT_MS,
                                                                     DEFAULT_INDIRECT_PROBES,
                                                                     DEFAULT_SUSPECT_TIMEOUT_MS,
                                                                     DEFAULT_MAX_PIGGYBACK);

        /// Default SWIM settings.
        public static SwimSettings swimSettings() {
            return DEFAULT;
        }

        /// Factory method with validation following JBCT naming convention.
        public static Result<SwimSettings> swimSettings(long periodMs,
                                                        long probeTimeoutMs,
                                                        int indirectProbes,
                                                        long suspectTimeoutMs,
                                                        int maxPiggyback) {
            return checkPositiveLong("periodMs", periodMs).flatMap(_ -> checkPositiveLong("probeTimeoutMs",
                                                                                          probeTimeoutMs))
                                    .flatMap(_ -> checkPositiveInt("indirectProbes", indirectProbes))
                                    .flatMap(_ -> checkPositiveLong("suspectTimeoutMs", suspectTimeoutMs))
                                    .flatMap(_ -> checkPositiveInt("maxPiggyback", maxPiggyback))
                                    .map(_ -> new SwimSettings(periodMs,
                                                               probeTimeoutMs,
                                                               indirectProbes,
                                                               suspectTimeoutMs,
                                                               maxPiggyback));
        }

        private static Result<Long> checkPositiveLong(String name, long value) {
            return value > 0
                   ? success(value)
                   : WorkerConfigError.invalidWorkerConfig(name + " must be positive, got: " + value)
                                      .result();
        }

        private static Result<Integer> checkPositiveInt(String name, int value) {
            return value > 0
                   ? success(value)
                   : WorkerConfigError.invalidWorkerConfig(name + " must be positive, got: " + value)
                                      .result();
        }
    }

    /// Error hierarchy for worker configuration failures.
    public sealed interface WorkerConfigError extends Cause {
        record unused() implements WorkerConfigError {
            @Override
            public String message() {
                return "unused";
            }
        }

        /// Configuration error for WorkerConfig.
        record InvalidWorkerConfig(String detail) implements WorkerConfigError {
            /// Factory method following JBCT naming convention.
            public static Result<InvalidWorkerConfig> invalidWorkerConfig(String detail, boolean validated) {
                return success(new InvalidWorkerConfig(detail));
            }

            public static InvalidWorkerConfig invalidWorkerConfig(String detail) {
                return invalidWorkerConfig(detail, true).unwrap();
            }

            @Override
            public String message() {
                return "Invalid worker configuration: " + detail;
            }
        }

        static WorkerConfigError invalidWorkerConfig(String detail) {
            return InvalidWorkerConfig.invalidWorkerConfig(detail);
        }
    }
}
