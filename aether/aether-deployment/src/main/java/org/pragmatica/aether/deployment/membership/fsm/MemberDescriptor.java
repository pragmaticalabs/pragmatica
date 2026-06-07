// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.NodeAddress;

/// Last-wins per-member network descriptor: the peer's latest-known dial [`NodeAddress`] (preferring
/// `resolvedAddress`), plus its `role` and `source` labels. Orthogonal to the lifecycle FSM — it is
/// upserted whenever a NodeInfo-bearing SWIM observation (JoinAnnounced / MemberDiscovered) arrives,
/// and read by the [`MembershipFsm#desiredConnections`] / [`MembershipFsm#coreMembers`] projections.
///
/// `address` is [`Option`] because a member may be tracked (via a bare health edge) before any
/// NodeInfo observation has supplied its address; such a member is skipped by `desiredConnections`
/// until a descriptor lands. `role` / `source` default to empty strings ("unknown"); an absent or
/// blank role counts as a non-worker (included in the core set).
public record MemberDescriptor(Option<NodeAddress> address, String role, String source) {
    /// The unknown descriptor: no address, blank role (→ counts as core), blank source.
    public static final MemberDescriptor UNKNOWN = new MemberDescriptor(Option.none(), "", "");

    /// Build a last-wins descriptor from a NodeInfo-bearing observation: prefer the dial-resolved
    /// address and read the role / source labels (defaulting to blank when absent).
    public static MemberDescriptor fromNodeInfo(NodeInfo info) {
        return new MemberDescriptor(Option.option(info.resolvedAddress()),
                                    info.labels().getOrDefault(NodeInfo.LABEL_ROLE, ""),
                                    info.labels().getOrDefault(NodeInfo.LABEL_SOURCE, ""));
    }

    /// Whether this member is part of the connectable core: a role that is NOT the explicit literal
    /// `worker`. An unknown / blank role is included (an all-core cluster carries no role labels).
    public boolean isCore() {
        return !"worker".equals(role);
    }
}
