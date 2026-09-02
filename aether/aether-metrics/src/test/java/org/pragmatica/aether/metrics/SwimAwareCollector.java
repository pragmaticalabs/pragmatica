// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.pragmatica.consensus.NodeId;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;


/// `ClusterSyncCollector` double whose SWIM-alive predicate is fixed at construction, so the
/// owner-side `ClusterSyncContext.emitPingTimeoutIfExceeded` guard can be driven deterministically
/// without standing up real SWIM. ALIVE peers are exempt from the unreachable hint; not-alive peers
/// are fed to the wired reporter, which is how the ping-timeout path is observed. Shared by the
/// FSM-level and scheduler-level ping-timeout suites — `NoopClusterSyncCollector` reports every
/// peer ALIVE, which short-circuits that path before it does anything.
public final class SwimAwareCollector extends NoopClusterSyncCollector {
    private final Set<NodeId> alivePeers;
    private final AtomicReference<Consumer<NodeId>> unreachableReporter = new AtomicReference<>(_ -> {});

    public SwimAwareCollector(Set<NodeId> alivePeers) {
        this.alivePeers = Set.copyOf(alivePeers);
    }

    @Override
    public boolean peerLocallyAlive(NodeId peer) {
        return alivePeers.contains(peer);
    }

    @Override
    public void setUnreachableReporter(Consumer<NodeId> reporter) {
        unreachableReporter.set(reporter == null
                                ? _ -> {}
                                : reporter);
    }

    @Override
    public void reportUnreachable(NodeId peer) {
        unreachableReporter.get()
                           .accept(peer);
    }
}
