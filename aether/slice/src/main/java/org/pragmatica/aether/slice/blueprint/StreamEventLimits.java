// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.consensus.net.OutboundMessageLimit;


/// The largest `max-event-size` a stream may declare (#1937): one event must fit ONE transport frame, because replication
/// and catch-up both ship a single oversized event alone in its own message (a chunk never splits an event).
///
/// The ceiling is [OutboundMessageLimit#MAX_TRANSFER_BYTES] (the 32 MiB frame less its reserve, the bound every whole
/// message is validated against) minus [#EVENT_ENVELOPE_RESERVE_BYTES]. The reserve is derived from the code, not guessed:
/// `StreamEventFrameBoundTest` in `aether/node` encodes the real `ReplicateEvents` and `CatchupResponse` messages through the
/// node's codec registry with worst-case names and a history, and pins that their non-payload bytes stay inside it.
public interface StreamEventLimits {
    /// Bytes reserved for everything in a one-event replication or catch-up message that is not the event. Measured:
    /// a `ReplicateEvents` envelope is 324 bytes and a `CatchupResponse` 306 bytes plus 72 per history entry, for a stream
    /// name of 192 characters and a node id of 65.
    long EVENT_ENVELOPE_RESERVE_BYTES = 4096L;
    /// The ceiling a declared `max-event-size` may not exceed.
    long MAX_EVENT_SIZE_BYTES = OutboundMessageLimit.MAX_TRANSFER_BYTES - EVENT_ENVELOPE_RESERVE_BYTES;
}
