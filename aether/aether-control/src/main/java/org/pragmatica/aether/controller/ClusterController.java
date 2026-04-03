package org.pragmatica.aether.controller;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.util.List;
import java.util.Map;


/// Interface for cluster control decision-making.
///
///
/// Controllers analyze metrics and current state to produce scaling decisions.
/// Implementations can range from simple decision trees to AI-powered systems.
public interface ClusterController {
    Promise<ControlDecisions> evaluate(ControlContext context);

    record ControlContext(Map<NodeId, Map<String, Double>> metrics,
                          Map<Artifact, Blueprint> blueprints,
                          List<NodeId> activeNodes) {
        public double avgMetric(String metricName) {
            var values = metrics.values().stream()
                                       .map(m -> m.get(metricName))
                                       .flatMap(v -> Option.option(v).stream())
                                       .mapToDouble(Double::doubleValue)
                                       .toArray();
            if (values.length == 0) {return 0.0;}
            return java.util.Arrays.stream(values).average()
                                          .orElse(0.0);
        }

        public double maxMetric(String metricName) {
            return metrics.values().stream()
                                 .map(m -> m.get(metricName))
                                 .flatMap(v -> Option.option(v).stream())
                                 .mapToDouble(Double::doubleValue)
                                 .max()
                                 .orElse(0.0);
        }

        public double totalCalls(String methodName) {
            return metrics.values().stream()
                                 .map(m -> m.get("method." + methodName + ".calls"))
                                 .flatMap(v -> Option.option(v).stream())
                                 .mapToDouble(Double::doubleValue)
                                 .sum();
        }
    }

    record Blueprint(Artifact artifact, int instances, int minInstances){}

    record ControlDecisions(List<BlueprintChange> changes) {
        public static ControlDecisions none() {
            return new ControlDecisions(List.of());
        }

        public static ControlDecisions controlDecisions(BlueprintChange... changes) {
            return new ControlDecisions(List.of(changes));
        }
    }

    sealed interface BlueprintChange {
        Artifact artifact();

        record ScaleUp(Artifact artifact, int additionalInstances) implements BlueprintChange{}

        record ScaleDown(Artifact artifact, int reduceBy) implements BlueprintChange{}
    }
}
