// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
package org.pragmatica.aether.worker.metadata;

import java.util.function.Function;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.serialization.SliceCodec;


/// Production assembly facade; role is immutable and server reads require committed core readiness.
public record WorkerMetadataChannel(boolean worker,
                                    Supplier<Boolean> coreReady,
                                    WorkerMetadataServer server,
                                    WorkerMetadataClient client) {
    public java.util.Map<String, Long> resourceMetrics() {
        var metrics = new java.util.HashMap<>(server.resourceMetrics());

        metrics.putAll(client.resourceMetrics());

        return java.util.Map.copyOf(metrics);
    }

    public static WorkerMetadataChannel workerMetadataChannel(NodeId self,
                                                              boolean worker,
                                                              KVStore<AetherKey, AetherValue> store,
                                                              SliceCodec codec,
                                                              BiConsumer<NodeId, ProtocolMessage> send,
                                                              Supplier<Boolean> coreReady,
                                                              Predicate<NodeId> eligibleWorker,
                                                              Predicate<NodeId> eligibleCore,
                                                              Supplier<Set<NodeId>> cores,
                                                              Function<Set<NodeId>, List<NodeInfo>> directory,
                                                              Function<List<NodeInfo>, Result<Unit>> directoryReceived,
                                                              Consumer<List<NodeInfo>> endpointDirectoryReceived,
                                                              Runnable projectionReady,
                                                              Consumer<String> report,
                                                              BiConsumer<NodeId, String> reportRejection,
                                                              WorkerMetadataLimits limits) {
        return new WorkerMetadataChannel(worker,
                                         coreReady,
                                         new WorkerMetadataServer(self,
                                                                  store,
                                                                  codec,
                                                                  send,
                                                                  eligibleWorker,
                                                                  cores,
                                                                  directory,
                                                                  limits,
                                                                  reportRejection),
                                         new WorkerMetadataClient(self,
                                                                  store,
                                                                  codec,
                                                                  cores,
                                                                  eligibleCore,
                                                                  send,
                                                                  directoryReceived,
                                                                  endpointDirectoryReceived,
                                                                  projectionReady,
                                                                  report,
                                                                  limits));
    }

    public Unit onManifestRequest(WorkerMetadataMessage.ManifestRequest request) {
        if (!worker && coreReady.get()) {
            server.onManifestRequest(request);
        }

        return Unit.unit();
    }

    public Unit onChunkRequest(WorkerMetadataMessage.ChunkRequest request) {
        if (!worker && coreReady.get()) {
            server.onChunkRequest(request);
        }

        return Unit.unit();
    }

    public Unit onManifest(WorkerMetadataMessage.Manifest response) {
        if (worker) {
            client.onManifest(response);
        }

        return Unit.unit();
    }

    public Unit onChunk(WorkerMetadataMessage.Chunk response) {
        if (worker) {
            client.onChunk(response);
        }

        return Unit.unit();
    }

    public Unit onValuePut(KVStoreNotification.ValuePut<?, ?> notification) {
        if (!worker) {
            server.put(notification.cause().key(),
                       notification.cause().value());
        }

        return Unit.unit();
    }

    public Unit onValueRemove(KVStoreNotification.ValueRemove<?, ?> notification) {
        if (!worker) {
            server.remove(notification.cause().key());
        }

        return Unit.unit();
    }

    public Unit tick() {
        if (worker) {
            client.tick();
        }

        return Unit.unit();
    }

    public boolean hasFreshProjection() {
        return ! worker || client.hasFreshProjection();
    }

    public Unit onStateRestored() {
        if (!worker) {
            server.onStateRestored();
        }

        return Unit.unit();
    }
}
