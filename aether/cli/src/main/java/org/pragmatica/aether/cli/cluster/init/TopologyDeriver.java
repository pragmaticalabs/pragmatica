// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster.init;

import org.pragmatica.lang.Result;


/// Splits a requested `--nodes N` into a CORE (consensus) tier and a WORKER tier.
///
/// #1019 — the previous table capped CORE at 5 and mapped `--nodes 5` to a THREE-node core, so
/// `aether cluster init --nodes 5` reported success while describing a 3-member consensus cluster,
/// and no value of `--nodes` could express a 7- or 9-member one. Cloud bootstrap provisions the CORE
/// tier only (`BootstrapPhaseProvision#CLOUD_BOOTSTRAP_ROLES`, RFC-0017 stage 7 — workers arrive
/// later via the stage-5 reconciler), so the derived core IS the consensus cluster the operator gets.
///
/// The rule is now "the largest supported consensus tier that fits, remainder to workers":
///
/// | `--nodes` | core | worker |
/// |-----------|------|--------|
/// | 5         | 5    | 0      |
/// | 6         | 5    | 1      |
/// | 7         | 7    | 0      |
/// | 8         | 7    | 1      |
/// | 9         | 9    | 0      |
/// | N > 9     | 9    | N − 9  |
///
/// CORE is bounded at [#MAXIMUM_CORE_NODES] because this tier is the quorum basis and every
/// consensus round is broadcast across it. The FLEET is deliberately unbounded — see
/// `ClusterConfig#UNBOUNDED` (#298) — so nodes past the cap become workers rather than an error.
public sealed interface TopologyDeriver {
    /// Owner ruling 2026-09-12: minimum 5. A 3-node cluster tolerates ZERO failures during
    /// maintenance — a rolling restart leaves 2 of 3, and any further fault loses quorum.
    int MINIMUM_TOTAL_NODES = 5;

    /// Largest consensus tier `--nodes` will derive. Beyond this, added nodes are workers.
    int MAXIMUM_CORE_NODES = 9;

    static Result<CoreWorkerSplit> derive(int totalNodes) {
        if (totalNodes < MINIMUM_TOTAL_NODES) {
            return new ClusterInitError.TooFewNodes(totalNodes).result();
        }

        return switch (totalNodes) {
            case 5, 6 -> CoreWorkerSplit.coreWorkerSplit(5, totalNodes - 5);
            case 7, 8 -> CoreWorkerSplit.coreWorkerSplit(7, totalNodes - 7);
            default -> CoreWorkerSplit.coreWorkerSplit(MAXIMUM_CORE_NODES, totalNodes - MAXIMUM_CORE_NODES);
        };
    }

    record unused() implements TopologyDeriver {}
}
