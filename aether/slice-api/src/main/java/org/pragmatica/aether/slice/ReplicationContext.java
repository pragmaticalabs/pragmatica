// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Result;


/// #1564: what a resource's replication declaration is resolved against — the committed cluster defaults
/// (`[replication]` of the cluster TOML) and the cluster's DESIRED core count (R7). Read from the committed
/// cluster config when a resource is deployed or activated, so every node resolves a declaration the same way.
public record ReplicationContext(ReplicationFactors defaults, int desiredCoreCount) {
    /// No committed cluster config: the built-in defaults, and no core count to check against.
    public static final ReplicationContext BUILT_IN = new ReplicationContext(ReplicationFactors.BUILT_IN, 0);

    public static ReplicationContext replicationContext(ReplicationFactors defaults, int desiredCoreCount) {
        return new ReplicationContext(defaults, desiredCoreCount);
    }

    /// `declaration` resolved against the defaults, then checked against the desired core count.
    public Result<ReplicationDeclaration.Resolved> resolve(ReplicationDeclaration declaration) {
        return declaration.resolve(defaults)
                          .flatMap(resolved -> resolved.factors()
                                                       .withinCoreCount(desiredCoreCount)
                                                       .map(_ -> resolved));
    }

    /// The current context, read from the committed cluster config. A failure means the committed config could
    /// not be read (for example unparseable), and the resource is refused rather than resolved against a guess.
    @FunctionalInterface
    public interface Source {
        Result<ReplicationContext> current();

        /// A fixed context (tests, and components built before a cluster config exists).
        static Source fixed(ReplicationContext context) {
            return () -> Result.success(context);
        }
    }
}
