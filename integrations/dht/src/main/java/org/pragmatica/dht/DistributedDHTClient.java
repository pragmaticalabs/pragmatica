/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.dht;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.utility.KSUID;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.pragmatica.lang.Unit.unit;

/// Distributed DHT client with quorum-based reads and writes.
/// Routes operations to responsible nodes via consistent hashing and ClusterNetwork.
public final class DistributedDHTClient implements DHTClient {
    private final DHTNode node;
    private final ClusterNetwork network;
    private final DHTConfig config;

    /// Pending operations indexed by correlation ID.
    private final ConcurrentHashMap<String, PendingOperation<?>> pendingOps = new ConcurrentHashMap<>();

    private record PendingOperation<T>(QuorumCollector<T> collector) {}

    private DistributedDHTClient(DHTNode node, ClusterNetwork network, DHTConfig config) {
        this.node = node;
        this.network = network;
        this.config = config;
    }

    /// Create a distributed DHT client.
    ///
    /// @param node    local DHT node for handling local storage operations
    /// @param network cluster network for inter-node messaging
    /// @param config  DHT configuration (replication factor, quorum sizes)
    public static DistributedDHTClient distributedDHTClient(DHTNode node,
                                                            ClusterNetwork network,
                                                            DHTConfig config) {
        return new DistributedDHTClient(node, network, config);
    }

    @Override
    public Promise<Option<byte[]>> get(byte[] key) {
        var targets = targetNodes(key);

        if (targets.isEmpty()) {
            return DHTError.NO_AVAILABLE_NODES.promise();
        }

        Promise<Option<byte[]>> promise = Promise.promise();
        var collector = QuorumCollector.<Option<byte[]>>quorumCollector(config.readQuorum(), targets.size(), promise);

        for (var target : targets) {
            if (target.equals(node.nodeId())) {
                handleLocalGet(key, collector);
            } else {
                sendRemoteGet(target, key, collector);
            }
        }

        return promise;
    }

    @Override
    public Promise<Unit> put(byte[] key, byte[] value) {
        var targets = targetNodes(key);

        if (targets.isEmpty()) {
            return DHTError.NO_AVAILABLE_NODES.promise();
        }

        Promise<Unit> promise = Promise.promise();
        var collector = QuorumCollector.<Unit>quorumCollector(config.writeQuorum(), targets.size(), promise);

        for (var target : targets) {
            if (target.equals(node.nodeId())) {
                handleLocalPut(key, value, collector);
            } else {
                sendRemotePut(target, key, value, collector);
            }
        }

        return promise;
    }

    @Override
    public Promise<Boolean> remove(byte[] key) {
        var targets = targetNodes(key);

        if (targets.isEmpty()) {
            return DHTError.NO_AVAILABLE_NODES.promise();
        }

        Promise<Boolean> promise = Promise.promise();
        var collector = QuorumCollector.<Boolean>quorumCollector(config.writeQuorum(), targets.size(), promise);

        for (var target : targets) {
            if (target.equals(node.nodeId())) {
                handleLocalRemove(key, collector);
            } else {
                sendRemoteRemove(target, key, collector);
            }
        }

        return promise;
    }

    @Override
    public Promise<Boolean> exists(byte[] key) {
        var targets = targetNodes(key);

        if (targets.isEmpty()) {
            return DHTError.NO_AVAILABLE_NODES.promise();
        }

        Promise<Boolean> promise = Promise.promise();
        var collector = QuorumCollector.<Boolean>quorumCollector(config.readQuorum(), targets.size(), promise);

        for (var target : targets) {
            if (target.equals(node.nodeId())) {
                handleLocalExists(key, collector);
            } else {
                sendRemoteExists(target, key, collector);
            }
        }

        return promise;
    }

    @Override
    public Partition partitionFor(byte[] key) {
        return node.partitionFor(key);
    }

    /// Get the underlying node.
    public DHTNode node() {
        return node;
    }

