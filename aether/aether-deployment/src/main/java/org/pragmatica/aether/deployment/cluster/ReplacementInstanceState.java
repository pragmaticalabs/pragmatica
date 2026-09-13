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
    /// observed [#PRESENT], or once the reconciler's first-listing grace has passed since the replacement
    /// was dispatched: inside that grace a never-seen absence may be a create call still outstanding or a
    /// provider listing that lags creation, so the reconciler treats it like [#UNKNOWN].
    ABSENT,
    /// The provider could not answer — the listing failed, or no compute provider is wired. Never read
    /// as existing or as gone: the entry is kept until the hard ceiling.
    UNKNOWN
}
