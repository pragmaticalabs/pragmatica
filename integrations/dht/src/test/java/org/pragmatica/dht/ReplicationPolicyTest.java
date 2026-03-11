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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.ConsistentHashRing.consistentHashRing;
import static org.pragmatica.dht.HomeReplicaResolver.homeReplicaResolver;
import static org.pragmatica.dht.ReplicationPolicy.replicationPolicy;

class ReplicationPolicyTest {
    private static final byte[] TEST_KEY = "test-key".getBytes(StandardCharsets.UTF_8);
    private static final int RF = 3;
    private static final Set<String> SPOT_NODES = Set.of("spot-1");

    private ConsistentHashRing<String> ring;

    @BeforeEach
    void setUp() {
        ring = consistentHashRing();
        ring.addNode("node-1");
        ring.addNode("node-2");
        ring.addNode("node-3");
        ring.addNode("node-4");
        ring.addNode("spot-1");
    }

    @Nested
    class CoreCommunity {

        @Test
        void replicasFor_coreCommunity_usesStandardRingPlacement() {
            var policy = createPolicy(c -> Set.of());

            var replicas = policy.replicasFor(TEST_KEY, "core");

            assertThat(replicas).hasSize(RF);
            assertThat(replicas).doesNotContain("spot-1");
        }

        @Test
        void replicasFor_coreCommunity_ignoresHomeReplicaResolver() {
            // Even with community members, core should use ring placement
            var policy = createPolicy(c -> Set.of("node-1"));

            var coreReplicas = policy.replicasFor(TEST_KEY, "core");
            var ringReplicas = ring.nodesFor(TEST_KEY, RF, node -> !SPOT_NODES.contains(node));

            assertThat(coreReplicas).isEqualTo(ringReplicas);
        }
    }

    @Nested
    class NonCoreCommunity {

        @Test
        void replicasFor_nonCoreCommunity_homeReplicaIsFirst() {
            var communityMembers = Set.of("node-2");
            var policy = createPolicy(c -> communityMembers);

            var replicas = policy.replicasFor(TEST_KEY, "tenant-1");

            assertThat(replicas).isNotEmpty();
            assertThat(replicas.getFirst()).isEqualTo("node-2");
        }

        @Test
        void replicasFor_nonCoreCommunity_totalEqualsRF() {
            var communityMembers = Set.of("node-1", "node-2");
            var policy = createPolicy(c -> communityMembers);

            var replicas = policy.replicasFor(TEST_KEY, "tenant-1");

            assertThat(replicas).hasSize(RF);
        }

        @Test
        void replicasFor_nonCoreCommunity_excludesSpotNodes() {
            var communityMembers = Set.of("node-1");
            var policy = createPolicy(c -> communityMembers);

            var replicas = policy.replicasFor(TEST_KEY, "tenant-1");

            assertThat(replicas).doesNotContain("spot-1");
        }

        @Test
        void replicasFor_nonCoreCommunity_noDuplicates() {
            var communityMembers = Set.of("node-1", "node-3");
            var policy = createPolicy(c -> communityMembers);

            var replicas = policy.replicasFor(TEST_KEY, "tenant-1");

            assertThat(replicas).doesNotHaveDuplicates();
        }
    }

    @Nested
    class Fallbacks {

        @Test
        void replicasFor_noCommunityMembers_fallsBackToRing() {
            var policy = createPolicy(c -> Set.of());

            var replicas = policy.replicasFor(TEST_KEY, "tenant-empty");
            var ringReplicas = ring.nodesFor(TEST_KEY, RF, node -> !SPOT_NODES.contains(node));

            assertThat(replicas).isEqualTo(ringReplicas);
        }

        @Test
        void replicasFor_fewerNodesThanRF_returnsDegradedReplicaSet() {
            ConsistentHashRing<String> smallRing = consistentHashRing();
            smallRing.addNode("node-1");
            smallRing.addNode("node-2");

            ReplicationPolicy<String> policy = replicationPolicy(
                smallRing, 5,
                homeReplicaResolver(c -> Set.of("node-1")),
                node -> true
            );

            var replicas = policy.replicasFor(TEST_KEY, "tenant-1");

            // Home + whatever ring can provide (at most 2 nodes total, but RF=5)
            assertThat(replicas.size()).isLessThanOrEqualTo(5);
            assertThat(replicas.getFirst()).isEqualTo("node-1");
        }
    }

    private ReplicationPolicy<String> createPolicy(java.util.function.Function<String, Set<String>> membershipLookup) {
        return replicationPolicy(
            ring, RF,
            homeReplicaResolver(membershipLookup),
            node -> !SPOT_NODES.contains(node)
        );
    }
}
