package org.pragmatica.aether.forge.simulator;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.time.Duration;
import java.util.Set;

/**
 * Chaos events that can be injected into the system for resilience testing.
 */
public sealed interface ChaosEvent {
    /**
     * Type identifier for the event.
     */
    String type();

    /**
     * Human-readable description.
     */
    String description();

    /**
     * How long the chaos effect should last.
     * Null or zero means permanent until explicitly stopped.
     */
    Duration duration();

    // Shared validation causes
    Cause NODE_ID_REQUIRED = Causes.cause("nodeId cannot be null or blank");
    Cause LEVEL_OUT_OF_RANGE = Causes.cause("level must be between 0 and 1");
    Cause FAILURE_RATE_OUT_OF_RANGE = Causes.cause("failureRate must be between 0 and 1");

    /**
     * Kill a specific node (simulates crash).
     */
    record NodeKill(String nodeId, Duration duration) implements ChaosEvent {
        @Override
        public String type() {
            return "NODE_KILL";
        }

        @Override
        public String description() {
            return "Kill node " + nodeId;
        }

        public static Result<NodeKill> kill(String nodeId, Duration duration) {
            if (nodeId == null || nodeId.isBlank()) {
                return NODE_ID_REQUIRED.result();
            }
            return Result.success(new NodeKill(nodeId, duration));
        }

        public static Result<NodeKill> killFor(String nodeId, long seconds) {
            return kill(nodeId, Duration.ofSeconds(seconds));
        }
    }

    /**
     * Simulate network partition between node groups.
     */
    record NetworkPartition(Set<String> group1, Set<String> group2, Duration duration) implements ChaosEvent {
        private static final Cause GROUP1_EMPTY = Causes.cause("group1 cannot be null or empty");
        private static final Cause GROUP2_EMPTY = Causes.cause("group2 cannot be null or empty");

        public NetworkPartition(Set<String> group1, Set<String> group2, Duration duration) {
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

        public static Result<NetworkPartition> between(Set<String> group1, Set<String> group2, Duration duration) {
            if (group1 == null || group1.isEmpty()) {
                return GROUP1_EMPTY.result();
            }
            if (group2 == null || group2.isEmpty()) {
                return GROUP2_EMPTY.result();
            }
            return Result.success(new NetworkPartition(group1, group2, duration));
        }
    }

    /**
     * Add latency to a specific node's responses.
     */
    record LatencySpike(String nodeId, long latencyMs, Duration duration) implements ChaosEvent {
        private static final Cause LATENCY_NEGATIVE = Causes.cause("latencyMs must be >= 0");

        @Override
        public String type() {
            return "LATENCY_SPIKE";
        }

        @Override
        public String description() {
            return "Add " + latencyMs + "ms latency to node " + nodeId;
        }

        public static Result<LatencySpike> addLatency(String nodeId, long latencyMs, Duration duration) {
            if (nodeId == null || nodeId.isBlank()) {
                return NODE_ID_REQUIRED.result();
            }
            if (latencyMs < 0) {
                return LATENCY_NEGATIVE.result();
            }
            return Result.success(new LatencySpike(nodeId, latencyMs, duration));
        }
    }

    /**
     * Crash a specific slice on a node.
     */
    record SliceCrash(String sliceArtifact, String nodeId, Duration duration) implements ChaosEvent {
        private static final Cause ARTIFACT_REQUIRED = Causes.cause("sliceArtifact cannot be null or blank");

        @Override
        public String type() {
            return "SLICE_CRASH";
        }

        @Override
        public String description() {
            var target = nodeId != null
                         ? " on node " + nodeId
                         : " on all nodes";
            return "Crash slice " + sliceArtifact + target;
        }

        public static Result<SliceCrash> crashSlice(String artifact, String nodeId, Duration duration) {
            if (artifact == null || artifact.isBlank()) {
                return ARTIFACT_REQUIRED.result();
            }
            return Result.success(new SliceCrash(artifact, nodeId, duration));
        }

        public static Result<SliceCrash> crashSliceEverywhere(String artifact, Duration duration) {
            return crashSlice(artifact, null, duration);
        }
    }

    /**
     * Simulate memory pressure on a node.
     */
    record MemoryPressure(String nodeId, double level, Duration duration) implements ChaosEvent {
        @Override
        public String type() {
            return "MEMORY_PRESSURE";
        }

        @Override
        public String description() {
            return String.format("Simulate %.0f%% memory pressure on node %s", level * 100, nodeId);
        }

        public static Result<MemoryPressure> onNode(String nodeId, double level, Duration duration) {
            if (nodeId == null || nodeId.isBlank()) {
                return NODE_ID_REQUIRED.result();
            }
            if (level < 0 || level > 1) {
                return LEVEL_OUT_OF_RANGE.result();
            }
            return Result.success(new MemoryPressure(nodeId, level, duration));
        }
    }

    /**
     * Simulate CPU spike on a node.
     */
    record CpuSpike(String nodeId, double level, Duration duration) implements ChaosEvent {
        @Override
        public String type() {
            return "CPU_SPIKE";
        }

        @Override
        public String description() {
            return String.format("Simulate %.0f%% CPU usage on node %s", level * 100, nodeId);
        }

        public static Result<CpuSpike> onNode(String nodeId, double level, Duration duration) {
            if (nodeId == null || nodeId.isBlank()) {
                return NODE_ID_REQUIRED.result();
            }
            if (level < 0 || level > 1) {
                return LEVEL_OUT_OF_RANGE.result();
            }
            return Result.success(new CpuSpike(nodeId, level, duration));
        }
    }

    /**
     * Inject random failures into slice invocations.
     */
    record InvocationFailure(String sliceArtifact, double failureRate, Duration duration) implements ChaosEvent {
        @Override
        public String type() {
            return "INVOCATION_FAILURE";
        }

        @Override
        public String description() {
            var target = sliceArtifact != null
                         ? sliceArtifact
                         : "all slices";
            return String.format("Inject %.0f%% failure rate for %s", failureRate * 100, target);
        }

        public static Result<InvocationFailure> forSlice(String artifact, double rate, Duration duration) {
            if (rate < 0 || rate > 1) {
                return FAILURE_RATE_OUT_OF_RANGE.result();
            }
            return Result.success(new InvocationFailure(artifact, rate, duration));
        }

        public static Result<InvocationFailure> forAllSlices(double rate, Duration duration) {
            return forSlice(null, rate, duration);
        }
    }

    /**
     * Custom chaos event with arbitrary action.
     */
    record CustomChaos(String name, String description, Runnable action, Duration duration) implements ChaosEvent {
        private static final Cause NAME_REQUIRED = Causes.cause("name cannot be null or blank");
        private static final Cause ACTION_REQUIRED = Causes.cause("action cannot be null");

        @Override
        public String type() {
            return "CUSTOM";
        }

        @Override
        public String description() {
            return description != null
                   ? description
                   : name;
        }

        public static Result<CustomChaos> custom(String name, String description, Runnable action, Duration duration) {
            if (name == null || name.isBlank()) {
                return NAME_REQUIRED.result();
            }
            if (action == null) {
                return ACTION_REQUIRED.result();
            }
            return Result.success(new CustomChaos(name, description, action, duration));
        }
    }
}
