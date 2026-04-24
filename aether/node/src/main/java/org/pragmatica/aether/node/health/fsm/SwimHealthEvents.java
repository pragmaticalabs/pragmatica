// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.health.fsm;

import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.swim.GossipEncryptor;
import org.pragmatica.swim.SwimMember;
import org.pragmatica.swim.SwimProtocol;
import org.pragmatica.swim.SwimTransport;

/// Domain event vocabulary for the SWIM health-detector FSM. Each event is a record and is
/// dispatched to the FSM via [`SwimHealthContext#dispatch`]. Events drive lifecycle transitions
/// and per-peer health accounting; no event causes direct state mutation outside the FSM.
public sealed interface SwimHealthEvents {

    /// Requested by the public `start()` entry point. Moves the FSM from `Stopped` to `Starting`
    /// while the caller performs the synchronous-to-asynchronous transport/protocol creation.
    record StartRequested() implements SwimHealthEvents {}

    /// SWIM transport and protocol creation succeeded. Carries the live collaborators — they
    /// become fields on the `Running` state record so handlers read them by reference rather than
    /// through atomic-reference holders.
    record ProtocolReady(SwimProtocol swim, SwimTransport transport, GossipEncryptor encryptor)
        implements SwimHealthEvents {}

    /// Transport/protocol creation failed — FSM returns to `Stopped`.
    record StartFailed() implements SwimHealthEvents {}

    /// Requested by the public `stop()` entry point.
    record StopRequested() implements SwimHealthEvents {}

    /// A peer is now connected (QUIC Hello). If the NodeInfo is present, it carries the freshly
    /// learned address for dynamic peers that are not in the static `topologyConfig`. The FSM
    /// re-adds the peer to SWIM membership (or markAlive) and emits a HEALTHY hint.
    record PeerConnected(NodeId peer, Option<NodeInfo> info) implements SwimHealthEvents {}

    /// SWIM membership: peer joined.
    record PeerJoined(SwimMember member) implements SwimHealthEvents {}

    /// SWIM membership: peer suspect.
    record PeerSuspect(SwimMember member) implements SwimHealthEvents {}

    /// SWIM membership: peer faulty. The handler evaluates the rolling-window local-disconnect
    /// check; on faulty-peer-is-current-leader, routes DisconnectNode locally to unblock
    /// re-election (see [`SwimHealthState.Running`]).
    record PeerFaulty(SwimMember member) implements SwimHealthEvents {}

    /// SWIM membership: peer left.
    record PeerLeft(NodeId peer) implements SwimHealthEvents {}

    /// External leader notification. Updates the authoritative `currentLeader` snapshot carried
    /// on the `Running` / `LocalDisconnect` state records. Pushed by higher layers that subscribe
    /// to `LeaderNotification.LeaderChange`.
    record LeaderChanged(Option<NodeId> leader) implements SwimHealthEvents {}

    /// Report a health hint for a peer that bypasses the membership callback path (used by
    /// `onNodeConnected` after the FSM has seeded / marked-alive the peer).
    record ReportHint(NodeId peer, HealthHint hint) implements SwimHealthEvents {}
}
