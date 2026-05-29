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

package org.pragmatica.swim.membership;

import org.pragmatica.consensus.NodeId;

import java.util.Set;

/// Stable-set membership delta emitted by [`MembershipTracker`] exactly once per
/// stable transition (membership-unification-spec §4 "Emit deltas once").
///
/// A change is produced only when the debounced stable member set differs from the
/// last-emitted set. `joined` and `left` are disjoint and at least one is non-empty
/// (the tracker never emits an empty delta). `members` is the full post-transition
/// stable set (includes self) for subscribers that prefer absolute state over deltas.
public record MembershipChange(Set<NodeId> joined, Set<NodeId> left, Set<NodeId> members) {
    public MembershipChange {
        joined = Set.copyOf(joined);
        left = Set.copyOf(left);
        members = Set.copyOf(members);
    }

    public static MembershipChange membershipChange(Set<NodeId> joined, Set<NodeId> left, Set<NodeId> members) {
        return new MembershipChange(joined, left, members);
    }
}
