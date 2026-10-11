// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// #782 — a cluster is at least three nodes; there is no supported single-node topology.
///
/// #1019 — THIS FLOOR IS STRUCTURAL AND STAYS AT 3. The owner ruling of 2026-09-12 sets the supported
/// minimum for NEW clusters at 5, and that is a POLICY minimum enforced where configs are CREATED
/// (`aether cluster init` / `scaffold`, via `CoreWorkerSplit`), never here.
///
/// The distinction is not bookkeeping, and getting it wrong is self-defeating. Three is where a
/// majority quorum stops existing at all — arithmetic. Five is where a cluster still has a fault
/// budget DURING MAINTENANCE: a rolling restart of a 3-node cluster leaves 2 of 3, and any further
/// fault loses quorum. Enforcing the policy figure HERE would make the first node of a rolling
/// upgrade refuse to start and fail to rejoin — forbidding, in the name of maintenance safety, the
/// exact maintenance operation the rule exists to protect — and would retroactively refuse to boot
/// clusters that are running today. That claim is about THIS gate specifically and is load-bearing:
/// `Main#enforceMinimumClusterSize` calls [#enforce] and pipes the failure into `Main#abortBoot`, so
/// a rejection here really does stop the process.
///
/// `ConfigValidator` keeps the same structural floor, but NOT for the same reason, and the round-1
/// review of #1019 found this paragraph asserting that it did (S1). `ConfigValidator` also runs on
/// every boot of a node that was GIVEN a config file (`--config=`), and since #2052 its failure
/// refuses the boot too: `Main#resolveConfig` fails and `Main#refuseConfig` exits 65 (before #2052 the
/// failure was discarded and the node booted on defaults). Raising ITS floor would therefore stop a
/// running 3-node cluster from restarting, exactly as raising this gate's would. Two gates, two floors
/// of 3, two mechanisms, both now refusing: this one on the CONFIGURED topology, that one on the file.
///
/// Kept as its own top-level gate — not folded into [ConfigValidator], which a sibling change is
/// editing elsewhere — so it can run on the CONFIGURED expected cluster size (`Main`'s
/// `expectedClusterSize`: the parsed `--peers=`/`CLUSTER_PEERS` list size, or the discovery/config
/// arm's `cluster().nodes()`), never on however many peers a boot attempt happened to RESOLVE — a
/// cloud-discovery majority-at-timeout boot can legitimately resolve fewer peers than configured,
/// and this gate must not abort that healthy boot. That is a different question from
/// `ConfigValidator`'s declarative `[cluster] nodes` TOML check, which only fires when a config file
/// is given and loads (its failure refuses that boot since #2052, with exit 65); this gate is the one
/// that stops a sub-3-node start with NO config file, from `--peers=`/`CLUSTER_PEERS`.
public final class ClusterSizeGate {
    private static final int MINIMUM_SUPPORTED_CLUSTER_SIZE = 3;

    private ClusterSizeGate() {}

    /// Rejects any expected size below the minimum supported topology. Returns `Unit` rather than
    /// the resolved size — callers already hold the size they passed in; the point of this call is
    /// solely to fail when it is too small.
    public static Result<Unit> enforce(int expectedSize) {
        return expectedSize < MINIMUM_SUPPORTED_CLUSTER_SIZE
               ? ClusterSizeError.clusterTooSmall(expectedSize).result()
               : Result.unitResult();
    }

    public sealed interface ClusterSizeError extends Cause {
        record ClusterTooSmall(int size) implements ClusterSizeError {
            @Override
            public String message() {
                return "Expected cluster size " + size
                     + " is not a supported topology: a cluster is "
                     + "at least three nodes. For a single machine, run the documented three-container "
                     + "quick start (docs/operators/docker-deployment.md, section "
                     + "\"Single machine (three containers)\") instead of one node.";
            }
        }

        static ClusterSizeError clusterTooSmall(int size) {
            return new ClusterTooSmall(size);
        }
    }
}
