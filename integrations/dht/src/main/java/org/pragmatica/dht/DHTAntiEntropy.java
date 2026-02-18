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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Periodic anti-entropy process that synchronizes replicas.
/// Computes partition digests and exchanges them with peer nodes
/// to detect and repair inconsistencies.
public final class DHTAntiEntropy {
    private static final Logger log = LoggerFactory.getLogger(DHTAntiEntropy.class);
    private static final long INTERVAL_SECONDS = 30;

    private final DHTNode node;
    private final ClusterNetwork network;
    private final DHTConfig config;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private DHTAntiEntropy(DHTNode node, ClusterNetwork network, DHTConfig config) {
        this.node = node;
        this.network = network;
        this.config = config;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                                                                        var thread = new Thread(r, "dht-anti-entropy");
                                                                        thread.setDaemon(true);
                                                                        return thread;
                                                                    });
    }

    /// Create an anti-entropy process for the given node.
    ///
    /// @param node    local DHT node with storage and ring
    /// @param network cluster network for sending digest requests
    /// @param config  DHT configuration
    public static DHTAntiEntropy dhtAntiEntropy(DHTNode node, ClusterNetwork network, DHTConfig config) {
        return new DHTAntiEntropy(node, network, config);
    }

    /// Start the periodic anti-entropy process.
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        scheduler.scheduleAtFixedRate(this::runAntiEntropy, INTERVAL_SECONDS, INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("DHT anti-entropy started (interval: {}s)", INTERVAL_SECONDS);
    }

    /// Stop the anti-entropy process.
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        scheduler.shutdown();
        log.info("DHT anti-entropy stopped");
    }

    private void runAntiEntropy() {
        if (config.isFullReplication()) {
            return;
        }
        try{
            synchronizePartitions();
        } catch (Exception e) {
            log.error("Anti-entropy cycle failed", e);
        }
    }

    private void synchronizePartitions() {
        var replicationFactor = config.effectiveReplicationFactor(node.ring()
                                                                      .nodeCount());
        for (int p = 0; p < Partition.MAX_PARTITIONS; p++) {
            var partitionKey = ("partition:" + p).getBytes(StandardCharsets.UTF_8);
            var nodes = node.ring()
                            .nodesFor(partitionKey, replicationFactor);
            if (!nodes.contains(node.nodeId())) {
                continue;
            }
            sendDigestRequests(p, nodes);
        }
    }

    private void sendDigestRequests(int partitionIndex, List<NodeId> nodes) {
        var partition = Partition.at(partitionIndex);
        node.storage()
            .entriesForPartition(node.ring(),
                                 partition)
            .onSuccess(entries -> {
                           var digest = computeDigest(entries);
                           sendDigestToPeers(partitionIndex, nodes, digest);
                       });
    }

    private byte[] computeDigest(List<DHTMessage.KeyValue> entries) {
        var crc = new CRC32();
        entries.stream()
               .sorted((a, b) -> Arrays.compare(a.key(),
                                                b.key()))
               .forEach(kv -> {
                   crc.update(kv.key());
                   crc.update(kv.value());
               });
        return longToBytes(crc.getValue());
    }

    private void sendDigestToPeers(int partitionIndex, List<NodeId> nodes, byte[] digest) {
        for (var peer : nodes) {
            if (peer.equals(node.nodeId())) {
                continue;
            }
            var correlationId = KSUID.ksuid()
                                     .toString();
            network.send(peer,
                         new DHTMessage.DigestRequest(correlationId, node.nodeId(), partitionIndex, partitionIndex));
        }
    }

    private static byte[] longToBytes(long value) {
        return new byte[]{(byte)(value>>> 56), (byte)(value>>> 48),
        (byte)(value>>> 40), (byte)(value>>> 32),
        (byte)(value>>> 24), (byte)(value>>> 16),
        (byte)(value>>> 8), (byte) value};
    }
}
