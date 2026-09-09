// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.cluster.metrics;

import org.pragmatica.serialization.Codec;


/// QUIC-level connectivity state observed by a follower about a peer.
/// `CONNECTED` is the quiescent baseline; `DISCONNECTED` means the peer is
/// unreachable; `STALE` means the connection is up but no traffic has flowed
/// within the stale-detection window.
///
/// Top-level by design — see `HealthHintWire` for the tag-collision rationale.
@Codec
public enum ConnectivityState {
    CONNECTED,
    DISCONNECTED,
    STALE,
    /// Wire sentinel (#964): an ordinal this node cannot name decodes here instead of throwing.
    /// Descriptive only; nothing branches on it today.
    /// Must stay LAST — a new constant appended after it, or inserted before it, is read as UNKNOWN
    /// by an older node either way.
    UNKNOWN
}
