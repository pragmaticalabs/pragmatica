// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import java.util.Map;

import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentState.Active;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Stuck-transitional detection and remediation seam extracted (move-only) from
/// {@link Active}. A slice that lingers in a transitional {@link SliceState} past
/// {@code STUCK_TIMEOUT_MULTIPLIER}× its declared timeout is force-remediated: LOAD/ACTIVATE/ROUTING
/// states are force-unloaded; DEACTIVATE/UNLOAD states are force-removed from the DHT. States not
/// handled by the switch stay tracked in {@code transitionalStateTimestamps} so they are re-detected
/// on later reconcile sweeps (repeated-detection WARNs are intended).
record StuckTransitionalRemediator(Active active) {
    private static final Logger log = LoggerFactory.getLogger(StuckTransitionalRemediator.class);
    private static final int STUCK_TIMEOUT_MULTIPLIER = 3;

    // Fire-and-forget remediation sweep: invoked by the reconcile path, which ignores the outcome;
    // issued unload/remove commands handle their own failures inline. void is the contract.
    @Contract
    void detectStuckTransitionalStates() {
        var now = active.ctx().nowMs();
        var stuckEntries = transitionalStateTimestamps().entrySet()
                                                      .stream()
                                                      .filter(entry -> isStuckTransitional(entry.getKey(),
                                                                                           entry.getValue(),
                                                                                           now))
                                                      .map(Map.Entry::getKey)
                                                      .toList();

        if (stuckEntries.isEmpty()) {
            return;
        }

        log.warn("Detected {} slices stuck in transitional states", stuckEntries.size());
        stuckEntries.forEach(this::issueStuckRemediationCommand);
    }

    private boolean isStuckTransitional(SliceNodeKey sliceKey, long enteredAt, long now) {
        return Option.option(sliceStates().get(sliceKey))
                     .filter(SliceState::isTransitional)
                     .flatMap(SliceState::timeout)
                     .filter(timeout -> (now - enteredAt) > timeout.millis() * STUCK_TIMEOUT_MULTIPLIER)
                     .isPresent();
    }

    private void issueStuckRemediationCommand(SliceNodeKey sliceKey) {
        Option.option(sliceStates().get(sliceKey)).onPresent(state -> executeStuckRemediation(sliceKey, state));
    }

    private void executeStuckRemediation(SliceNodeKey sliceKey, SliceState state) {
        switch (state) {
            case LOADING, ACTIVATING, ROUTING -> resetStuckLoadingSlice(sliceKey, state);
            case DEACTIVATING, UNLOADING -> forceRemoveStuckSlice(sliceKey, state);
            default -> {}
        }
    }

    private void resetStuckLoadingSlice(SliceNodeKey sliceKey, SliceState state) {
        transitionalStateTimestamps().remove(sliceKey);
        log.warn("Force-resetting stuck {} slice {} on {} — issuing UNLOAD",
                 state,
                 sliceKey.artifact(),
                 sliceKey.nodeId());
        sliceStates().remove(sliceKey);
        active.issueUnloadCommand(sliceKey);
    }

    private void forceRemoveStuckSlice(SliceNodeKey sliceKey, SliceState state) {
        transitionalStateTimestamps().remove(sliceKey);
        log.warn("Force-removing stuck {} slice {} on {} from DHT", state, sliceKey.artifact(), sliceKey.nodeId());
        sliceStates().remove(sliceKey);
        active.removeNodeArtifactKey(sliceKey);
    }

    private Map<SliceNodeKey, SliceState> sliceStates() {
        return active.sliceStates();
    }

    private Map<SliceNodeKey, Long> transitionalStateTimestamps() {
        return active.transitionalStateTimestamps();
    }
}
