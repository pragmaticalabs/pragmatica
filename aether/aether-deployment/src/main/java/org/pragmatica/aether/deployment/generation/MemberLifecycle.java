// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

/// Lightweight membership descriptor consumed by `ClusterGenerationProjector` to build
/// `CoreMember`s. Decouples the projector from any KV value type. Membership-v2 finale: the
/// synthetic per-node lifecycle enum was removed — presence in the member set IS membership, so
/// this carries only the display address (host/port). The projector always defaults
/// `joinedEpoch`/`observedCoreEpoch` to `Epoch.ZERO` and `provisioningSource` to `UNKNOWN`.
public record MemberLifecycle(String host, int port) {
    public static MemberLifecycle memberLifecycle(String host, int port) {
        return new MemberLifecycle(host, port);
    }
}
