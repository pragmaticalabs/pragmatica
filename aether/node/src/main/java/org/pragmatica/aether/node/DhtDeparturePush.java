// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.function.Supplier;

import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.dht.DHTRebalancer;
import org.pragmatica.dht.DeparturePushObserver;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// The DHT half of a draining node's departure push (#427), as `AetherNode` assembles it (#1818).
///
/// The push excludes every node the leader commanded to drain alongside this one. That set is read
/// from the collector WHEN the push runs, not when the supplier is built: the leader ping carrying the
/// drain command records its global `drainNodes` before the drain handler fires, so the push sees every
/// node commanded in the same ping. The set can only ever ADD targets — a node wrongly in it is skipped
/// as a target and not counted as a surviving holder, so the push goes one node further along the ring
/// instead, or, when the exclusion exhausts the ring, to every remaining node; a stale entry can
/// therefore cost an extra copy, never a missing one. A cleared set is the pre-#1818 behaviour: a
/// co-drainer missing from it can still absorb the push or be counted as a survivor.
public sealed interface DhtDeparturePush {
    /// The departure push the drain procedure invokes once at DRAINING.
    static Supplier<Promise<Unit>> dhtDeparturePush(DHTRebalancer rebalancer,
                                                    ClusterSyncCollector drainCommands,
                                                    Supplier<DeparturePushObserver> observer) {
        return () -> rebalancer.pushOnDeparture(drainCommands.commandedDrainNodes(), observer.get());
    }

    record unused() implements DhtDeparturePush {}
}
