// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.lang.Result;


/// G1-g: the one place an apply action that would CREATE nodes asks who the pool's decider is ([PoolAuthority]) before it creates.
/// The leader's pool: create nothing, wait for the leader, report the wait. Apply's pool: create. A refusal creates nothing.
///
/// Only CLOUD and DOCKER sources are asked: they are the sources a creation exists for (a FORGE or SSH action registers hosts and
/// creates no node), and an action with nothing missing creates nothing.
final class PoolGate {
    private PoolGate() {}

    /// One `(source, role)` pool as an action sees it: `missing` nodes to create, `wanted` the config's count for the pool, `wantedInRole`
    /// the config's count for the role across all sources (what the cluster's live count of the role is waited up to).
    record Pool(String source, SourceType type, NodeRole role, int missing, int wanted, int wantedInRole) {
        boolean createsNodes() {
            return missing > 0 && (type == SourceType.CLOUD || type == SourceType.DOCKER);
        }
    }

    static Result<List<ProvisionedNode>> through(PoolAuthority authority,
                                                 Pool pool,
                                                 Consumer<String> progress,
                                                 Supplier<Result<List<ProvisionedNode>>> create) {
        return pool.createsNodes()
               ? authority.actorFor(pool.source(),
                                    pool.role(),
                                    pool.wanted())
                          .flatMap(actor -> actor == PoolAuthority.Actor.APPLY
                                            ? create.get()
                                            : waitForLeader(authority, pool, progress))
               : create.get();
    }

    private static Result<List<ProvisionedNode>> waitForLeader(PoolAuthority authority,
                                                               Pool pool,
                                                               Consumer<String> progress) {
        progress.accept(pool.source()
                       + "." + pool.role().value()
                       + ": the leader's auto-heal fills this pool; apply creates nothing here");

        return authority.awaitLeader(pool.role(),
                                     pool.wantedInRole(),
                                     progress)
                        .map(_ -> List.<ProvisionedNode> of());
    }
}
