// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.api.ManagementServer;
import org.pragmatica.aether.metrics.ClusterSyncPongSignalFan;
import org.pragmatica.aether.node.assembly.DrainAssembly;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;


/// Boot-test observation seam: hands a test the instances `assembleNode` actually passed to the quorum-loss unit and to
/// the management server, so a wiring test can assert instance identity at runtime instead of reading source text.
/// Assembly runs synchronously on the booting thread, so the capture is thread-scoped; with none open (production)
/// every call is a no-op.
final class AssemblyObserver {
    private static final ThreadLocal<Capture> ACTIVE = new ThreadLocal<>();

    private AssemblyObserver() {}

    /// Opens a capture on the calling thread; close it to end the observation.
    static Capture capture() {
        var capture = new Capture();

        ACTIVE.set(capture);

        return capture;
    }

    @Contract
    static void quorumLoss(DrainAssembly.QuorumLossInputs inputs,
                           DrainAssembly.QuorumLossDrain drain,
                           KVStore<AetherKey, AetherValue> kvStore,
                           ClusterSyncPongSignalFan pongSignalFan) {
        Option.option(ACTIVE.get()).onPresent(capture -> capture.quorumLoss.set(new QuorumLoss(inputs,
                                                                                               drain,
                                                                                               kvStore,
                                                                                               pongSignalFan)));
    }

    @Contract
    static void management(ManagementServer server) {
        Option.option(ACTIVE.get()).onPresent(capture -> capture.management.set(server));
    }

    /// What the quorum-loss unit was handed and returned, with the node's own `kvStore` and `pongSignalFan` locals
    /// beside it, so a test can compare the inputs against the instances they were meant to carry.
    record QuorumLoss(DrainAssembly.QuorumLossInputs inputs,
                      DrainAssembly.QuorumLossDrain drain,
                      KVStore<AetherKey, AetherValue> kvStore,
                      ClusterSyncPongSignalFan pongSignalFan) {}

    static final class Capture implements AutoCloseable {
        private final AtomicReference<QuorumLoss> quorumLoss = new AtomicReference<>();
        private final AtomicReference<ManagementServer> management = new AtomicReference<>();

        QuorumLoss quorumLoss() {
            return quorumLoss.get();
        }

        ManagementServer management() {
            return management.get();
        }

        @Override
        @Contract
        public void close() {
            ACTIVE.remove();
        }
    }
}
