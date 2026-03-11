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

import java.util.List;
import java.util.function.Predicate;

/// Community-aware replication policy: 1 home replica + ring replicas = RF total.
///
/// The home replica is deterministically chosen from the community that owns the key.
/// Remaining replicas come from the consistent hash ring (any community).
/// Spot nodes are excluded from replica selection.
///
/// Core community ("core") uses standard ring placement with no home-replica override.
///
/// @param <N> Node identifier type
public interface ReplicationPolicy<N extends Comparable<N>> {

    /// Compute replica set for a key owned by a specific community.
    ///
    /// @param key            the DHT key bytes
    /// @param ownerCommunity the community that owns this key
    /// @return ordered list of replica nodes (first = primary/home)
    List<N> replicasFor(byte[] key, String ownerCommunity);

    /// Create a replication policy.
    ///
    /// @param ring                consistent hash ring
    /// @param replicationFactor   target RF (e.g. 3)
    /// @param homeReplicaResolver resolver for home replica selection
    /// @param spotNodeFilter      predicate returning true for non-spot nodes (nodes eligible for replication)
    static <N extends Comparable<N>> ReplicationPolicy<N> replicationPolicy(
            ConsistentHashRing<N> ring,
            int replicationFactor,
            HomeReplicaResolver<N> homeReplicaResolver,
            Predicate<N> spotNodeFilter) {
        return new ReplicationPolicyImpl<>(ring, replicationFactor, homeReplicaResolver, spotNodeFilter);
    }
}
