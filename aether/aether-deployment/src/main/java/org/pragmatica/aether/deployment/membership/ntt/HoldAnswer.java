// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

/// What a hold source answers for a drain episode: is this node's drain held open by a data hold? The declared order ends with
/// [#UNKNOWN] so a later codec enum can take it without reordering; precedence is the explicit [#rank], not the ordinal.
public enum HoldAnswer {
    HELD,
    NOT_HELD,
    UNKNOWN;
    /// Precedence: HELD 2 > UNKNOWN 1 > NOT_HELD 0.
    int rank() {
        return switch (this) {
            case HELD -> 2;
            case UNKNOWN -> 1;
            case NOT_HELD -> 0;
        };
    }
    /// The stronger of two answers by [#rank]; on a tie, the first.
    public static HoldAnswer strongest(HoldAnswer a, HoldAnswer b) {
        return a.rank() >= b.rank()
               ? a
               : b;
    }
}
