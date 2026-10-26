// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.function.Function;

import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterIncarnationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterIncarnationValue;
import org.pragmatica.cluster.state.kvstore.IncarnationSource;
import org.pragmatica.cluster.state.kvstore.IncarnationTag;
import org.pragmatica.cluster.state.kvstore.StructuredKey;


/// The production reader of the cluster incarnation for the worker replica feed's commit ring (W-OBS section 2.1 R-02): the tag is the
/// committed [ClusterIncarnationValue]'s `(incarnation, incarnationId)`, read from storage before and after every batch, and
/// [IncarnationTag#NONE] while no incarnation is committed (before genesis, or a declare-genesis whose second transaction was refused). A
/// `NONE` tag matches nothing, so a node without an incarnation serves no ring.
public sealed interface ReplicaIncarnationSource {
    static IncarnationSource replicaIncarnationSource() {
        return new IncarnationSource() {
            @Override
            public IncarnationTag read(Function<StructuredKey, Object> lookup) {
                return lookup.apply(ClusterIncarnationKey.clusterIncarnationKey()) instanceof ClusterIncarnationValue committed
                       ? IncarnationTag.incarnationTag(committed.incarnation(), committed.incarnationId())
                       : IncarnationTag.NONE;
            }

            @Override
            public boolean isIncarnationKey(StructuredKey key) {
                return key instanceof ClusterIncarnationKey;
            }
        };
    }

    record unused() implements ReplicaIncarnationSource {}
}
