package org.pragmatica.aether.forge.simulator;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;

import java.time.Duration;
import java.util.Set;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;

/// Chaos events that can be injected into the system for resilience testing.
public sealed interface ChaosEvent {
    /// Type identifier for the event.
    String type();

    /// Human-readable description.
    String description();

    /// How long the chaos effect should last.
    /// Empty means permanent until explicitly stopped.
    Option<Duration> duration();

    // Shared validation causes
    Cause NODE_ID_REQUIRED = Causes.cause("nodeId cannot be null or blank");
    Cause LEVEL_OUT_OF_RANGE = Causes.cause("level must be between 0 and 1");
    Cause FAILURE_RATE_OUT_OF_RANGE = Causes.cause("failureRate must be between 0 and 1");

    /// Kill a specific node (simulates crash).
    record NodeKill(String nodeId, Option<Duration> duration) implements ChaosEvent {
        @Override
        public String type() {
            return "NODE_KILL";
        }

        @Override
        public String description() {
            return "Kill node " + nodeId;
        }

        public static Result<NodeKill> nodeKill(String nodeId, Option<Duration> duration) {
            return ensureNotBlank(nodeId, NODE_ID_REQUIRED).map(id -> new NodeKill(id, duration));
        }

        public static Result<NodeKill> nodeKill(String nodeId, long seconds) {
            return nodeKill(nodeId, some(Duration.ofSeconds(seconds)));
        }

        public static Result<NodeKill> nodeKill(String nodeId) {
            return nodeKill(nodeId, none());
        }
    }

    /// Simulate network partition between node groups.
    record NetworkPartition(Set<String> group1, Set<String> group2, Option<Duration> duration) implements ChaosEvent {
        private static final Cause GROUP1_EMPTY = Causes.cause("group1 cannot be null or empty");
        private static final Cause GROUP2_EMPTY = Causes.cause("group2 cannot be null or empty");

        public NetworkPartition(Set<String> group1, Set<String> group2, Option<Duration> duration) {
            this.group1 = group1 == null
                          ? Set.of()
                          : Set.copyOf(group1);
            this.group2 = group2 == null
                          ? Set.of()
                          : Set.copyOf(group2);
            this.duration = duration;
        }

        @Override
        public String type() {
            return "NETWORK_PARTITION";
        }

        @Override
        public String description() {
            return "Network partition between " + group1 + " and " + group2;
        }

        public static Result<NetworkPartition> networkPartition(Set<String> group1,
                                                                Set<String> group2,
                                                                Option<Duration> duration) {
            return ensureNonEmptyGroup(group1, GROUP1_EMPTY).flatMap(_ -> ensureNonEmptyGroup(group2, GROUP2_EMPTY))
                                      .map(_ -> new NetworkPartition(group1, group2, duration));
        }

        private static Result<Set<String>> ensureNonEmptyGroup(Set<String> group, Cause cause) {
            return Verify.ensure(group, Verify.Is::notNull, cause)
                         .filter(cause,
                                 g -> !g.isEmpty());
        }
    }

    /// Add latency to a specific node's responses.
    record LatencySpike(String nodeId, long latencyMs, Option<Duration> duration) implements ChaosEvent {
        private static final Cause LATENCY_NEGATIVE = Causes.cause("latencyMs must be >= 0");

        @Override
        public String type() {
            return "LATENCY_SPIKE";
        }

        @Override
        public String description() {
            return "Add " + latencyMs + "ms latency to node " + nodeId;
        }

        public static Result<LatencySpike> latencySpike(String nodeId, long latencyMs, Option<Duration> duration) {
            return ensureNotBlank(nodeId, NODE_ID_REQUIRED).flatMap(_ -> Verify.ensure(latencyMs,
                                                                                       Verify.Is::nonNegative,
                                                                                       LATENCY_NEGATIVE))
                                 .map(_ -> new LatencySpike(nodeId, latencyMs, duration));
        }
    }

    /// Crash a specific slice on a node.
    record SliceCrash(String sliceArtifact, Option<String> nodeId, Option<Duration> duration) implements ChaosEvent {
        private static final Cause ARTIFACT_REQUIRED = Causes.cause("sliceArtifact cannot be null or blank");

        @Override
        public String type() {
            return "SLICE_CRASH";
        }

        @Override
        public String description() {
            var target = nodeId.map(n -> " on node " + n)
                               .or(" on all nodes");
            return "Crash slice " + sliceArtifact + target;
        }

