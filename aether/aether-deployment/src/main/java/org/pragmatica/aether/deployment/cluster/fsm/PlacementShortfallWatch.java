// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentContext.PlacementShortfall;
import org.pragmatica.lang.Contract;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// v2713 item 1: what the leader reports when a slice's committed target exceeds what its placement audience can place.
///
/// Since rc6 §3.1 the requirement combiner does not clamp the target to the cluster (placeable capacity is unbounded until §3.3).
/// One node hosts at most one instance of an artifact (a `SliceNodeKey` names an artifact and a node), so [SliceAllocationEngine]
/// places one instance per eligible node and the excess stays unplaced, re-attempted on every reconcile tick. That state is raised as
/// `slice-target-exceeds-placement`, naming the target and the eligible node count, once per distinct pair of the two, and closed by
/// `slice-target-placement-restored` when the target fits again or the slice is no longer desired.
final class PlacementShortfallWatch {
    private static final Logger log = LoggerFactory.getLogger(PlacementShortfallWatch.class);

    private final ClusterDeploymentState.Active active;

    PlacementShortfallWatch(ClusterDeploymentState.Active active) {
        this.active = active;
    }

    /// The allocation of `artifact` toward `desired` instances found `placeable` eligible nodes in its audience.
    @Contract
    void observed(Artifact artifact, int desired, int placeable) {
        if (desired > placeable) {
            exceeds(artifact, new PlacementShortfall(desired, placeable));
        } else {
            ended(artifact,
                  "the target of " + desired + " fits the " + placeable + " eligible nodes");
        }
    }

    /// Run at the start of every reconcile tick: ends the shortfall of every slice that is no longer desired.
    @Contract
    void sweep() {
        active.ctx()
              .placementShortfalls()
              .keySet()
              .stream()
              .filter(artifact -> !active.blueprints()
                                         .containsKey(artifact))
              .toList()
              .forEach(artifact -> ended(artifact, "the slice is no longer desired"));
    }

    private void exceeds(Artifact artifact, PlacementShortfall shortfall) {
        var previous = active.ctx()
                             .placementShortfalls()
                             .put(artifact, shortfall);

        if (!shortfall.equals(previous)) {
            OperatorWarnings.raise(log,
                                   active.ctx().operatorWarnings(),
                                   OperatorWarningCode.SLICE_TARGET_EXCEEDS_PLACEMENT,
                                   artifact.asString(),
                                   "Slice {} has a committed target of {} instances but its placement audience has {} eligible nodes; one node "
                                  + "hosts at most one instance, so {} stay unplaced. Add nodes to the audience, or release the requirement "
                                  + "that holds the target up",
                                   artifact,
                                   shortfall.desired(),
                                   shortfall.placeable(),
                                   shortfall.desired() - shortfall.placeable());
        }
    }

    private void ended(Artifact artifact, String why) {
        if (active.ctx()
                  .placementShortfalls()
                  .remove(artifact) != null) {
            OperatorWarnings.raise(log,
                                   active.ctx().operatorWarnings(),
                                   OperatorWarningCode.SLICE_TARGET_PLACEMENT_RESTORED,
                                   artifact.asString(),
                                   "Slice {} can be placed in full again: {}",
                                   artifact,
                                   why);
        }
    }
}
