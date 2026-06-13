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


public sealed interface SwimHealthEvents {
    record StartRequested() implements SwimHealthEvents {}

    record ProtocolReady(SwimProtocol swim, SwimTransport transport, GossipEncryptor encryptor) implements SwimHealthEvents {}

    record StartFailed() implements SwimHealthEvents {}

    record StopRequested() implements SwimHealthEvents {}

    record PeerConnected(NodeId peer, Option<NodeInfo> info) implements SwimHealthEvents {}

    record PeerJoined(SwimMember member) implements SwimHealthEvents {}

    record PeerSuspect(SwimMember member) implements SwimHealthEvents {}

    /// SWIM declared `member` FAULTY. `firstHand` carries verdict provenance (P1
    /// death-path co-confirmation): `true` when THIS node's own probe cycle timed out,
    /// `false` when the verdict was a (locally corroborated) gossip dissemination. A
    /// second-hand FAULTY that was NOT locally corroborated never reaches this event —
    /// it is downgraded to SUSPECT inside `SwimProtocol`.
    record PeerFaulty(SwimMember member, boolean firstHand) implements SwimHealthEvents {}

    record PeerLeft(NodeId peer) implements SwimHealthEvents {}

    record LeaderChanged(Option<NodeId> leader) implements SwimHealthEvents {}

    record ReportHint(NodeId peer, HealthHint hint) implements SwimHealthEvents {}
}
