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

/// Consensus-layer mirror of `aether/slice`'s `NodeLifecycleState`, exposed through
/// `MembershipView.lifecycleStates()` so that `TopologyObserver` (which lives in the
/// `integrations/consensus` module and must not depend on `aether/slice` types) can drive
/// `MembershipDecision.NodeJoining` / `NodeDraining` / `NodeDecommissioned` emission from the
/// snapshot-projected lifecycle map.
///
/// Step H/I collapse (2026-05-22): the prior 6-value alphabet (`JOINING / ON_DUTY / DRAINING /
/// DECOMMISSIONED / SHUTTING_DOWN / FAILED_DRAIN`) collapses to 4 to mirror the slice-layer
/// collapse. The three terminal variants are unified into `STOPPED`; the discriminator survives
/// on the slice-side `NodeLifecycleValue.stopReason()` sidecar (FORCED / GRACEFUL /
/// DRAIN_FAILED) — consensus-layer consumers that only need "is this peer terminal" treat all
/// three former values uniformly.
///
/// Snapshot adapters (e.g. `SnapshotMembershipView` in `aether-deployment`) translate the
/// slice-level enum into this one.
public enum LifecycleState {
    JOINING,
    ON_DUTY,
    DRAINING,
    STOPPED
}
