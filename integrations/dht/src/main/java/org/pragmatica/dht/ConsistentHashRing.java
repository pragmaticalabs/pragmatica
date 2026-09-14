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

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;


/// Consistent hash ring for distributing data across nodes.
/// Uses virtual nodes for even distribution and MurmurHash3-like hashing.
///
/// The ring maps keys to partitions (0-1023), and partitions to nodes — and that is the ONLY
/// placement: a key's nodes are its partition's nodes ([#nodesFor(byte[], int)] resolves through
/// [#nodesFor(Partition, int)]). Until #420 a key was placed at its own hash position while
/// anti-entropy and the rebalancer placed its partition at `hash("partition:<p>")`, so the repair
/// machinery moved data among nodes that owned the partition but not the key (measured: the two
/// owner sets agreed for ~10% of keys on a 5-node ring — chance level).
/// Each physical node has multiple virtual nodes spread across the ring
/// for better load distribution.
///
/// @param <N> Node identifier type
public final class ConsistentHashRing<N extends Comparable<N>> {
    private static final int VIRTUAL_NODES_PER_PHYSICAL = 150;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final NavigableMap<Integer, N> ring = new TreeMap<>();
    private final Map<N, List<Integer>> nodeToVirtualNodes = new HashMap<>();
    private final int virtualNodesPerPhysical;

    private ConsistentHashRing(int virtualNodesPerPhysical) {
        this.virtualNodesPerPhysical = virtualNodesPerPhysical;
    }

    /// Create a new empty consistent hash ring with default virtual node count.
    public static <N extends Comparable<N>> ConsistentHashRing<N> consistentHashRing() {
        return new ConsistentHashRing<>(VIRTUAL_NODES_PER_PHYSICAL);
    }

    /// Create a new empty consistent hash ring with specified virtual node count.
    public static <N extends Comparable<N>> ConsistentHashRing<N> consistentHashRing(int virtualNodesPerPhysical) {
        return new ConsistentHashRing<>(virtualNodesPerPhysical);
    }

