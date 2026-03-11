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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.HomeReplicaResolver.homeReplicaResolver;

class HomeReplicaResolverTest {
    private static final byte[] KEY_A = "key-a".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEY_B = "key-b".getBytes(StandardCharsets.UTF_8);
    private static final String COMMUNITY = "tenant-1";

    @Nested
    class DeterministicSelection {

        @Test
        void resolve_sameKeyAndCommunity_returnsSameNode() {
            var members = Set.of("node-1", "node-2", "node-3");
            HomeReplicaResolver<String> resolver = homeReplicaResolver(c -> members);

            var first = resolver.resolve(KEY_A, COMMUNITY);
            var second = resolver.resolve(KEY_A, COMMUNITY);

            assertThat(first.isPresent()).isTrue();
            assertThat(first).isEqualTo(second);
        }

        @Test
        void resolve_differentKeys_maySelectDifferentMembers() {
            var members = Set.of("node-1", "node-2", "node-3", "node-4", "node-5");
            HomeReplicaResolver<String> resolver = homeReplicaResolver(c -> members);

            // Try many keys — at least some should pick different nodes
            var selectedNodes = new HashSet<String>();

            for (int i = 0; i < 100; i++) {
                var key = ("key-" + i).getBytes(StandardCharsets.UTF_8);
                resolver.resolve(key, COMMUNITY)
                        .onPresent(selectedNodes::add);
            }

            assertThat(selectedNodes.size()).isGreaterThan(1);
        }
    }

    @Nested
    class EdgeCases {

        @Test
        void resolve_emptyMembership_returnsEmpty() {
            HomeReplicaResolver<String> resolver = homeReplicaResolver(c -> Set.of());

            var result = resolver.resolve(KEY_A, COMMUNITY);

            assertThat(result.isPresent()).isFalse();
        }

        @Test
        void resolve_nullMembership_returnsEmpty() {
            HomeReplicaResolver<String> resolver = homeReplicaResolver(c -> null);

            var result = resolver.resolve(KEY_A, COMMUNITY);

            assertThat(result.isPresent()).isFalse();
        }

        @Test
        void resolve_singleMember_alwaysSelectsThatMember() {
            HomeReplicaResolver<String> resolver = homeReplicaResolver(c -> Set.of("only-node"));

            var resultA = resolver.resolve(KEY_A, COMMUNITY);
            var resultB = resolver.resolve(KEY_B, COMMUNITY);

            assertThat(resultA.isPresent()).isTrue();
            resultA.onPresent(n -> assertThat(n).isEqualTo("only-node"));
            assertThat(resultB.isPresent()).isTrue();
            resultB.onPresent(n -> assertThat(n).isEqualTo("only-node"));
        }

        @Test
        void resolve_differentCommunities_usesCorrectMembership() {
            var memberships = Map.<String, Set<String>>of(
                "community-a", Set.of("node-a"),
                "community-b", Set.of("node-b")
            );
            HomeReplicaResolver<String> resolver = homeReplicaResolver(memberships::get);

            var resultA = resolver.resolve(KEY_A, "community-a");
            var resultB = resolver.resolve(KEY_A, "community-b");

            assertThat(resultA.isPresent()).isTrue();
            resultA.onPresent(n -> assertThat(n).isEqualTo("node-a"));
            assertThat(resultB.isPresent()).isTrue();
            resultB.onPresent(n -> assertThat(n).isEqualTo("node-b"));
        }
    }
}
