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
package org.pragmatica.consensus.rabia;

import java.util.List;
import java.util.Set;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;


/// Consensus-level cluster membership descriptor.
///
/// Used by [RabiaEngine#reconfigure] as the explicit reset signal: applying a
/// `ClusterConfig` that differs from the engine's current membership wipes proposal
/// state and restarts the engine against the new membership. Quorum-loss alone never
/// resets the engine — it transitions to `Paused`, retaining state until quorum returns
/// (or until a `reconfigure` call replaces the membership).
///
/// This is an internal consensus value object, deliberately decoupled from higher-level
/// deployment configuration (`aether/aether-config/ClusterConfig`) which carries
/// orchestration concerns the consensus layer must not see.
@org.pragmatica.serialization.Codec
public record ClusterConfig(List<NodeId> members) {
    public ClusterConfig {
        members = List.copyOf(members);
    }

    public static Result<ClusterConfig> clusterConfig(List<NodeId> members) {
        return Verify.ensure(members, Verify.Is::notNull, ClusterConfigError.EMPTY_MEMBERSHIP)
                     .filter(ClusterConfigError.EMPTY_MEMBERSHIP,
                             m -> !m.isEmpty())
                     .filter(ClusterConfigError.DUPLICATE_MEMBERS,
                             m -> Set.copyOf(m).size() == m.size())
                     .map(ClusterConfig::new);
    }

    public int clusterSize() {
        return members.size();
    }

    /// Strict equality on membership: same node-id set, regardless of order.
    /// Used to decide whether a `reconfigure` call is a true membership change
    /// (and thus requires a state reset) or a no-op replay of the same config.
    public boolean sameMembership(ClusterConfig other) {
        return Set.copyOf(members).equals(Set.copyOf(other.members));
    }

    public enum ClusterConfigError implements Cause {
        EMPTY_MEMBERSHIP("ClusterConfig must contain at least one member"),
        DUPLICATE_MEMBERS("ClusterConfig must not contain duplicate members");
        private final String message;
        ClusterConfigError(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }
}