    /// Add a node to the ring.
    @Contract
    public void addNode(N node) {
        lock.writeLock().lock();
        try {
            if (nodeToVirtualNodes.containsKey(node)) {
                return;
            }

            List<Integer> virtualNodes = new ArrayList<>(virtualNodesPerPhysical);

            for (int i = 0; i < virtualNodesPerPhysical; i++) {
                int hash = hash(node.toString() + "#" + i);

                ring.put(hash, node);
                virtualNodes.add(hash);
            }

            nodeToVirtualNodes.put(node, virtualNodes);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /// Remove a node from the ring.
    @Contract
    public void removeNode(N node) {
        lock.writeLock().lock();
        try {
            Option.option(nodeToVirtualNodes.remove(node)).onPresent(virtualNodes -> virtualNodes.forEach(ring::remove));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /// Get the partition for a given key.
    /// Note: This method does not require locking as it only uses the hash function.
    public Partition partitionFor(byte[] key) {
        int hash = hash(key);
        // Use bitwise AND to ensure non-negative result (Math.abs fails for Integer.MIN_VALUE)
        return Partition.at((hash & 0x7FFFFFFF) % Partition.MAX_PARTITIONS);
    }

    /// Get the partition for a given string key.
    public Partition partitionFor(String key) {
        return partitionFor(key.getBytes(StandardCharsets.UTF_8));
    }

    /// Get the primary node for a given key: the primary of the key's partition.
    /// Returns empty if no nodes are in the ring.
    public Option<N> primaryFor(byte[] key) {
        return primaryFor(partitionFor(key));
    }

    /// Get the primary node for a partition.
    /// Returns empty if no nodes are in the ring.
    public Option<N> primaryFor(Partition partition) {
        lock.readLock().lock();
        try {
            if (ring.isEmpty()) {
                return Option.none();
            }

            return Option.some(getNodeForHash(positionOf(partition)));
        } finally {
            lock.readLock().unlock();
        }
    }

    /// Get the primary node for a given string key.
    public Option<N> primaryFor(String key) {
        return primaryFor(key.getBytes(StandardCharsets.UTF_8));
    }

    /// Get the primary and replica nodes for a given key: the nodes of the key's partition.
    /// Returns up to replicaCount nodes, starting with primary.
    public List<N> nodesFor(byte[] key, int replicaCount) {
        return nodesFor(partitionFor(key), replicaCount);
    }

    /// Get the primary and replica nodes for a partition.
    /// Returns up to replicaCount nodes, starting with primary.
    public List<N> nodesFor(Partition partition, int replicaCount) {
        lock.readLock().lock();
        try {
            if (ring.isEmpty()) {
                return List.of();
            }

            if (replicaCount <= 0) {
                return List.of();
            }

            int hash = positionOf(partition);
            Set<N> seen = new LinkedHashSet<>();
            // Start from the hash position and walk clockwise
            int current = Option.option(ring.ceilingKey(hash)).or(ring::firstKey);

            while (seen.size() < replicaCount && seen.size() < nodeToVirtualNodes.size()) {
                N node = ring.get(current);

                seen.add(node);
                current = Option.option(ring.higherKey(current)).or(ring::firstKey);
            }

            return new ArrayList<>(seen);
        } finally {
            lock.readLock().unlock();
        }
    }

    /// Get the primary and replica nodes for a given key, excluding filtered nodes.
    /// Returns up to replicaCount nodes of the key's partition that pass the filter, starting
    /// with primary.
    ///
    /// @param key          the key to look up
    /// @param replicaCount maximum number of nodes to return
    /// @param filter       predicate that must return true for a node to be included
    public List<N> nodesFor(byte[] key, int replicaCount, Predicate<N> filter) {
        return nodesFor(partitionFor(key), replicaCount, filter);
    }

    /// Returns up to replicaCount nodes of the partition that pass the filter, starting with primary.
    public List<N> nodesFor(Partition partition, int replicaCount, Predicate<N> filter) {
        lock.readLock().lock();
        try {
            if (ring.isEmpty() || replicaCount <= 0) {
                return List.of();
            }

            int hash = positionOf(partition);
            Set<N> seen = new LinkedHashSet<>();
            Set<N> visited = new HashSet<>();
            int current = Option.option(ring.ceilingKey(hash)).or(ring::firstKey);

            while (seen.size() < replicaCount && visited.size() < nodeToVirtualNodes.size()) {
                N node = ring.get(current);

                if (visited.add(node) && filter.test(node)) {
                    seen.add(node);
                }

                current = Option.option(ring.higherKey(current)).or(ring::firstKey);
            }

            return new ArrayList<>(seen);
        } finally {
            lock.readLock().unlock();
        }
    }

    /// Get the primary and replica nodes for a given string key.
    public List<N> nodesFor(String key, int replicaCount) {
        return nodesFor(key.getBytes(StandardCharsets.UTF_8), replicaCount);
    }

    /// Get all nodes currently in the ring.
    public Set<N> nodes() {
        lock.readLock().lock();
        try {
            return new HashSet<>(nodeToVirtualNodes.keySet());
        } finally {
            lock.readLock().unlock();
        }
    }

    /// Get the number of nodes in the ring.
    public int nodeCount() {
        lock.readLock().lock();
        try {
            return nodeToVirtualNodes.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /// Check if the ring is empty.
    public boolean isEmpty() {
        lock.readLock().lock();
        try {
            return ring.isEmpty();
        } finally {
            lock.readLock().unlock();
        }
    }

    private N getNodeForHash(int hash) {
        int key = Option.option(ring.ceilingKey(hash)).or(ring::firstKey);

        return ring.get(key);
    }

    /// MurmurHash3-like hash function.
    /// Provides good distribution for consistent hashing.
    /// The ring position of a partition — the one place the partition-to-position mapping lives.
    private static int positionOf(Partition partition) {
        return hash("partition:" + partition.value());
    }

    private static int hash(byte[] data) {
        int h = 0x811c9dc5;

        for (byte b : data) {
            h ^= b;
            h *= 0x01000193;
        }
        // Final mix
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;

        return h;
    }

    private static int hash(String data) {
        return hash(data.getBytes(StandardCharsets.UTF_8));
    }
}
