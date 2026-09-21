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
/// A target becomes voting authority only through an agreed checkpoint handoff.
/// Local health or a provisioning count never changes the installed electorate.
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
    /// Used to distinguish a new handoff target from a retry of the installed configuration.
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
