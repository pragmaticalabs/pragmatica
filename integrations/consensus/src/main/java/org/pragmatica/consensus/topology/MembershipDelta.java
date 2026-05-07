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

package org.pragmatica.consensus.topology;

import org.pragmatica.consensus.NodeId;

import java.util.HashSet;
import java.util.Set;

/// Pure diff between two consecutive `MembershipView` projections.
///
/// Computed inside `TopologyObserver` whenever it observes a fresh `MembershipView`
/// and serves as the foundation for the membership-state-tracker consolidation
/// (audit `aether/docs/internal/audits/membership-state-tracker-audit-2026-05-07.md`):
/// downstream subscribers (CTM anchor logic, transport eviction, event emitters) can
/// drive off a single canonical edge stream rather than the parallel SWIM-observation
/// / QUIC-view-change / KV-lifecycle paths the audit identifies.
///
/// `added` and `removed` are the symmetric difference of the two views' core-member
/// sets. A node that flips lifecycle from `JOINING` → `ON_DUTY` (without entering or
/// leaving the core set) is reflected via the `phaseChanged` set; current Step 1
/// publishers do not yet emit phase-only edges, so this set is a placeholder for
/// later steps and is always empty for now.
public record MembershipDelta(Set<NodeId> added, Set<NodeId> removed, Set<NodeId> phaseChanged) {
    public MembershipDelta {
        added = Set.copyOf(added);
        removed = Set.copyOf(removed);
        phaseChanged = Set.copyOf(phaseChanged);
    }

    public static MembershipDelta empty() {
        return new MembershipDelta(Set.of(), Set.of(), Set.of());
    }

    /// Compute the delta of `current` relative to `previous`. `null` for either side
    /// is treated as the empty set so the very first observation surfaces every
    /// `current` member as `added`.
    public static MembershipDelta diff(Set<NodeId> previous, Set<NodeId> current) {
        var prev = previous == null ? Set.<NodeId>of() : previous;
        var curr = current == null ? Set.<NodeId>of() : current;
        var added = new HashSet<>(curr);
        added.removeAll(prev);
        var removed = new HashSet<>(prev);
        removed.removeAll(curr);
        return new MembershipDelta(added, removed, Set.of());
    }

    public boolean isEmpty() {
        return added.isEmpty() && removed.isEmpty() && phaseChanged.isEmpty();
    }
}
