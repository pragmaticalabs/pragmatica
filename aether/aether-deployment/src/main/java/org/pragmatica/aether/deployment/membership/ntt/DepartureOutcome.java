// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

/// How the departure push of a drain ended (#2034). The drain halts either way; this only says what it left behind.
public enum DepartureOutcome {
    /// No sole-copy key was left unacknowledged. Replicated keys may still be pending; their survivors keep them.
    COMPLETED,
    /// Sole-copy keys (no surviving replica holds them) were still unacknowledged, or the inventory could not be enumerated, when
    /// the window ended: the drain completed with data at risk.
    COMPLETED_WITH_DATA_AT_RISK
}
