// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// See LICENSE in the repository root for full terms.
package org.pragmatica.cluster.metrics;

import org.pragmatica.serialization.Codec;


/// How a held drain episode ended, as reported in [HeldReport#ended()].
/// `NONE` means the episode is still open; `DELIVERED` means every owed entry reached its destination;
/// `LOSS_ACCEPTED` means the episode was closed with undelivered entries accepted as lost.
///
/// Top-level by design — see `HealthHintWire` for the tag-collision rationale.
@Codec
public enum HeldEnd {
    NONE,
    DELIVERED,
    LOSS_ACCEPTED,
    /// Wire sentinel (#964): an ordinal this node cannot name decodes here instead of throwing.
    /// Descriptive only; nothing branches on it today.
    /// Must stay LAST — a new constant appended after it, or inserted before it, is read as UNKNOWN
    /// by an older node either way.
    UNKNOWN
}
