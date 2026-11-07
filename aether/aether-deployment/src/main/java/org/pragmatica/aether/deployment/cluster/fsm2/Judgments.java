// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactJudgmentKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;


/// The durable judgments as notified (spec 4.2): the committed `ArtifactJudgmentValue` is a fact the reconcile reads
/// (the permanent mark, the retry counts, the exclusions). Every other key family the FSM does not react to is indexed
/// and otherwise ignored.
public sealed interface Judgments {
    static ClusterModel onPut(ClusterModel m, Ctx ctx, AetherKey key, AetherValue value) {
        Indexer.indexPut(m, ctx, key, value);

        return key instanceof ArtifactJudgmentKey
               ? Reconcile.request(m, ctx)
               : m;
    }

    static ClusterModel onRemove(ClusterModel m, Ctx ctx, AetherKey key) {
        Indexer.indexRemove(m, ctx, key);

        return key instanceof ArtifactJudgmentKey
               ? Reconcile.request(m, ctx)
               : m;
    }

    /// D.6 rows 18/19: this leader cleared (or is clearing) an exclusion of `artifact`, for `reason`. The recovery is raised by
    /// [#exclusionsEnded] once the committed judgment holds no exclusion, with the reason of the last clear.
    static ClusterModel exclusionEnding(ClusterModel m, Artifact artifact, String reason) {
        m.exclusionEndReasons = m.exclusionEndReasons.put(artifact, reason);

        return m;
    }

    /// Whether the committed judgment of `artifact` excludes at least one node.
    static boolean excludes(ClusterModel m, Artifact artifact) {
        return m.judgments.get(artifact)
                          .map(judgment -> !judgment.unresponsive()
                                                    .isEmpty())
                          .or(false);
    }

    /// D.6 rows 18/19 (CTO ruling on #2831): `slice-unresponsive` is raised per excluded node with the artifact as its subject, so
    /// `slice-unresponsive-expired` is raised once, when the artifact's committed exclusions go from some to none: the last excluded node
    /// expired or left (subject-gone), or the judgment itself went. Read from committed state, it is raised once however many paths
    /// cleared the same exclusion (a departure and the C17b removal of the node's rows). Called by the indexer, so every path that indexes a
    /// judgment (a notification, the values a refusal carries) sees the transition exactly once; the rebuild starts from an empty model and
    /// sees none.
    static ClusterModel exclusionsEnded(ClusterModel m, Ctx ctx, Artifact artifact, boolean excluding, String message) {
        if (excluding && !excludes(m, artifact)) {
            m.exclusionEndReasons = m.exclusionEndReasons.remove(artifact);
            ctx.warn(Recoveries.SLICE_UNRESPONSIVE_EXPIRED, artifact.asString(), message);
        }

        return m;
    }

    record unused() implements Judgments {}
}
