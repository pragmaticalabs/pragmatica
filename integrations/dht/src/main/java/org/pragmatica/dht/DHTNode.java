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
import org.pragmatica.dht.storage.StorageEngine;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.Arrays;
import java.util.function.Consumer;
import java.util.zip.CRC32;

/// Local DHT node that handles storage operations.
/// Provides local data access and can be integrated with MessageRouter
/// for handling remote requests.
public final class DHTNode {
    private final NodeId nodeId;
    private final StorageEngine storage;
    private final ConsistentHashRing<NodeId> ring;
    private final DHTConfig config;

    private DHTNode(NodeId nodeId, StorageEngine storage, ConsistentHashRing<NodeId> ring, DHTConfig config) {
        this.nodeId = nodeId;
        this.storage = storage;
        this.ring = ring;
        this.config = config;
    }

    /// Create a new DHT node.
    ///
    /// @param nodeId  this node's identifier
    /// @param storage storage engine for local data
    /// @param ring    consistent hash ring for routing
    /// @param config  DHT configuration
    public static DHTNode dhtNode(NodeId nodeId,
                                  StorageEngine storage,
                                  ConsistentHashRing<NodeId> ring,
                                  DHTConfig config) {
        return new DHTNode(nodeId, storage, ring, config);
    }

    /// Get the node's identifier.
    public NodeId nodeId() {
        return nodeId;
    }

    /// Get the storage engine (for migration and anti-entropy operations).
    public StorageEngine storage() {
        return storage;
    }

    /// Get the configuration.
    public DHTConfig config() {
        return config;
    }

    /// Get the consistent hash ring.
    public ConsistentHashRing<NodeId> ring() {
        return ring;
    }

    /// Get a value from local storage.
    public Promise<Option<byte[]>> getLocal(byte[] key) {
        return storage.get(key);
    }

    /// Put a value to local storage.
    public Promise<Unit> putLocal(byte[] key, byte[] value) {
        return storage.put(key, value);
    }

    /// Remove a value from local storage.
    public Promise<Boolean> removeLocal(byte[] key) {
        return storage.remove(key);
    }

    /// Check if key exists in local storage.
    public Promise<Boolean> existsLocal(byte[] key) {
        return storage.exists(key);
    }

    /// Get the partition for a key.
    public Partition partitionFor(byte[] key) {
        return ring.partitionFor(key);
    }

    /// Check if this node is responsible for a key (as primary or replica).
    public boolean isResponsibleFor(byte[] key) {
        return ring.nodesFor(key,
                             config.replicationFactor())
                   .contains(nodeId);
    }

    /// Check if this node is the primary for a key.
    public boolean isPrimaryFor(byte[] key) {
        return ring.primaryFor(key)
                   .map(nodeId::equals)
                   .or(false);
    }

    /// Get the local storage size.
    public long localSize() {
        return storage.size();
    }

    /// Clear local storage.
    public Promise<Unit> clearLocal() {
        return storage.clear();
    }

    /// Shutdown the node and release resources.
    public Promise<Unit> shutdown() {
        return storage.shutdown();
    }

    /// Handle a get request (for message routing integration).
    public void handleGetRequest(DHTMessage.GetRequest request,
                                 Consumer<DHTMessage.GetResponse> responseHandler) {
        storage.get(request.key())
               .onSuccess(value -> responseHandler.accept(new DHTMessage.GetResponse(request.requestId(),
                                                                                     nodeId,
                                                                                     value)))
               .onFailure(_ -> responseHandler.accept(new DHTMessage.GetResponse(request.requestId(),
                                                                                 nodeId,
                                                                                 Option.none())));
    }

