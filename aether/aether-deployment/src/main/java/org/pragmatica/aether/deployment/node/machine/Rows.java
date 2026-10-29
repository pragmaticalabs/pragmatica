// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import java.util.Map;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.lang.Option;


/// Reading observed rows: which of the node's own rows is current, which states are live, and whether another live
/// current replica hosts an artifact (spec R-1.6.2, R-3.4.5).
sealed interface Rows {
    /// The node's own committed row, read as absent when it is stale on positive evidence (R-1.6.2, C8).
    static Option<SliceObservedValue> current(NodeFsmConfig config, InstanceRecord record) {
        return record.observed()
                     .filter(value -> !config.isStale(value.incarnation().bootToken()));
    }

    static Option<SliceObservedState> stateOf(Option<SliceObservedValue> row) {
        return row.map(SliceObservedValue::state);
    }

    /// The positive list of live states (spec R-1.6.2): counted, serving or on the way to either.
    static boolean isLive(SliceObservedState state) {
        return switch (state) {
            case LOADING, LOADED, ACTIVATING, ACTIVE, DEACTIVATING, UNKNOWN -> true;
            case FAILED, UNLOADING, UNLOADED -> false;
        };
    }

    /// Another node holds a live row for `artifact`, current by the snapshot's own token knowledge (C8: an unknown
    /// token reads as current). The replica index holds other nodes' rows only. The last-replica guards of B-145 and Q10
    /// consult this.
    static boolean hostedElsewhere(NodeFsmModel model, Artifact artifact) {
        return model.replicas()
                    .getOrDefault(artifact,
                                  Map.of())
                    .values()
                    .stream()
                    .anyMatch(Rows::isLiveReplica);
    }

    private static boolean isLiveReplica(PeerRow row) {
        return ! row.isStale() && isLive(row.value().state());
    }

    record unused() implements Rows {}
}
