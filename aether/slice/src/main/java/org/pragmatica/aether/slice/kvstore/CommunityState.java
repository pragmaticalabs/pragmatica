// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.pragmatica.serialization.Codec;


/// Per-community lifecycle FSM state (leader-evaluated), the `state` field of
/// [AetherValue.CommunityValue]. Death of a community is the explicit `DISSOLVED` terminal fact,
/// not a disappearance (worker-membership-spec A10). Transitions (spec §3.3):
/// minted → `FORMING` → `ACTIVE` → (`DEGRADED` ↔) `DISSOLVING` → `DISSOLVED`.
@Codec
public enum CommunityState {
    FORMING,
    ACTIVE,
    DEGRADED,
    DISSOLVING,
    DISSOLVED
}
