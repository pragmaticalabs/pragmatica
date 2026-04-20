// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.generation;


/// Application-level QUIC connectivity state, mirrored at the wire boundary by
/// `org.pragmatica.cluster.metrics.ConnectivityState`. Lives in `aether/slice` because it
/// is the type every consumer in the slice layer speaks (the wire enum stays
/// behind the cluster/ module boundary).
///
/// See `aether/docs/specs/clustersync-refactor-spec.md` commit 1.
public enum ConnectivityReport {
    CONNECTED,
    DISCONNECTED,
    STALE
}
