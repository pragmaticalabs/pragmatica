// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster.init;

import org.pragmatica.aether.config.ConsensusTierBounds;
import org.pragmatica.lang.Result;


/// The two tiers of a cluster, stated rather than inferred.
///
/// #1019 — this is the ONE place the supported-minimum POLICY of 5 is enforced, and it is reachable
/// only from `aether cluster init` and `aether cluster scaffold`, both of which CREATE configs and
/// sit on no boot path. That placement is the whole point: `ConfigValidator` and `ClusterSizeGate`
/// look like authoring gates and are not — both run on every node boot — so each keeps a STRUCTURAL
/// floor of 3 instead.
///
/// Their two boot-time mechanisms differ, and the round-1 review of #1019 found this comment eliding
/// that (S1). `ClusterSizeGate` genuinely refuses: `Main#enforceMinimumClusterSize` pipes its failure
/// into `Main#abortBoot`, so raising its floor would stop a running 3-node cluster from restarting.
/// `ConfigValidator` does NOT refuse: `Main#loadConfigFile` discards its failure into `Option.none()`,
/// so raising its floor would silently drop the node's config and boot it without one. Neither
/// outcome is acceptable, which is why the policy lives here — but "it would refuse to boot" is only
/// true of one of them.
///
/// The MAXIMUM is a different question and is NOT CLI-only: see [#MAXIMUM_CORE_NODES].
///
/// A previous design had the CLI take ONE number and infer the split. That inference was the defect:
/// the config models `[cluster.core]` and `[source.X.worker]` as independent quantities, the CLI
/// collapsed them into a total, and the round trip lost information — `--nodes 5` produced a THREE
/// node consensus tier. Both tiers are now given explicitly and nothing is derived.
public record CoreWorkerSplit(int core, int worker) {
    /// Owner ruling 2026-09-12. A 3-node core tolerates ZERO failures during maintenance: a rolling
    /// restart takes one node down, leaving 2 of 3, and any further fault loses quorum. 5 is the
    /// smallest core where a planned operation still leaves margin; 7 buys a second concurrent fault.
    public static final int MINIMUM_CORE_NODES = 5;
    /// Bounds the CONSENSUS tier only — every consensus round is broadcast across it. The FLEET is
    /// deliberately unbounded (`ClusterConfig#maxNodes`, #298: a default numeric cap silently refuses
    /// provisioning on any cluster already larger than it), so capacity beyond this is added as
    /// workers rather than refused.
    ///
    /// Unlike [#MINIMUM_CORE_NODES] this is NOT a CLI-only authoring rule. Round 1 enforced it here and
    /// nowhere else, so a hand-written bootstrap config with 11 cores validated and a scale command
    /// could grow the tier without limit. The figure and the list of enforcement points now live in
    /// `ConsensusTierBounds`.
    public static final int MAXIMUM_CORE_NODES = ConsensusTierBounds.MAXIMUM_CORE_NODES;

    public static Result<CoreWorkerSplit> coreWorkerSplit(int core, int worker) {
        if (core < MINIMUM_CORE_NODES) {
            return new ClusterInitError.TooFewCoreNodes(core).result();
        }

        if (core % 2 == 0) {
            return new ClusterInitError.InvalidTopology("core must be odd so that no split is a tie, got " + core).result();
        }

        if (core > MAXIMUM_CORE_NODES) {
            return new ClusterInitError.InvalidTopology("core must be at most " + MAXIMUM_CORE_NODES
                                                       + " (the consensus tier is broadcast to on every round; add capacity as workers, "
                                                       + "which are unbounded), got " + core).result();
        }

        if (worker < 0) {
            return new ClusterInitError.InvalidTopology("worker must be >= 0, got " + worker).result();
        }

        return Result.success(new CoreWorkerSplit(core, worker));
    }

    public int total() {
        return core + worker;
    }
}
