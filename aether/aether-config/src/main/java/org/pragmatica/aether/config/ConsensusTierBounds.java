// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

/// The upper bound on the CONSENSUS tier, in ONE place because three modules enforce it (#1019).
///
/// Round 1 of #1019 put the figure in `CoreWorkerSplit` (`aether/cli`) and again in
/// [ConfigValidator], and the round-1 review found the bound held at `aether cluster init` and
/// NOWHERE else: `ClusterBootstrapConfigValidator` accepted a hand-written bootstrap config with a
/// derived core count of 11, and `ClusterTopologyManager#setDesiredCount` had a floor and no ceiling,
/// so a scale command could grow the consensus tier without limit. A cap that only one authoring
/// command applies is an authoring convention, not a bound.
///
/// This bounds the tier that every consensus round is BROADCAST ACROSS — not the fleet. Fleet size is
/// `ClusterConfig#maxNodes`, which #298 deliberately leaves UNBOUNDED (a default numeric fleet cap
/// silently refuses provisioning on any cluster already larger than it), so capacity beyond this
/// limit is added as workers rather than refused.
///
/// WHERE THIS IS ENFORCED (CTO ruling, session 19): at the two validators that gate a config being
/// CREATED or CHANGED — `ClusterBootstrapConfigValidator` (CL-04, REQ-3.3.3) and
/// `ClusterTopologyManager#setDesiredCount` — and at `CoreWorkerSplit`, which `aether cluster init`
/// and `scaffold` reach.
///
/// WHERE IT IS NOT: no NEW enforcement is added on the boot path. [ConfigValidator]'s `[cluster]
/// nodes` bound is pre-existing (it refused above 7 before this change) and is only RAISED to this
/// figure, never introduced — see that class for what a boot-time validation failure actually does,
/// which is not what it looks like.
public final class ConsensusTierBounds {
    /// Raised 7 -> 9 by #1019. Odd, so that no split of the tier is a tie.
    public static final int MAXIMUM_CORE_NODES = 9;

    private ConsensusTierBounds() {}
}
