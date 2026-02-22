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
import org.pragmatica.utility.KSUID;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Re-replicates data when a node departs to maintain replication factor.
/// After a node is removed from the ring, scans local storage and pushes
/// entries to new replica nodes that need copies for under-replicated partitions.
public final class DHTRebalancer {
    private static final Logger log = LoggerFactory.getLogger(DHTRebalancer.class);

    private final DHTNode node;
    private final ClusterNetwork network;
    private final DHTConfig config;

    private DHTRebalancer(DHTNode node, ClusterNetwork network, DHTConfig config) {
        this.node = node;
        this.network = network;
        this.config = config;
    }

    /// Create a rebalancer for the given DHT node.
    ///
    /// @param node    local DHT node with storage and ring
    /// @param network cluster network for sending migration data
    /// @param config  DHT configuration
    public static DHTRebalancer dhtRebalancer(DHTNode node, ClusterNetwork network, DHTConfig config) {
        return new DHTRebalancer(node, network, config);
    }

    /// Called after a node is removed from the ring.
    /// Scans local storage partition by partition and pushes data to new replica
    /// nodes that need copies to restore the replication factor.
    public void onNodeRemoved(NodeId removedNode) {
        if (config.isFullReplication()) {
            return;
        }

        log.info("Rebalancing after node {} departed", removedNode.id());

        var replicationFactor = config.effectiveReplicationFactor(node.ring().nodeCount());

        for (int p = 0; p < Partition.MAX_PARTITIONS; p++) {
            rebalancePartition(p, replicationFactor);
        }
    }

    private void rebalancePartition(int partitionIndex, int replicationFactor) {
        var partitionKey = ("partition:" + partitionIndex).getBytes(StandardCharsets.UTF_8);
        var replicaNodes = node.ring().nodesFor(partitionKey, replicationFactor);

        if (!replicaNodes.contains(node.nodeId())) {
            return;
        }

        if (!isPrimary(replicaNodes)) {
            return;
        }

        var partition = Partition.at(partitionIndex);
        node.storage()
            .entriesForPartition(node.ring(), partition)
            .onSuccess(entries -> pushToReplicas(partitionIndex, replicaNodes, entries));
    }

    private boolean isPrimary(List<NodeId> replicaNodes) {
        return !replicaNodes.isEmpty() && replicaNodes.getFirst().equals(node.nodeId());
    }

    private void pushToReplicas(int partitionIndex, List<NodeId> replicaNodes, List<DHTMessage.KeyValue> entries) {
        if (entries.isEmpty()) {
            return;
        }

        for (var replica : replicaNodes) {
            if (replica.equals(node.nodeId())) {
                continue;
            }
            sendMigrationData(replica, partitionIndex, entries);
        }
    }

    private void sendMigrationData(NodeId target, int partitionIndex, List<DHTMessage.KeyValue> entries) {
        var correlationId = KSUID.ksuid().toString();

        log.debug("Pushing {} entries for partition {} to {}", entries.size(), partitionIndex, target.id());

        network.send(target, new DHTMessage.MigrationDataResponse(correlationId, node.nodeId(), entries));
    }
}
