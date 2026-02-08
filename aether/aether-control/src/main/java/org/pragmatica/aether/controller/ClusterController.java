package org.pragmatica.aether.controller;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.util.List;
import java.util.Map;

/**
 * Interface for cluster control decision-making.
 *
 * <p>Controllers analyze metrics and current state to produce scaling decisions.
 * Implementations can range from simple decision trees to AI-powered systems.
 */
public interface ClusterController {
    /**
     * Evaluate current cluster state and produce control decisions.
     *
     * @param context Current state of the cluster
     * @return Decisions to be applied
     */
    Promise<ControlDecisions> evaluate(ControlContext context);

    /**
     * Context provided to the controller for decision-making.
     */
    record ControlContext(Map<NodeId, Map<String, Double>> metrics,
                          Map<Artifact, Blueprint> blueprints,
                          List<NodeId> activeNodes) {
        /**
         * Get average value of a metric across all nodes.
         */
        public double avgMetric(String metricName) {
            var values = metrics.values()
                                .stream()
                                .map(m -> m.get(metricName))
                                .flatMap(v -> Option.option(v)
                                                    .stream())
                                .mapToDouble(Double::doubleValue)
                                .toArray();
            if (values.length == 0) {
                return 0.0;
            }
            return java.util.Arrays.stream(values)
                       .average()
                       .orElse(0.0);
        }

        /**
         * Get max value of a metric across all nodes.
         */
        public double maxMetric(String metricName) {
            return metrics.values()
                          .stream()
                          .map(m -> m.get(metricName))
                          .flatMap(v -> Option.option(v)
                                              .stream())
                          .mapToDouble(Double::doubleValue)
                          .max()
                          .orElse(0.0);
        }

        /**
         * Get total calls for a method across all nodes.
         */
        public double totalCalls(String methodName) {
            return metrics.values()
                          .stream()
                          .map(m -> m.get("method." + methodName + ".calls"))
                          .flatMap(v -> Option.option(v)
                                              .stream())
                          .mapToDouble(Double::doubleValue)
                          .sum();
        }
    }

    /**
     * Desired state for a slice (from blueprint).
     */
    record Blueprint(Artifact artifact, int instances) {}

    /**
     * Decisions produced by the controller.
     */
    record ControlDecisions(List<BlueprintChange> changes) {
        public static ControlDecisions none() {
            return new ControlDecisions(List.of());
        }

        public static ControlDecisions controlDecisions(BlueprintChange... changes) {
            return new ControlDecisions(List.of(changes));
        }
    }

    /**
     * Individual scaling decisions.
     */
    sealed interface BlueprintChange {
        Artifact artifact();

        /**
         * Scale up: add more instances.
         */
        record ScaleUp(Artifact artifact, int additionalInstances) implements BlueprintChange {}

        /**
         * Scale down: remove instances.
         */
        record ScaleDown(Artifact artifact, int reduceBy) implements BlueprintChange {}
    }
}
