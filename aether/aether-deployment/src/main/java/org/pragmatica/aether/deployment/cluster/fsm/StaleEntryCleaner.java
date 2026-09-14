// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.deployment.CommittedSliceTarget;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentState.Active;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Stale-entry cleanup seam extracted (move-only) from {@link Active}. Diffs KV-Store node-routes,
/// slice-state, and node-artifact entries against the nodes that still own placements — the
/// resolved core membership plus the registered workers ({@link #placementNodes()}) — and removes
/// entries for departed nodes; also unloads/removes orphaned slice entries that have no matching
/// blueprint. All cleanups gate on {@link Active#coreMembershipResolved()} so a sweep racing the
/// membership wiring never mass-classifies KV-known members as departed.
record StaleEntryCleaner(Active active) {
    private static final Logger log = LoggerFactory.getLogger(StaleEntryCleaner.class);

    // Fire-and-forget cleanup sweep: only callers are the reconcile/KV-rebuild paths in
    // ClusterDeploymentState (rebuildStateFromKVStore / deferredTopologyRecheck), which ignore the
    // outcome. The KV apply already reports its own failure via .onFailure(log.error) below, so void
    // is the correct contract — propagating Promise<Unit> would just be discarded one level up.
    @Contract
    void cleanupStaleNodeRoutes() {
        if (!active.coreMembershipResolved()) {
            return;
        }

        var currentNodes = placementNodes();
        var commands = new ArrayList<KVCommand<AetherKey>>();

        active.ctx()
              .kvStore()
              .forEach(NodeRoutesKey.class,
                       AetherValue.NodeRoutesValue.class,
                       (key, _) -> collectStaleNodeRoutesKey(commands, key, currentNodes));
        if (!commands.isEmpty()) {
            log.debug("Cleaning up {} stale node-routes entries", commands.size());
            active.ctx()
                  .cluster()
                  .apply(commands)
                  .onFailure(cause -> log.error("Failed to clean up stale node routes: {}",
                                                cause.message()));
        }
    }

    /// The nodes whose KV rows are NOT stale (#850): {@link Active#placementNodes()} — counted core
    /// members plus registered workers. `activeNodes()` alone is core-scoped by construction and
    /// classified every LIVE worker's rows as stale on every reconcile tick.
    private Set<NodeId> placementNodes() {
        return active.placementNodes();
    }

    private void collectStaleNodeRoutesKey(List<KVCommand<AetherKey>> commands,
                                           NodeRoutesKey key,
                                           Set<NodeId> currentNodes) {
        if (!currentNodes.contains(key.nodeId())) {
            commands.add(new KVCommand.Remove<>(key));
        }
    }

    // Fire-and-forget cleanup sweep (see cleanupStaleNodeRoutes): callers ignore the outcome; the
    // KV apply reports its own failure via .onFailure(log.error).
    @Contract
    void cleanupStaleSliceEntries() {
        if (!active.coreMembershipResolved()) {
            return;
        }

        var currentNodes = placementNodes();
        var staleKeys = active.sliceStates()
                              .keySet()
                              .stream()
                              .filter(key -> !currentNodes.contains(key.nodeId()))
                              .toList();

        if (staleKeys.isEmpty()) {
            return;
        }

        staleKeys.forEach(active.sliceStates()::remove);
        List<KVCommand<AetherKey>> commands = staleKeys.stream()
                                                       .<KVCommand<AetherKey>> map(KVCommand.Remove::new)
                                                       .toList();

        log.info("Cleaning up {} stale slice entries", staleKeys.size());
        active.ctx()
              .cluster()
              .apply(commands)
              .onFailure(cause -> log.error("Failed to clean up stale slice entries: {}",
                                            cause.message()));
    }

    // Fire-and-forget cleanup sweep (see cleanupStaleNodeRoutes): callers ignore the outcome; the
    // KV apply reports its own failure via .onFailure(log.error).
    @Contract
    void cleanupStaleNodeArtifactEntries() {
        if (!active.coreMembershipResolved()) {
            return;
        }

        var currentNodes = placementNodes();
        var staleKeys = new ArrayList<NodeArtifactKey>();

        active.ctx()
              .kvStore()
              .forEach(NodeArtifactKey.class,
                       NodeArtifactValue.class,
                       (key, _) -> collectStaleNodeArtifactKey(staleKeys, key, currentNodes));
        if (staleKeys.isEmpty()) {
            return;
        }

        List<KVCommand<AetherKey>> commands = staleKeys.stream()
                                                       .<KVCommand<AetherKey>> map(KVCommand.Remove::new)
                                                       .toList();

        log.info("Cleaning up {} stale node-artifact entries", staleKeys.size());
        active.ctx()
              .cluster()
              .apply(commands)
              .onFailure(cause -> log.error("Failed to clean up stale node-artifact entries: {}",
                                            cause.message()));
    }