    /// Handle a put request (for message routing integration).
    public void handlePutRequest(DHTMessage.PutRequest request,
                                 Consumer<DHTMessage.PutResponse> responseHandler) {
        storage.put(request.key(),
                    request.value())
               .onSuccess(_ -> responseHandler.accept(new DHTMessage.PutResponse(request.requestId(),
                                                                                 nodeId,
                                                                                 true)))
               .onFailure(_ -> responseHandler.accept(new DHTMessage.PutResponse(request.requestId(),
                                                                                 nodeId,
                                                                                 false)));
    }

    /// Handle a remove request (for message routing integration).
    public void handleRemoveRequest(DHTMessage.RemoveRequest request,
                                    Consumer<DHTMessage.RemoveResponse> responseHandler) {
        storage.remove(request.key())
               .onSuccess(found -> responseHandler.accept(new DHTMessage.RemoveResponse(request.requestId(),
                                                                                        nodeId,
                                                                                        found)))
               .onFailure(_ -> responseHandler.accept(new DHTMessage.RemoveResponse(request.requestId(),
                                                                                    nodeId,
                                                                                    false)));
    }

    /// Handle an exists request (for message routing integration).
    public void handleExistsRequest(DHTMessage.ExistsRequest request,
                                    Consumer<DHTMessage.ExistsResponse> responseHandler) {
        storage.exists(request.key())
               .onSuccess(exists -> responseHandler.accept(new DHTMessage.ExistsResponse(request.requestId(),
                                                                                         nodeId,
                                                                                         exists)))
               .onFailure(_ -> responseHandler.accept(new DHTMessage.ExistsResponse(request.requestId(),
                                                                                    nodeId,
                                                                                    false)));
    }

    /// Handle a digest request: compute digest for the requested partition range and respond.
    public void handleDigestRequest(DHTMessage.DigestRequest request,
                                    Consumer<DHTMessage.DigestResponse> responseHandler) {
        var partition = Partition.at(request.partitionStart());
        storage.entriesForPartition(ring, partition)
               .onSuccess(entries -> responseHandler.accept(new DHTMessage.DigestResponse(request.requestId(),
                                                                                          nodeId,
                                                                                          computeDigest(entries))))
               .onFailure(_ -> responseHandler.accept(new DHTMessage.DigestResponse(request.requestId(),
                                                                                    nodeId,
                                                                                    new byte[0])));
    }

    /// Handle a migration data request: return all entries for the requested partition range.
    public void handleMigrationDataRequest(DHTMessage.MigrationDataRequest request,
                                           Consumer<DHTMessage.MigrationDataResponse> responseHandler) {
        var partition = Partition.at(request.partitionStart());
        storage.entriesForPartition(ring, partition)
               .onSuccess(entries -> responseHandler.accept(new DHTMessage.MigrationDataResponse(request.requestId(),
                                                                                                  nodeId,
                                                                                                  entries)))
               .onFailure(_ -> responseHandler.accept(new DHTMessage.MigrationDataResponse(request.requestId(),
                                                                                            nodeId,
                                                                                            java.util.List.of())));
    }

    /// Apply migration data by merging received entries into local storage.
    public void applyMigrationData(java.util.List<DHTMessage.KeyValue> entries) {
        entries.forEach(kv -> storage.put(kv.key(), kv.value()));
    }

    /// Compute a CRC32 digest over sorted key-value entries.
    static byte[] computeDigest(java.util.List<DHTMessage.KeyValue> entries) {
        var crc = new CRC32();
        entries.stream()
               .sorted((a, b) -> Arrays.compare(a.key(), b.key()))
               .forEach(kv -> updateCrc(crc, kv));
        return longToBytes(crc.getValue());
    }

    private static void updateCrc(CRC32 crc, DHTMessage.KeyValue kv) {
        crc.update(kv.key());
        crc.update(kv.value());
    }

    private static byte[] longToBytes(long value) {
        return new byte[]{(byte) (value >>> 56), (byte) (value >>> 48),
                          (byte) (value >>> 40), (byte) (value >>> 32),
                          (byte) (value >>> 24), (byte) (value >>> 16),
                          (byte) (value >>> 8), (byte) value};
    }
}
