package org.pragmatica.aether.forge.simulator;

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

        public static NodeKill kill(String nodeId, Duration duration) {
            return new NodeKill(nodeId, duration);
        }

        public static NodeKill killFor(String nodeId, long seconds) {
            return new NodeKill(nodeId, Duration.ofSeconds(seconds));
        }
    }

    /**
     * Simulate network partition between node groups.
     */
    record NetworkPartition(Set<String> group1, Set<String> group2, Duration duration) implements ChaosEvent {
        public NetworkPartition {
            if (group1 == null || group1.isEmpty()) {
                throw new IllegalArgumentException("group1 cannot be null or empty");
            }
            if (group2 == null || group2.isEmpty()) {
                throw new IllegalArgumentException("group2 cannot be null or empty");
            }
            group1 = Set.copyOf(group1);
            group2 = Set.copyOf(group2);
        }

        @Override
        public String type() {
            return "NETWORK_PARTITION";
        }

        @Override
        public String description() {
            return "Network partition between " + group1 + " and " + group2;
        }

        public static NetworkPartition between(Set<String> group1, Set<String> group2, Duration duration) {
            return new NetworkPartition(group1, group2, duration);
        }
    }

    /**
     * Add latency to a specific node's responses.
     */
    record LatencySpike(String nodeId, long latencyMs, Duration duration) implements ChaosEvent {
        public LatencySpike {
            if (nodeId == null || nodeId.isBlank()) {
                throw new IllegalArgumentException("nodeId cannot be null or blank");
            }
            if (latencyMs < 0) {
                throw new IllegalArgumentException("latencyMs must be >= 0");
            }
        }

        @Override
        public String type() {
            return "LATENCY_SPIKE";
        }

        @Override
        public String description() {
            return "Add " + latencyMs + "ms latency to node " + nodeId;
        }

        public static LatencySpike addLatency(String nodeId, long latencyMs, Duration duration) {
            return new LatencySpike(nodeId, latencyMs, duration);
        }
    }

    /**
     * Crash a specific slice on a node.
     */
    record SliceCrash(String sliceArtifact, String nodeId, Duration duration) implements ChaosEvent {
        public SliceCrash {
            if (sliceArtifact == null || sliceArtifact.isBlank()) {
                throw new IllegalArgumentException("sliceArtifact cannot be null or blank");
            }
        }

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

        public static SliceCrash crashSlice(String artifact, String nodeId, Duration duration) {
            return new SliceCrash(artifact, nodeId, duration);
        }

        public static SliceCrash crashSliceEverywhere(String artifact, Duration duration) {
            return new SliceCrash(artifact, null, duration);
        }
    }

    /**
     * Simulate memory pressure on a node.
     */
    record MemoryPressure(String nodeId, double level, Duration duration) implements ChaosEvent {
        public MemoryPressure {
            if (nodeId == null || nodeId.isBlank()) {
                throw new IllegalArgumentException("nodeId cannot be null or blank");
            }
            if (level < 0 || level > 1) {
                throw new IllegalArgumentException("level must be between 0 and 1");
            }
        }

        @Override
        public String type() {
            return "MEMORY_PRESSURE";
        }

        @Override
        public String description() {
            return String.format("Simulate %.0f%% memory pressure on node %s", level * 100, nodeId);
        }

        public static MemoryPressure onNode(String nodeId, double level, Duration duration) {
            return new MemoryPressure(nodeId, level, duration);
        }
    }

    /**
     * Simulate CPU spike on a node.
     */
    record CpuSpike(String nodeId, double level, Duration duration) implements ChaosEvent {
        public CpuSpike {
            if (nodeId == null || nodeId.isBlank()) {
                throw new IllegalArgumentException("nodeId cannot be null or blank");
            }
            if (level < 0 || level > 1) {
                throw new IllegalArgumentException("level must be between 0 and 1");
            }
        }

        @Override
        public String type() {
            return "CPU_SPIKE";
        }

        @Override
        public String description() {
            return String.format("Simulate %.0f%% CPU usage on node %s", level * 100, nodeId);
        }

        public static CpuSpike onNode(String nodeId, double level, Duration duration) {
            return new CpuSpike(nodeId, level, duration);
        }
    }

    /**
     * Inject random failures into slice invocations.
     */
    record InvocationFailure(String sliceArtifact, double failureRate, Duration duration) implements ChaosEvent {
        public InvocationFailure {
            if (failureRate < 0 || failureRate > 1) {
                throw new IllegalArgumentException("failureRate must be between 0 and 1");
            }
        }

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

        public static InvocationFailure forSlice(String artifact, double rate, Duration duration) {
            return new InvocationFailure(artifact, rate, duration);
        }

        public static InvocationFailure forAllSlices(double rate, Duration duration) {
            return new InvocationFailure(null, rate, duration);
        }
    }

    /**
     * Custom chaos event with arbitrary action.
     */
    record CustomChaos(String name, String description, Runnable action, Duration duration) implements ChaosEvent {
        public CustomChaos {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name cannot be null or blank");
            }
            if (action == null) {
                throw new IllegalArgumentException("action cannot be null");
            }
        }

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

        public static CustomChaos custom(String name, String description, Runnable action, Duration duration) {
            return new CustomChaos(name, description, action, duration);
        }
    }
}
