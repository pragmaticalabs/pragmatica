// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// TOML binding shape for the `[membership]` section (membership v2 spec §14).
///
/// **Why a separate type.** The behavioural [`org.pragmatica.aether.deployment.membership.MembershipConfig`]
/// record lives in `aether-deployment`, which depends on `aether-config`. The root TOML
/// binder ([`AetherConfig`]) lives in `aether-config` and therefore cannot import the
/// deployment-side record without inverting the module dependency direction. This binding
/// captures the raw scalar values; [`org.pragmatica.aether.Main#run`] (which sits above
/// both modules in the dependency graph) lifts a present binding into the deployment-side
/// record before it is threaded into [`org.pragmatica.aether.node.AetherNodeConfig`].
///
/// **Field semantics** mirror the deployment-side record one-for-one:
/// - `nttDepartureTimeout` — NTT timer duration from SWIM `DepartedObserved` until
///   `TopologyUnhealthy`. Default: 15s.
/// - `quorumLossDrainThreshold` — duration `localQuorumCount` must stay below
///   `coreCount/2+1` before a [`org.pragmatica.aether.deployment.membership.ntt.QuorumLossIntent`]
///   is emitted. Default: 8s.
///
/// E2 Phase 2a (2026-05-28): the `nttObservation` migration-ramp feature flag is removed.
/// NTT/LocalQuorumWatcher/LeaderReconciler now wire unconditionally.
public record MembershipConfigBinding(TimeSpan nttDepartureTimeout, TimeSpan quorumLossDrainThreshold) {
    public static final TimeSpan DEFAULT_NTT_DEPARTURE_TIMEOUT = timeSpan(15).seconds();
    public static final TimeSpan DEFAULT_QUORUM_LOSS_DRAIN_THRESHOLD = timeSpan(8).seconds();

    public static MembershipConfigBinding membershipConfigBinding() {
        return new MembershipConfigBinding(DEFAULT_NTT_DEPARTURE_TIMEOUT, DEFAULT_QUORUM_LOSS_DRAIN_THRESHOLD);
    }
}
