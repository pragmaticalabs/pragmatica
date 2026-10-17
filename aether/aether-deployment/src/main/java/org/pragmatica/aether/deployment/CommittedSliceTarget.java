// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.VersionRoutingKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.VersionRoutingValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;


/// #1068 — the ONE question every start of a slice instance is gated on: does the COMMITTED KV
/// store still want this exact version running?
///
/// A rolled-back blueprint removes its `SliceTargetKey`; its UNLOAD commands are separate consensus
/// rounds and can fail (measured: `Consensus apply timed out after 30000ms` during a rollback),
/// leaving a `NodeArtifactKey` behind that the node's KV-convergence redeploy then dutifully loaded
/// and served — with no owning blueprint, on a stream address the rolled-back blueprint never
/// committed. The node-side gate (`NodeDeploymentState.Active`), the leader's ACTIVATE substitution
/// and the orphan sweep (`StaleEntryCleaner`) all read THIS predicate from the committed store, never
/// from a leader-local projection, so a node that applied the rollback in Rabia's total order cannot
/// start that version again: the removal precedes any later start command in the same order.
///
/// A version is permitted while the committed target names it, or — during a rolling update — while a
/// `VersionRoutingKey` for the base names it as the old or the new version: the target already carries
/// the NEW version at that point and the old instances are legitimately still serving. An absent
/// target permits nothing; a routing entry without a target is not a deploy.
public sealed interface CommittedSliceTarget {
    static boolean permits(KVStore<AetherKey, AetherValue> kvStore, Artifact artifact) {
        var base = artifact.base();
        var version = artifact.version();

        return target(kvStore, base).map(target -> target.currentVersion()
                                                         .equals(version) || routingNames(kvStore, base, version))
                     .or(false);
    }

    private static Option<SliceTargetValue> target(KVStore<AetherKey, AetherValue> kvStore, ArtifactBase base) {
        return kvStore.get(SliceTargetKey.sliceTargetKey(base))
                      .filter(SliceTargetValue.class::isInstance)
                      .map(SliceTargetValue.class::cast);
    }

    private static boolean routingNames(KVStore<AetherKey, AetherValue> kvStore, ArtifactBase base, Version version) {
        return kvStore.get(VersionRoutingKey.versionRoutingKey(base))
                      .filter(VersionRoutingValue.class::isInstance)
                      .map(VersionRoutingValue.class::cast)
                      .map(routing -> routing.oldVersion()
                                             .equals(version) || routing.newVersion()
                                                                        .equals(version))
                      .or(false);
    }

    record unused() implements CommittedSliceTarget {}
}
