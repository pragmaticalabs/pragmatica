// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

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

    record unused() implements Judgments {}
}
