// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterIncarnationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterIncarnationValue;
import org.pragmatica.cluster.state.kvstore.KVNotificationRouter;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.lang.Contract;
import org.pragmatica.messaging.MessageRouter;


/// The committed cluster incarnation as the last KV notification for [ClusterIncarnationKey] delivered it
/// (#1529), readable without the `KVStore` monitor. It is not a second source: it only mirrors
/// [ClusterIncarnation#current], one notification behind at most (a snapshot install shows up once its
/// notifications are replayed).
///
/// It exists for network threads. The node's epoch suppliers (`AetherNode.epochSources`) are read inline on
/// the QUIC event loop (an inbound attach, a reconnect, a routed request) and on the SWIM UDP loop (every
/// health hint), and [ClusterIncarnation#current] reads through the synchronized `KVStore.getTyped`, whose
/// monitor a whole snapshot restore holds — a read there would stall I/O for every channel on that loop.
public record ObservedIncarnation(AtomicLong incarnation) {
    public static ObservedIncarnation observedIncarnation(long seed) {
        return new ObservedIncarnation(new AtomicLong(seed));
    }

    public long current() {
        return incarnation.get();
    }

    /// The notifications that keep this mirror current: every commit that changes the incarnation (genesis,
    /// restore, declare-genesis) and every replay after a snapshot install.
    public List<MessageRouter.Entry<?>> routeEntries() {
        return KVNotificationRouter.<AetherKey, AetherValue> builder(AetherKey.class)
                                   .onPut(ClusterIncarnationKey.class, this::onPut)
                                   .onRemove(ClusterIncarnationKey.class, this::onRemove)
                                   .build()
                                   .asRouteEntries();
    }

    @Contract
    public void onPut(ValuePut<ClusterIncarnationKey, ClusterIncarnationValue> put) {
        incarnation.set(put.cause().value().incarnation());
    }

    /// A restore removes the incarnation and puts the new one in the same batch; between the two the
    /// committed answer is [ClusterIncarnation#NONE], and so is this one.
    @Contract
    public void onRemove(ValueRemove<ClusterIncarnationKey, ClusterIncarnationValue> remove) {
        incarnation.set(ClusterIncarnation.NONE);
    }
}
