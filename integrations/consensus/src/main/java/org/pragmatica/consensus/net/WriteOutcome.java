/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package org.pragmatica.consensus.net;

import org.pragmatica.consensus.NodeId;

/// Synchronous outcome of a per-peer message dispatch from [ClusterNetwork.sendOutcome].
///
/// Replaces the prior fire-and-forget `Unit` semantics for callers that need to react
/// immediately to transient or permanent transport failures (notably the DHT quorum
/// path). Each variant carries the peer id so the caller can correlate to a specific
/// target without external bookkeeping.
///
/// Semantics:
///
///   - [Sent]            — message handed off to the transport queue successfully. The
///                         caller should still await the receiver's reply via the
///                         existing message-routing path (this outcome only signals
///                         that the local enqueue succeeded).
///   - [BackpressureRefused] — the per-peer channel is at the netty high-watermark.
///                             Transient; the peer is logically reachable but cannot
///                             accept more bytes right now. Caller can retry later or
///                             route to another replica.
///   - [ConnectionDead]   — the peer's QUIC link is gone (stream missing, channel
///                          inactive). Permanent for this attempt; a new connection
///                          would need to be re-established.
///   - [NoPeerState]      — no `PeerState` exists for this peer id (peer was never
///                          registered or has been authoritatively removed).
///
/// Why an explicit type instead of `Result<Unit, Cause>`:
///   1. Different failure flavours need different upstream reactions (DHT exclude vs.
///      retry vs. log).
///   2. Pattern-matching keeps the call site short and exhaustive.
public sealed interface WriteOutcome {
    NodeId peerId();

    /// Helper — true for `Sent`, false for any refusal variant.
    default boolean isSent() {return this instanceof Sent;}

    record Sent(NodeId peerId) implements WriteOutcome {}

    record BackpressureRefused(NodeId peerId) implements WriteOutcome {}

    record ConnectionDead(NodeId peerId) implements WriteOutcome {}

    record NoPeerState(NodeId peerId) implements WriteOutcome {}
}
