// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.cluster.metrics;

import org.pragmatica.serialization.Codec;


/// Wire-level health-hint observed by a follower's SWIM detector about a peer.
/// Mirrors the `aether/slice` `HealthHint` enum but lives here so the `cluster/`
/// module stays independent of the slice types; translated at the boundary.
///
/// Top-level by design — keeping it nested in `ClusterSyncMessage` produces a
/// deterministic codec tag that collides with `AetherKey.GovernorAnnouncementKey`.
@Codec
public enum HealthHintWire {
    HEALTHY,
    SUSPECTED,
    FAULTY
}
