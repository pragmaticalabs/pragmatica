// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// #782 — a cluster is at least three nodes; there is no supported single-node topology.
///
/// #1019 / owner ruling 2026-09-12 — that floor is now FIVE. A 3-node cluster tolerates ZERO
/// failures during maintenance: a rolling restart takes one node down, leaving 2 of 3, and any
/// further fault loses quorum. Maintenance is planned and routine, so a 3-node cluster spends a
/// predictable fraction of its life with no fault budget at all. 5 is the smallest size where a
/// planned operation still leaves margin.
///
/// BREAKING, and the failure mode deserves naming: this gate runs at node BOOT, so a cluster already
/// running on 3 nodes does not merely fail to validate a new config — its nodes refuse to start. Scale
/// to 5 BEFORE upgrading; the message below says so, because a node that exits on restart with an
/// unactionable reason is worse than the topology it is refusing.
///
/// Kept as its own top-level gate — not folded into [ConfigValidator], which a sibling change is
/// editing elsewhere — so it can run on the CONFIGURED expected cluster size (`Main`'s
/// `expectedClusterSize`: the parsed `--peers=`/`CLUSTER_PEERS` list size, or the discovery/config
/// arm's `cluster().nodes()`), never on however many peers a boot attempt happened to RESOLVE — a
/// cloud-discovery majority-at-timeout boot can legitimately resolve fewer peers than configured,
/// and this gate must not abort that healthy boot. That is a different question from
/// `ConfigValidator`'s declarative `[cluster] nodes` TOML check, which only fires when a TOML loads
/// and today never aborts boot on its own (`Main#loadConfigFile` discards any validation failure
/// into `Option.none()`); this gate is the one that actually stops a sub-3-node start.
public final class ClusterSizeGate {
    private static final int MINIMUM_SUPPORTED_CLUSTER_SIZE = 5;

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
                     + " is below the supported minimum of " + MINIMUM_SUPPORTED_CLUSTER_SIZE
                     + " nodes, so this node is refusing to start. A " + size
                     + "-node cluster has no fault budget during maintenance: a rolling restart takes "
                     + "one node down and any further fault then loses quorum. Scale the cluster to "
                     + MINIMUM_SUPPORTED_CLUSTER_SIZE
                     + " nodes (7 recommended) and retry; if you are upgrading an existing "
                     + "smaller cluster, scale it BEFORE upgrading. For a single machine, run the "
                     + "documented five-container quick start (docs/operators/docker-deployment.md, "
                     + "section \"Single machine (five containers)\").";
            }
        }

        static ClusterSizeError clusterTooSmall(int size) {
            return new ClusterTooSmall(size);
        }
    }
}
