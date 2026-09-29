// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

/// #1564: what a declared [ReplicationFactors] risks, raised at declaration. `loud` marks the warning the owner
/// ruled must be LOUD (a WARN log, the deploy response and a cluster event).
public enum ReplicationWarning {
    FACTOR_BELOW_THREE("replication-factor-below-three",
                       true,
                       "replication_factor is below 3: under terminal node removal, losing that many nodes loses the partition"),
    CONFIRMATION_EQUALS_FACTOR("confirmation-equals-replication-factor",
                               false,
                               "confirmation_factor equals replication_factor: losing any one replica refuses writes"),
    CONFIRMATION_OWNER_ONLY("confirmation-factor-owner-only",
                            false,
                            "confirmation_factor is 1: when the owner dies, records it acknowledged but had not yet replicated are lost");

    private final String code;
    private final boolean loud;
    private final String risk;

    ReplicationWarning(String code, boolean loud, String risk) {
        this.code = code;
        this.loud = loud;
        this.risk = risk;
    }

    public String code() {
        return code;
    }

    public boolean loud() {
        return loud;
    }

    /// The operator-facing sentence for `resource` declared with `factors`.
    public String message(String resource, ReplicationFactors factors) {
        return resource + " (" + factors + "): " + risk;
    }
}
