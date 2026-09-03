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
/// Kept as its own top-level gate — not folded into [ConfigValidator], which a sibling change is
/// editing elsewhere — so it can run on the RESOLVED peer count a node actually assembles at boot
/// (`Main`'s `peers.size()`, computed in `parsePeers` from `--peers=`/`CLUSTER_PEERS`/cloud
/// discovery/config, in that order). That is a different question from `ConfigValidator`'s
/// declarative `[cluster] nodes` TOML check, which only fires when a TOML loads and today never
/// aborts boot on its own (`Main#loadConfigFile` discards any validation failure into
/// `Option.none()`); this gate is the one that actually stops a sub-3-node start.
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