    private void collectStaleNodeArtifactKey(List<NodeArtifactKey> result,
                                             NodeArtifactKey key,
                                             Set<NodeId> currentNodes) {
        if (!currentNodes.contains(key.nodeId())) {
            result.add(key);
        }
    }

    // Fire-and-forget cleanup sweep (see cleanupStaleNodeRoutes): callers ignore the outcome;
    // issueUnloadCommand / removeNodeArtifactKey each report their own failure internally.
    //
    // #1068: the sweep walks the COMMITTED `NodeArtifactKey` entries, not `active.sliceStates()`, and
    // re-issues on every tick until the key is absent from the store. The projection walk dropped the
    // entry BEFORE issuing the UNLOAD, so one consensus timeout (measured: 30s, during a rollback in
    // CI run 34772700962) left the key in the store for good — and a node then healed its ACTIVE
    // claim into a running slice with no owning blueprint. Keys of departed nodes are left to
    // cleanupStaleNodeArtifactEntries, which removes them outright.
    @Contract
    void cleanupOrphanedSliceEntries() {
        if (!active.coreMembershipResolved()) {
            return;
        }

        var currentNodes = new HashSet<>(active.activeNodes());
        var orphanedEntries = new ArrayList<Map.Entry<SliceNodeKey, SliceState>>();

        active.ctx()
              .kvStore()
              .forEach(NodeArtifactKey.class,
                       NodeArtifactValue.class,
                       (key, value) -> collectOrphanedSliceEntry(orphanedEntries, key, value, currentNodes));
        if (orphanedEntries.isEmpty()) {
            return;
        }

        for (var entry : orphanedEntries) {
            var key = entry.getKey();
            var state = entry.getValue();

            active.sliceStates().remove(key);
            // UNLOADING is NOT left to the node (measured in CI run 34788864919: sovr-2's own
            // `deleteSliceNodeKey` gave up after CONSENSUS_MAX_RETRIES under a timing-out consensus and
            // the key sat at UNLOADING for the whole 4-minute wait). A node's chain removes its key at
            // most twice; this sweep is what removes it until it is gone. The duplicate Remove when the
            // node's own succeeds is harmless.
            if (state == SliceState.UNLOAD || state == SliceState.UNLOADING) {
                active.removeNodeArtifactKey(key);
            } else {
                active.issueUnloadCommand(key);
            }
        }

        log.info("Cleaning up {} orphaned slice entries (no committed slice target)", orphanedEntries.size());
    }

    /// CONFIRM AGAINST THE AUTHORITY BEFORE DESTROYING. `active.blueprints()` is a leader-local
    /// PROJECTION rebuilt only on `Active` entry; nothing re-derives it during a term. A single missed
    /// `AppBlueprintPut` — or a rename path that clears the entry and loses the re-put — therefore made
    /// every slice of that artifact look orphaned for the leader's whole term, and this sweep ran each
    /// reconcile tick and force-UNLOADed them cluster-wide. The operator saw healthy slices unloading
    /// under "orphaned slice entries (no matching blueprint)".
    ///
    /// Same defect shape as the stuck-slice remediator (fixed 2026-08-16), which judged a slice by a
    /// projection and destroyed one that had been serving traffic 35s earlier — and the same fail-safe
    /// direction: the committed `SliceTargetValue` is the authority, and the projection is not consulted
    /// at all (#1068). A slice is orphaned exactly when [CommittedSliceTarget] permits nothing for its
    /// version — the VERSION is part of the check, so a target that has moved to a newer version means
    /// this artifact is superseded and genuinely should be unloaded, unless a rolling update's routing
    /// entry still names it.
    private void collectOrphanedSliceEntry(List<Map.Entry<SliceNodeKey, SliceState>> result,
                                           NodeArtifactKey key,
                                           NodeArtifactValue value,
                                           Set<NodeId> currentNodes) {
        if (!currentNodes.contains(key.nodeId())) {
            return;
        }

        if (CommittedSliceTarget.permits(active.ctx().kvStore(),
                                         key.artifact())) {
            return;
        }

        result.add(Map.entry(SliceNodeKey.sliceNodeKey(key.artifact(), key.nodeId()),
                             value.state()));
    }
}
