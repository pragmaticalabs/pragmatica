// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterIncarnationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterIncarnationValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.lang.Contract;


/// The committed cluster incarnation as the last KV notification for [ClusterIncarnationKey] delivered it
/// (#1529), readable without the `KVStore` monitor. It is not a second source: it only mirrors
/// [ClusterIncarnation#current], one notification behind at most (a snapshot install shows up once its
/// notifications are replayed).
///
/// It exists for the QUIC event loop. An inbound attach reaches the connectivity reporter synchronously
/// from `QuicClusterServer`'s Hello handler, and [ClusterIncarnation#current] reads through the
/// synchronized `KVStore.getTyped`, whose monitor a whole snapshot restore holds — a read there would stall
/// I/O for every channel on that loop.
record ObservedIncarnation(AtomicLong incarnation) {
    static ObservedIncarnation observedIncarnation(long seed) {
        return new ObservedIncarnation(new AtomicLong(seed));
    }

    long current() {
        return incarnation.get();
    }

    @Contract
    void onPut(ValuePut<ClusterIncarnationKey, ClusterIncarnationValue> put) {
        incarnation.set(put.cause().value().incarnation());
    }

    /// A restore removes the incarnation and puts the new one in the same batch; between the two the
    /// committed answer is [ClusterIncarnation#NONE], and so is this one.
    @Contract
    void onRemove(ValueRemove<ClusterIncarnationKey, ClusterIncarnationValue> remove) {
        incarnation.set(ClusterIncarnation.NONE);
    }
}