        public static Result<SliceCrash> sliceCrash(String artifact, Option<String> nodeId, Option<Duration> duration) {
            return ensureNotBlank(artifact, ARTIFACT_REQUIRED).map(a -> new SliceCrash(a, nodeId, duration));
        }

        public static Result<SliceCrash> sliceCrash(String artifact, Option<Duration> duration) {
            return sliceCrash(artifact, none(), duration);
        }
    }

    /// Simulate memory pressure on a node.
    record MemoryPressure(String nodeId, double level, Option<Duration> duration) implements ChaosEvent {
        @Override
        public String type() {
            return "MEMORY_PRESSURE";
        }

        @Override
        public String description() {
            return formatPercentageMessage("memory pressure", level, nodeId);
        }

        public static Result<MemoryPressure> memoryPressure(String nodeId, double level, Option<Duration> duration) {
            return ensureNotBlank(nodeId, NODE_ID_REQUIRED).flatMap(_ -> Verify.ensure(level,
                                                                                       Verify.Is::between,
                                                                                       0.0,
                                                                                       1.0,
                                                                                       LEVEL_OUT_OF_RANGE))
                                 .map(_ -> new MemoryPressure(nodeId, level, duration));
        }
    }

    /// Simulate CPU spike on a node.
    record CpuSpike(String nodeId, double level, Option<Duration> duration) implements ChaosEvent {
        @Override
        public String type() {
            return "CPU_SPIKE";
        }

        @Override
        public String description() {
            return formatPercentageMessage("CPU usage", level, nodeId);
        }

        public static Result<CpuSpike> cpuSpike(String nodeId, double level, Option<Duration> duration) {
            return ensureNotBlank(nodeId, NODE_ID_REQUIRED).flatMap(_ -> Verify.ensure(level,
                                                                                       Verify.Is::between,
                                                                                       0.0,
                                                                                       1.0,
                                                                                       LEVEL_OUT_OF_RANGE))
                                 .map(_ -> new CpuSpike(nodeId, level, duration));
        }
    }

    /// Inject random failures into slice invocations.
    record InvocationFailure(Option<String> sliceArtifact, double failureRate, Option<Duration> duration) implements ChaosEvent {
        @Override
        public String type() {
            return "INVOCATION_FAILURE";
        }

        @Override
        public String description() {
            var target = sliceArtifact.or("all slices");
            return String.format("Inject %.0f%% failure rate for %s", failureRate * 100, target);
        }

        public static Result<InvocationFailure> invocationFailure(Option<String> artifact,
                                                                  double rate,
                                                                  Option<Duration> duration) {
            return Verify.ensure(rate, Verify.Is::between, 0.0, 1.0, FAILURE_RATE_OUT_OF_RANGE)
                         .map(_ -> new InvocationFailure(artifact, rate, duration));
        }

        public static Result<InvocationFailure> invocationFailure(double rate, Option<Duration> duration) {
            return invocationFailure(none(), rate, duration);
        }
    }

    /// Custom chaos event with arbitrary action.
    record CustomChaos(String name, Option<String> descriptionText, Runnable action, Option<Duration> duration) implements ChaosEvent {
        private static final Cause NAME_REQUIRED = Causes.cause("name cannot be null or blank");
        private static final Cause ACTION_REQUIRED = Causes.cause("action cannot be null");

        @Override
        public String type() {
            return "CUSTOM";
        }

        @Override
        public String description() {
            return descriptionText.or(name);
        }

        public static Result<CustomChaos> customChaos(String name,
                                                      Option<String> descriptionText,
                                                      Runnable action,
                                                      Option<Duration> duration) {
            return ensureNotBlank(name, NAME_REQUIRED).flatMap(_ -> Verify.ensure(action,
                                                                                  Verify.Is::notNull,
                                                                                  ACTION_REQUIRED))
                                 .map(_ -> new CustomChaos(name, descriptionText, action, duration));
        }
    }

    /// Validate that a string field is non-null and non-blank.
    private static Result<String> ensureNotBlank(String value, Cause cause) {
        return Verify.ensure(value, Verify.Is::notNull, cause)
                     .filter(cause,
                             v -> !v.isBlank());
    }

    /// Format a percentage-based simulation description.
    private static String formatPercentageMessage(String metricName, double level, String nodeId) {
        return String.format("Simulate %.0f%% %s on node %s", level * 100, metricName, nodeId);
    }

    record unused() implements ChaosEvent {
        @Override
        public String type() {
            return "";
        }

        @Override
        public String description() {
            return "";
        }

        @Override
        public Option<Duration> duration() {
            return none();
        }
    }
}
