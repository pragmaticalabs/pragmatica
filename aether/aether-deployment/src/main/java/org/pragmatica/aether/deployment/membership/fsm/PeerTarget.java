// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.net.tcp.NodeAddress;


/// A desired-connection target published by [`MembershipFsm#desiredConnections`]: a counted, core
/// (non-explicit-worker) member paired with its latest-known dial address. The transport executor
/// consumes the resulting set to drive its connect/disconnect reconciliation, dialing exactly the
/// members the FSM considers part of the live core whose address is known.
public record PeerTarget(NodeId id, NodeAddress address) {
    /// Factory for a desired-connection target binding a member `id` to its dial `address`.
    public static PeerTarget peerTarget(NodeId id, NodeAddress address) {
        return new PeerTarget(id, address);
    }
}
