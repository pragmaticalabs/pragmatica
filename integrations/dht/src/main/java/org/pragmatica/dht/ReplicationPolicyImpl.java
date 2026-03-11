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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/// Package-private implementation of ReplicationPolicy.
/// Core community uses standard ring placement; non-core gets home replica first.
class ReplicationPolicyImpl<N extends Comparable<N>> implements ReplicationPolicy<N> {
    private static final String CORE_COMMUNITY = "core";

    private final ConsistentHashRing<N> ring;
    private final int replicationFactor;
    private final HomeReplicaResolver<N> homeReplicaResolver;
    private final Predicate<N> spotNodeFilter;

    ReplicationPolicyImpl(ConsistentHashRing<N> ring,
                          int replicationFactor,
                          HomeReplicaResolver<N> homeReplicaResolver,
                          Predicate<N> spotNodeFilter) {
        this.ring = ring;
        this.replicationFactor = replicationFactor;
        this.homeReplicaResolver = homeReplicaResolver;
        this.spotNodeFilter = spotNodeFilter;
    }

    @Override
    public List<N> replicasFor(byte[] key, String ownerCommunity) {
        if (CORE_COMMUNITY.equals(ownerCommunity)) {
            return ring.nodesFor(key, replicationFactor, spotNodeFilter);
        }

        return replicasForNonCore(key, ownerCommunity);
    }

    private List<N> replicasForNonCore(byte[] key, String ownerCommunity) {
        return homeReplicaResolver.resolve(key, ownerCommunity)
                                  .fold(() -> ring.nodesFor(key, replicationFactor, spotNodeFilter),
                                        home -> buildReplicaListWithHome(key, home));
    }

    private List<N> buildReplicaListWithHome(byte[] key, N home) {
        var result = new ArrayList<N>();
        result.add(home);

        var ringReplicas = ring.nodesFor(key, replicationFactor, excludeHomeNode(home));

        for (var node : ringReplicas) {
            if (result.size() >= replicationFactor) {
                break;
            }
            result.add(node);
        }

        return result;
    }

    private Predicate<N> excludeHomeNode(N home) {
        return node -> spotNodeFilter.test(node) && !node.equals(home);
    }
}
