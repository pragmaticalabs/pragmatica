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
    /// The explicit non-core role label. A member self-asserting this role is excluded from the
    /// connectable core set.
    private static final String ROLE_WORKER = "worker";
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
    ///
    /// `role` is a SELF-ASSERTED SWIM label — a node can claim its own role, and **nothing
    /// authenticates that claim**. This classification therefore trusts an unauthenticated input.
    ///
    /// This docstring previously justified that trust by asserting cluster admission is gated by
    /// `AETHER_CLUSTER_SECRET`. **That justification was false** (#715): the secret derives a
    /// deterministic CA and the CLIENT side verifies servers against it, but the QUIC server never
    /// calls `clientAuth(REQUIRE)` (Netty defaults to `ClientAuth.NONE`), and `handleHello` performs
    /// no cluster-identity check. Inbound admission is reachability-only, so an unauthorized node
    /// DOES reach this classification. The false premise is recorded rather than deleted because it
    /// is the kind of statement that talks a reader out of adding a check.
    ///
    /// Until #715 closes, treat the role label as untrusted input: it decides core-vs-worker
    /// membership, and a node that claims `worker` is excluded from the core set while one that
    /// claims nothing is included. The structured [`NodeInfo.NodeRole`] is a SEPARATE axis
    /// (transport active/passive); classification deliberately uses the self-asserted label.
    ///
    /// This docstring also claimed cryptographic role attestation was "tracked under #241". It is
    /// not: #241 is community topology lifecycle (seeding, growth policy, per-community FSM) and
    /// says nothing about attestation. The hardening that pointer implied was on the roadmap was
    /// not on it; **#747** is now its actual home. Note that #715 does NOT close it — a certificate
    /// proves cluster MEMBERSHIP, not role, so an admitted node can still assert any role it likes.
    public boolean isCore() {
        return isCoreRole(role);
    }

    /// Static form of [`#isCore`] for sites that carry the bare role label (the
    /// [`MembershipDeltaEdge`] payload): core iff the label is NOT the explicit literal
    /// `worker` — blank / unknown counts as core. Single source of the role-literal rule.
    public static boolean isCoreRole(String role) {
        return ! ROLE_WORKER.equals(role);
    }
}
