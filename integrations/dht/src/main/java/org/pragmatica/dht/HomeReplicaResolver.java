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

import java.util.Set;
import java.util.function.Function;

/// Deterministic home replica selection from community members.
/// Uses hashing over the community member set to pick a stable home node.
///
/// @param <N> Node identifier type
public interface HomeReplicaResolver<N extends Comparable<N>> {

    /// Resolve the home replica for a key within a community.
    ///
    /// @param key       the DHT key bytes
    /// @param community the community name
    /// @return the home node, or empty if no community members available
    Option<N> resolve(byte[] key, String community);

    /// Create a resolver backed by a community membership lookup.
    ///
    /// @param membershipLookup function returning current members for a community
    static <N extends Comparable<N>> HomeReplicaResolver<N> homeReplicaResolver(
            Function<String, Set<N>> membershipLookup) {
        return new HomeReplicaResolverImpl<>(membershipLookup);
    }
}
