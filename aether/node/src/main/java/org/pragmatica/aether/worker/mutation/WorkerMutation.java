// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.mutation;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.Message;
import org.pragmatica.serialization.Codec;
import org.pragmatica.serialization.CodecFor;


@Codec
@CodecFor(KVCommand.class)
public record WorkerMutation(NodeId sourceWorker, String correlationId, KVCommand<AetherKey> command) implements Message.Wired {
    public static WorkerMutation workerMutation(NodeId sourceWorker,
                                                String correlationId,
                                                KVCommand<AetherKey> command) {
        return new WorkerMutation(sourceWorker, correlationId, command);
    }
}
