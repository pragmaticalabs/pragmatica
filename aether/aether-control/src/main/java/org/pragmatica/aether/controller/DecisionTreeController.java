// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller;

import java.util.List;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


/// Per-slice scaling controller (#423). Each blueprint's decision is driven solely by that
/// artifact's own composite load — no cluster-wide CPU average, no cross-artifact call-rate gossip
/// (the #422 mis-attribution class). A high error rate on the artifact gates both directions.
public interface DecisionTreeController extends ClusterController {
    static DecisionTreeController decisionTreeController() {
        return decisionTreeController(ControllerConfig.DEFAULT);
    }

    static Result<DecisionTreeController> decisionTreeController(double cpuScaleUpThreshold,
                                                                 double cpuScaleDownThreshold,
                                                                 double callRateScaleUpThreshold) {
        return ControllerConfig.controllerConfig(cpuScaleUpThreshold,
                                                 cpuScaleDownThreshold,
                                                 callRateScaleUpThreshold,
                                                 1000).map(config -> decisionTreeController(config));
    }

    static DecisionTreeController decisionTreeController(ControllerConfig config) {
        return new ControllerState(config);
    }

    ControllerConfig configuration();
    Unit updateConfiguration(ControllerConfig config);

    final class ControllerState implements DecisionTreeController {
        private static final Logger log = LoggerFactory.getLogger(DecisionTreeController.class);

        private volatile ControllerConfig config;

        ControllerState(ControllerConfig config) {
            this.config = config;
        }

        @Override
        public ControllerConfig configuration() {
            return config;
        }

        @Override
        public Unit updateConfiguration(ControllerConfig config) {
            log.info("Controller configuration updated: {}", config);
            this.config = config;

            return unit();
        }

        @Override
        public Promise<ControlDecisions> evaluate(ControlContext context) {
            var scalingConfig = config.scalingConfig();
            var changes = context.blueprints()
                                 .entrySet()
                                 .stream()
                                 .flatMap(entry -> evaluateBlueprint(entry.getKey(),
                                                                     entry.getValue(),
                                                                     context.loadFor(entry.getKey()),
                                                                     scalingConfig).stream())
                                 .toList();

            return Promise.success(new ControlDecisions(changes));
        }

        private List<BlueprintChange> evaluateBlueprint(Artifact artifact,
                                                        Blueprint blueprint,
                                                        Option<ArtifactLoad> load,
                                                        ScalingConfig scalingConfig) {
            return load.filter(ArtifactLoad::canScale)
                       .flatMap(current -> decideForArtifact(artifact, blueprint, current, scalingConfig))
                       .map(change -> List.<BlueprintChange> of(change))
                       .or(List.of());
        }

        private Option<BlueprintChange> decideForArtifact(Artifact artifact,
                                                          Blueprint blueprint,
                                                          ArtifactLoad load,
                                                          ScalingConfig scalingConfig) {
            if (load.errorRateHigh()) {
                log.debug("Scaling for {} gated by high error rate", artifact);

                return Option.none();
            }

            double scaleUpThreshold = blueprint.scaleUpThreshold().or(scalingConfig.scaleUpThreshold());
            double scaleDownThreshold = blueprint.scaleDownThreshold().or(scalingConfig.scaleDownThreshold());

            if (load.compositeScore() >= scaleUpThreshold) {
                log.info("Rule triggered: high load ({} >= {}), scaling up {}",
                         load.compositeScore(),
                         scaleUpThreshold,
                         artifact);

                return Option.some(new BlueprintChange.ScaleUp(artifact, 1));
            }

            if (load.compositeScore() <= scaleDownThreshold && blueprint.instances() > blueprint.minInstances()) {
                log.debug("Rule triggered: low load ({} <= {}), scaling down {}",
                          load.compositeScore(),
                          scaleDownThreshold,
                          artifact);

                return Option.some(new BlueprintChange.ScaleDown(artifact, 1));
            }

            return Option.none();
        }
    }
}
