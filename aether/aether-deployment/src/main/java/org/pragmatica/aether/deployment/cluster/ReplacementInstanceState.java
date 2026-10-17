// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

/// #1049 — what the compute provider reports about the instance behind an in-flight auto-heal
/// replacement, as answered by [`ClusterTopologyManager#replacementInstanceState`]. The
/// `LeaderReconciler` tracks a replacement by this state instead of by a timer: it keeps the
/// in-flight entry while the instance exists, drops it on a provider-reported failure or deletion,
/// and falls back to the per-source hard ceiling whenever the provider cannot answer.
///
/// The lookup is by the replacement's node id (the `aether.node-id` tag every provider stamps at
/// create), not by provider instance id — the in-flight map and the prior leader's retained
/// dispatched set both carry only node ids, so no new wire field or KV record is needed for a new
/// leader to ask the same question.
public enum ReplacementInstanceState {
    /// The provider lists an instance for the node that is provisioning or running — a replacement is
    /// genuinely on its way.
    PRESENT,
    /// Every instance the provider lists for the node is stopping or terminated — the boot failed.
    FAILED,
    /// The provider answered and lists NO instance for the node. A deletion once the instance has been
    /// observed [#PRESENT]. For an instance never observed, only once the reconciler has counted enough
    /// consecutive successful absent listings over its first-listing floor (`LeaderReconciler`): before
    /// that, it may be a provider listing that lags creation.
    ABSENT,
    /// The provider could not answer — the listing failed, no compute provider is wired, or every listed
    /// instance is in a status the provider could not state (`InstanceStatus.Unknown`) and none is
    /// provisioning or running. Never read as existing or as gone, and never counted as an absence: the
    /// entry is kept until the hard ceiling.
    UNKNOWN
}
