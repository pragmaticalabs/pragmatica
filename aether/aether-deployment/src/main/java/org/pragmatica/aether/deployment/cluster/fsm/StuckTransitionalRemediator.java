// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import java.util.Map;

import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentState.Active;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
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
        Option.option(sliceStates().get(sliceKey)).onPresent(state -> remediateAgainstCommittedState(sliceKey, state));
    }

    /// CONFIRM AGAINST THE AUTHORITY BEFORE DESTROYING. `sliceStates()` is an in-memory PROJECTION on the
    /// leader, not the authority — the authority is the committed `NodeArtifactValue` in the KV-Store. When
    /// the projection misses a transition the slice looks stuck forever, because the map it is judged by is
    /// the same map that failed to advance, and nothing here ever re-reads the KV.
    ///
    /// Measured 2026-08-16 (02y-stream-crash, remote cluster B): node-2's activation chain SUCCEEDED and the
    /// slice served traffic — its own log shows `test-stream-multipart-stream-slice/publish depth=0
    /// duration=25.591363ms` at 23:15:15 — yet the leader's projection still read ACTIVATING and force-UNLOADed
    /// it 35s later, at 23:15:50. A healthy, serving slice was destroyed on the strength of a stale view. The
    /// activation chain's own 120s guard was never involved: `Promise.timeout` is a deadline on that promise
    /// instance, and the chain had already resolved successfully.
    ///
    /// This is the same shape as #593's `SYNCING` seed and #590's frozen `memberCount` — a local or
    /// self-reported value read as observed truth. Everything looks healthy right up until something acts on it.
    ///
    /// FAIL-SAFE DIRECTION: remediation is skipped ONLY when the KV positively reports a SETTLED
    /// (non-transitional) state. An absent key, an unreadable value, or a KV that agrees the slice is still
    /// transitional all fall through to the original behaviour — so a genuinely stuck slice is still
    /// recovered, and this can only ever spare a slice the cluster has already committed as settled.
    private void remediateAgainstCommittedState(SliceNodeKey sliceKey, SliceState state) {
        var settled = committedState(sliceKey).filter(committed -> !committed.isTransitional());

        if (settled.isPresent()) {
            settled.onPresent(committed -> adoptCommittedState(sliceKey, committed));

            return;
        }

        executeStuckRemediation(sliceKey, state);
    }

    /// The committed state for this slice, straight from the KV-Store — the authority the in-memory
    /// projection is supposed to mirror.
    private Option<SliceState> committedState(SliceNodeKey sliceKey) {
        return active.ctx()
                     .kvStore()
                     .get(NodeArtifactKey.nodeArtifactKey(sliceKey.nodeId(),
                                                          sliceKey.artifact()))
                     .filter(NodeArtifactValue.class::isInstance)
                     .map(NodeArtifactValue.class::cast)
                     .map(NodeArtifactValue::state);
    }

    /// Repair the stale projection instead of destroying the slice, and say so loudly: a divergence between
    /// the leader's view and the committed state is itself the defect worth seeing in the log.
    private void adoptCommittedState(SliceNodeKey sliceKey, SliceState committed) {
        log.warn("NOT remediating {} on {}: the committed KV state is {}, so the in-memory view was stale, not the slice stuck — adopting the committed state instead of unloading a slice the cluster considers settled",
                 sliceKey.artifact(),
                 sliceKey.nodeId(),
                 committed);
        transitionalStateTimestamps().remove(sliceKey);
        sliceStates().put(sliceKey, committed);
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
