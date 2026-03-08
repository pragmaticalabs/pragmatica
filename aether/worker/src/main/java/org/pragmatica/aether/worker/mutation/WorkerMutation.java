package org.pragmatica.aether.worker.mutation;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.Message;
import org.pragmatica.serialization.Codec;

/// A mutation originating from a worker node, to be forwarded to core for consensus.
///
/// @param sourceWorker   the worker that originated this mutation
/// @param correlationId  unique ID for tracking the mutation through the pipeline
/// @param command        the KV command to apply
@Codec
public record WorkerMutation(NodeId sourceWorker,
                             String correlationId,
                             KVCommand<AetherKey> command) implements Message.Wired {
    public static WorkerMutation workerMutation(NodeId sourceWorker,
                                                String correlationId,
                                                KVCommand<AetherKey> command) {
        return new WorkerMutation(sourceWorker, correlationId, command);
    }
}
