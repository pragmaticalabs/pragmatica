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

import java.util.Map;
import java.util.Set;

/// Read-only projection of cluster membership used by `TopologyObserver` read paths
/// when a snapshot-backed view is available.
///
/// Intentionally narrow: only the node-id / count fields that `TopologyObserver` needs
/// are exposed. This keeps `integrations/consensus` free of any dependency on
/// `aether/slice` types (`ClusterGenerationSnapshot`, `Epoch`, `CoreMember`), while still
/// letting downstream modules adapt their richer snapshot types into this view.
///
/// Snapshot adapters (e.g. in `aether/node`) are responsible for translating
/// per-member lifecycle / health hints into the two buckets defined here:
///
///   - `coreMemberIds` — every node the authoritative snapshot considers a core member
///     (regardless of lifecycle phase).
///   - `onDutyMemberIds` — strict subset of `coreMemberIds` restricted to nodes whose
///     lifecycle is `ON_DUTY` (or the snapshot-side equivalent of "fully joined").
///   - `healthyOnDutyCount` — `ON_DUTY` nodes whose `healthHint` is `HEALTHY`.
///   - `desiredCoreSize` — operator-driven target core size at the snapshot's epoch.
///
/// See `aether/docs/specs/cluster-generation-spec.md` §6.
public interface MembershipView {
    /// All node IDs the snapshot lists as core members, irrespective of lifecycle.
    Set<NodeId> coreMemberIds();

    /// Node IDs whose lifecycle is `ON_DUTY` (fully operational core members).
    Set<NodeId> onDutyMemberIds();

    /// Count of `ON_DUTY` members with `HEALTHY` health hint.
    int healthyOnDutyCount();

    /// Operator-declared target core size at the snapshot's epoch.
    int desiredCoreSize();

    /// Node IDs whose lifecycle atom was authored by the Cluster Topology Manager
    /// (i.e. provisioned through a cloud `ComputeProvider`). Only these nodes are
    /// eligible for CTM-driven auto-termination. Manually-seeded and unknown-source
    /// nodes are excluded.
    ///
    /// Default implementation returns the empty set — safe-by-default for views that
    /// do not carry per-member provisioning metadata.
    default Set<NodeId> ctmProvisionedNodeIds() {
        return Set.of();
    }

    /// Core-member node IDs that currently have no `NodeArtifactKey` atoms — i.e. nodes
    /// that carry no deployed slices. Used by CTM to prefer empty nodes as termination
    /// candidates under scale-down.
    ///
    /// Projected once during snapshot re-projection from committed `NodeArtifactKey`
    /// atoms (see `aether/docs/specs/cluster-generation-spec.md` §6). Reading this
    /// through the snapshot guarantees consistency with `coreMemberIds` at the same
    /// epoch — independent `DeploymentMap` subscribers can lag behind the snapshot.
    ///
    /// Default implementation returns the empty set — safe-by-default for views that
    /// do not carry artifact-presence metadata.
    default Set<NodeId> nodesWithoutSlices() {
        return Set.of();
    }

    /// Per-core-member lifecycle state at the snapshot's epoch. Used by `TopologyObserver`
    /// (RC1 Step 2) to derive the lifecycle-projection `MembershipDecision` variants
    /// (`NodeJoining` / `NodeDraining` / `NodeFailedDrain`) — variant emission diffs the
    /// previous snapshot's map against the current one.
    ///
    /// Default implementation returns the empty map — safe-by-default for views that do
    /// not carry lifecycle metadata (e.g. legacy / test stubs). Snapshot adapters in
    /// `aether-deployment` populate this from the slice-level `NodeLifecycleState`.
    default Map<NodeId, LifecycleState> lifecycleStates() {
        return Map.of();
    }

    /// Count of core members whose lifecycle is `JOINING` — capacity-in-progress that has
    /// not yet reached `ON_DUTY`. CTM deficit math subtracts this so a still-forming or
    /// recovering cluster (nodes SWIM-active but lagging in KV `ON_DUTY`) does not provision
    /// redundant replacements. Genuinely-stuck `JOINING` nodes are bounded by the enforcing
    /// `JoiningTimeout` reconciler rule, so counting them here is self-correcting.
    ///
    /// Derived from `lifecycleStates()`, so it inherits the empty-safe default for views
    /// that carry no lifecycle metadata.
    default int joiningCount() {
        return (int) lifecycleStates().values()
                                      .stream()
                                      .filter(state -> state == LifecycleState.JOINING)
                                      .count();
    }
}