    // --- Response handlers (called by message router) ---

    /// Handle a get response from a remote node.
    public void onGetResponse(DHTMessage.GetResponse response) {
        removePending(response.requestId())
            .onPresent(op -> castCollector(op, Option.class).onSuccess(response.value()));
    }

    /// Handle a put response from a remote node.
    public void onPutResponse(DHTMessage.PutResponse response) {
        removePending(response.requestId())
            .onPresent(op -> {
                if (response.success()) {
                    castCollector(op, Unit.class).onSuccess(unit());
                } else {
                    castCollector(op, Unit.class).onFailure(DHTError.OPERATION_TIMEOUT);
                }
            });
    }

    /// Handle a remove response from a remote node.
    public void onRemoveResponse(DHTMessage.RemoveResponse response) {
        removePending(response.requestId())
            .onPresent(op -> castCollector(op, Boolean.class).onSuccess(response.found()));
    }

    /// Handle an exists response from a remote node.
    public void onExistsResponse(DHTMessage.ExistsResponse response) {
        removePending(response.requestId())
            .onPresent(op -> castCollector(op, Boolean.class).onSuccess(response.exists()));
    }

    // --- Private helpers ---

    private List<NodeId> targetNodes(byte[] key) {
        return node.ring().nodesFor(key, config.effectiveReplicationFactor(node.ring().nodeCount()));
    }

    private Option<PendingOperation<?>> removePending(String correlationId) {
        return Option.option(pendingOps.remove(correlationId));
    }

    @SuppressWarnings("unchecked")
    private <T> QuorumCollector<T> castCollector(PendingOperation<?> op, Class<?> ignored) {
        return (QuorumCollector<T>) op.collector();
    }

    private void handleLocalGet(byte[] key, QuorumCollector<Option<byte[]>> collector) {
        node.getLocal(key)
            .onSuccess(collector::onSuccess)
            .onFailure(collector::onFailure);
    }

    private void handleLocalPut(byte[] key, byte[] value, QuorumCollector<Unit> collector) {
        node.putLocal(key, value)
            .onSuccess(collector::onSuccess)
            .onFailure(collector::onFailure);
    }

    private void handleLocalRemove(byte[] key, QuorumCollector<Boolean> collector) {
        node.removeLocal(key)
            .onSuccess(collector::onSuccess)
            .onFailure(collector::onFailure);
    }

    private void handleLocalExists(byte[] key, QuorumCollector<Boolean> collector) {
        node.existsLocal(key)
            .onSuccess(collector::onSuccess)
            .onFailure(collector::onFailure);
    }

    private void sendRemoteGet(NodeId target, byte[] key, QuorumCollector<Option<byte[]>> collector) {
        var correlationId = KSUID.ksuid().toString();
        pendingOps.put(correlationId, new PendingOperation<>(collector));
        network.send(target, new DHTMessage.GetRequest(correlationId, node.nodeId(), key));
    }

    private void sendRemotePut(NodeId target, byte[] key, byte[] value, QuorumCollector<Unit> collector) {
        var correlationId = KSUID.ksuid().toString();
        pendingOps.put(correlationId, new PendingOperation<>(collector));
        network.send(target, new DHTMessage.PutRequest(correlationId, node.nodeId(), key, value));
    }

    private void sendRemoteRemove(NodeId target, byte[] key, QuorumCollector<Boolean> collector) {
        var correlationId = KSUID.ksuid().toString();
        pendingOps.put(correlationId, new PendingOperation<>(collector));
        network.send(target, new DHTMessage.RemoveRequest(correlationId, node.nodeId(), key));
    }

    private void sendRemoteExists(NodeId target, byte[] key, QuorumCollector<Boolean> collector) {
        var correlationId = KSUID.ksuid().toString();
        pendingOps.put(correlationId, new PendingOperation<>(collector));
        network.send(target, new DHTMessage.ExistsRequest(correlationId, node.nodeId(), key));
    }
}
