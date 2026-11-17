// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.List;
import java.util.function.Function;

import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.stream.NodeLease;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.utility.warning.OperatorWarningSink;

/// Test-scope bridge to the package-private production lease wiring ([StreamAssembly#nodeLease]), so a test in another package (the FSM2
/// harness lives in `node.deployment`) drives the wiring production runs, not a copy of it.
public final class StreamAssemblyLeaseWiring {
    private StreamAssemblyLeaseWiring() {}

    public static NodeLease nodeLease(NodeId self,
                                      StreamingConfig streaming,
                                      KVStore<AetherKey, AetherValue> kvStore,
                                      Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier,
                                      OperatorWarningSink sink,
                                      List<MessageRouter.Entry<?>> entries) {
        return StreamAssembly.nodeLease(self, streaming, kvStore, applier, sink, entries);
    }
}
