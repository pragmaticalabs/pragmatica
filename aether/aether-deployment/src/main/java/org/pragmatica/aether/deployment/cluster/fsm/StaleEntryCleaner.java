// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentState.Active;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Stale-entry cleanup seam extracted (move-only) from {@link Active}. Diffs KV-Store node-routes,
/// slice-state, and node-artifact entries against the resolved core membership and removes entries
/// for departed nodes; also unloads/removes orphaned slice entries that have no matching blueprint.
/// All cleanups gate on {@link Active#coreMembershipResolved()} so a sweep racing the membership
/// wiring never mass-classifies KV-known members as departed.
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

        var currentNodes = new HashSet<>(active.activeNodes());
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

        var currentNodes = new HashSet<>(active.activeNodes());
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

        var currentNodes = new HashSet<>(active.activeNodes());
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
    @Contract
    void cleanupOrphanedSliceEntries() {
        if (!active.coreMembershipResolved()) {
            return;
        }

        var orphanedEntries = active.sliceStates()
                                    .entrySet()
                                    .stream()
                                    .filter(entry -> !active.blueprints()
                                                            .containsKey(entry.getKey().artifact()))
                                    .filter(entry -> committedTargetAbsent(entry.getKey().artifact()))
                                    .toList();

        if (orphanedEntries.isEmpty()) {
            return;
        }

        for (var entry : orphanedEntries) {
            var key = entry.getKey();
            var state = entry.getValue();

            active.sliceStates().remove(key);
            if (state == SliceState.UNLOAD || state == SliceState.UNLOADING) {
                active.removeNodeArtifactKey(key);
            } else {
                active.issueUnloadCommand(key);
            }
        }

        log.info("Cleaning up {} orphaned slice entries (no matching blueprint)", orphanedEntries.size());
    }

    /// CONFIRM AGAINST THE AUTHORITY BEFORE DESTROYING. `active.blueprints()` is a leader-local
    /// PROJECTION rebuilt only on `Active` entry; nothing re-derives it during a term. A single missed
    /// `AppBlueprintPut` — or a rename path that clears the entry and loses the re-put — therefore makes
    /// every slice of that artifact look orphaned for the leader's whole term, and this sweep runs each
    /// reconcile tick and force-UNLOADs them cluster-wide. The operator sees healthy slices unloading
    /// under "orphaned slice entries (no matching blueprint)".
    ///
    /// Same defect shape as the stuck-slice remediator (fixed 2026-08-16), which judged a slice by a
    /// projection and destroyed one that had been serving traffic 35s earlier — and the same fail-safe
    /// direction: the committed `SliceTargetValue` is the authority, and a slice is treated as orphaned
    /// only when the KV also has nothing for it. An absent or unreadable target falls through to the
    /// previous behaviour, so a genuinely orphaned slice is still cleaned up.
    ///
    /// The VERSION is part of the check: a target that has moved to a newer version means this artifact
    /// is superseded and genuinely should be unloaded, so a stale version is still an orphan.
    private boolean committedTargetAbsent(Artifact artifact) {
        return active.ctx()
                     .kvStore()
                     .get(SliceTargetKey.sliceTargetKey(artifact.base()))
                     .filter(SliceTargetValue.class::isInstance)
                     .map(SliceTargetValue.class::cast)
                     .filter(target -> target.currentVersion()
                                             .equals(artifact.version()))
                     .isEmpty();
    }
}
