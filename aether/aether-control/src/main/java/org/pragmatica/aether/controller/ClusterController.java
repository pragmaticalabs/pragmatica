// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller;

import java.util.List;
import java.util.Map;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


public interface ClusterController {
    Promise<ControlDecisions> evaluate(ControlContext context);

    /// Per-artifact scaling context (#423). Each artifact's decision is driven solely by its own
    /// composite load, computed from that artifact's per-slice metric windows — no cluster-wide
    /// average, no cross-artifact gossip. `canScale` reflects window readiness; `errorRateHigh`
    /// gates both directions.
    record ControlContext(Map<Artifact, ArtifactLoad> artifactLoads,
                          Map<Artifact, Blueprint> blueprints,
                          List<NodeId> activeNodes) {
        public Option<ArtifactLoad> loadFor(Artifact artifact) {
            return Option.option(artifactLoads.get(artifact));
        }
    }

    record ArtifactLoad(double compositeScore,
                        boolean canScale,
                        boolean errorRateHigh,
                        Map<ScalingMetric, Double> components) {
        public static ArtifactLoad artifactLoad(double compositeScore,
                                                boolean canScale,
                                                boolean errorRateHigh,
                                                Map<ScalingMetric, Double> components) {
            return new ArtifactLoad(compositeScore, canScale, errorRateHigh, Map.copyOf(components));
        }
    }

    /// Per-artifact scaling blueprint. `maxInstances` (#424) bounds autoscaler scale-up before the
    /// cluster-size cap; `scaleUpThreshold`/`scaleDownThreshold` override the cluster ScalingConfig
    /// tier for this slice when present. All three are optional — absent means "use cluster default".
    record Blueprint(Artifact artifact,
                     int instances,
                     int minInstances,
                     Option<Integer> maxInstances,
                     Option<Double> scaleUpThreshold,
                     Option<Double> scaleDownThreshold) {
        public Blueprint {
            if (maxInstances == null) {
                maxInstances = Option.none();
            }

            if (scaleUpThreshold == null) {
                scaleUpThreshold = Option.none();
            }

            if (scaleDownThreshold == null) {
                scaleDownThreshold = Option.none();
            }
        }

        public static Blueprint blueprint(Artifact artifact, int instances, int minInstances) {
            return new Blueprint(artifact, instances, minInstances, Option.none(), Option.none(), Option.none());
        }
    }

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

        record ScaleUp(Artifact artifact, int additionalInstances) implements BlueprintChange {}

        record ScaleDown(Artifact artifact, int reduceBy) implements BlueprintChange {}
    }
}
