// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;

/// Lightweight membership descriptor consumed by `ClusterGenerationProjector` to build
/// `CoreMember`s. Decouples the projector from the KV value type `NodeLifecycleValue`:
/// only the state plus display address (host/port) are required to project a member, since
/// the synthesized path always defaults `joinedEpoch`/`observedCoreEpoch` to `Epoch.ZERO`
/// and `provisioningSource` to `UNKNOWN`.
public record MemberLifecycle(NodeLifecycleState state, String host, int port) {
    public static MemberLifecycle memberLifecycle(NodeLifecycleState state, String host, int port) {
        return new MemberLifecycle(state, host, port);
    }
}
