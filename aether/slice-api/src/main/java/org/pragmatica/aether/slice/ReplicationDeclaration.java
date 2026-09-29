// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.none;


/// #1564: `replication_factor` and `confirmation_factor` as a resource section declares them, before the cluster
/// defaults apply. [#resolve] is the one path from a declaration to [ReplicationFactors] for every stream,
/// durable topic and durable entity (R5): the "RF below 3 only when declared" rule and the warnings live here,
/// because only a declaration knows which values the resource set itself.
public record ReplicationDeclaration(Option<Integer> replicationFactor, Option<Integer> confirmationFactor) {
    public static final String FACTOR_KEY = "replication_factor";
    public static final String CONFIRMATION_KEY = "confirmation_factor";
    /// Nothing declared: every value comes from the defaults (the management create path, #1564).
    public static final ReplicationDeclaration NONE = new ReplicationDeclaration(none(), none());

    public static ReplicationDeclaration replicationDeclaration(Option<Integer> replicationFactor,
                                                                Option<Integer> confirmationFactor) {
        return new ReplicationDeclaration(replicationFactor, confirmationFactor);
    }

    /// RF is the declared value, else the default's. CF is the declared value, else `min(defaultCF, RF)` (R4):
    /// an explicit CF above RF is refused, a defaulted one follows a smaller declared RF down. An RF below 3 is
    /// refused unless the resource declared it.
    public Result<Resolved> resolve(ReplicationFactors defaults) {
        var factor = replicationFactor.or(defaults.replicationFactor());
        var confirmation = confirmationFactor.or(Math.min(defaults.confirmationFactor(), factor));

        if (replicationFactor.isEmpty() && factor < ReplicationFactors.RECOMMENDED_MINIMUM_FACTOR) {
            return new ReplicationFactorsError.ImplicitFactorBelowThree(factor).result();
        }

        return ReplicationFactors.replicationFactors(factor, confirmation).map(factors -> new Resolved(factors,
                                                                                                       factors.warnings(replicationFactor.isPresent())));
    }

    /// A resolved policy and the warnings its declaration raised.
    public record Resolved(ReplicationFactors factors, List<ReplicationWarning> warnings) {
        public Resolved {
            warnings = List.copyOf(warnings);
        }
    }
}
