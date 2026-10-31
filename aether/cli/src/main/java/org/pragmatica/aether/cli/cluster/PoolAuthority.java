// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.function.Consumer;

import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// G1-g (owner ruling, option (b) of the apply/auto-heal double-fill): ONE decider creates nodes for a pool at a time. A pool is a
/// `(source, role)`. [WaveExecutor] asks, per action that would create nodes, who that decider is.
///
/// - [Actor#LEADER]: the running cluster's auto-heal fills the pool toward the COMMITTED target, so apply creates nothing and waits for
///   the leader to converge ([#awaitLeader]). Apply minting ids of its own beside the leader's is the double-fill: the two mint
///   different node ids, so neither the KV reservation nor a provider's name uniqueness refuses the second create.
/// - [Actor#APPLY]: nothing else fills the pool, so apply creates as before.
///
/// A pool the leader would not fill, or whose state cannot be read, is a refusal ([PoolRefused]); nothing is created beside it.
interface PoolAuthority {
    enum Actor {
        APPLY,
        LEADER
    }

    /// Who creates the nodes of `(source, role)` when the config asks for `wanted` of them there.
    Result<Actor> actorFor(String source, NodeRole role, int wanted);
    /// Blocks until the cluster reports `wantedInRole` live nodes of `role`, reporting each poll to `progress`.
    Result<Unit> awaitLeader(NodeRole role, int wantedInRole, Consumer<String> progress);

    /// The pool is not one apply may create in, or the cluster cannot say who may.
    record PoolRefused(String sourceName, NodeRole role, String reason) implements Cause {
        @Override
        public String message() {
            return "Apply refused to create nodes for " + sourceName
                 + "." + role.value()
                 + ": " + reason
                 + ". Nothing was created; the ledger is unchanged.";
        }
    }

    /// The cluster could not say who fills the pool (unreachable, or an answer apply cannot read): nothing is created on a guess.
    record PoolStateUnreadable(String what, Cause origin) implements Cause.Wrapped {
        @Override
        public String message() {
            return "Apply cannot tell who creates the nodes: it could not read " + what
                 + " (" + origin.message()
                 + "). Nothing was created; the ledger is unchanged. Retry when the cluster is reachable.";
        }
    }

    /// The leader did not bring the role to its target in time. Nothing apply did can be wrong here: it created nothing.
    record LeaderDidNotConverge(NodeRole role, int live, int wanted, long waitedSeconds) implements Cause {
        @Override
        public String message() {
            return "The leader's auto-heal has not provisioned the " + role.value()
                 + " nodes yet after " + waitedSeconds
                 + " s: " + live
                 + " live of " + wanted
                 + ". Apply created nothing. Check `aether cluster topology` and the provisioning diagnostics, then run `aether cluster apply --resume`.";
        }
    }
}
