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

import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Package-private implementation of HomeReplicaResolver.
/// Deterministically selects a home node from community members using FNV-1a hashing.
class HomeReplicaResolverImpl<N extends Comparable<N>> implements HomeReplicaResolver<N> {
    private final Function<String, Set<N>> membershipLookup;

    HomeReplicaResolverImpl(Function<String, Set<N>> membershipLookup) {
        this.membershipLookup = membershipLookup;
    }

    @Override
    public Option<N> resolve(byte[] key, String community) {
        var members = membershipLookup.apply(community);

        if (members == null || members.isEmpty()) {
            return none();
        }

        var sorted = sortedMembers(members);
        var index = selectIndex(key, sorted.size());

        return some(sorted.get(index));
    }

    private List<N> sortedMembers(Set<N> members) {
        return members.stream()
                      .sorted()
                      .toList();
    }

    private static int selectIndex(byte[] key, int size) {
        return (fnvHash(key) & 0x7FFFFFFF) % size;
    }

    /// FNV-1a hash for deterministic selection.
    private static int fnvHash(byte[] data) {
        int h = 0x811c9dc5;

        for (byte b : data) {
            h ^= b;
            h *= 0x01000193;
        }

        return h;
    }
}
