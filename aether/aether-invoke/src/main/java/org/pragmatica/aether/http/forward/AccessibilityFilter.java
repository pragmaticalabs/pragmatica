// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.forward;

import java.util.List;

import org.pragmatica.consensus.NodeId;


/// Data-path forwarder accessibility gate (silent-death fix). Narrows a candidate target list
/// to the nodes that are currently *reachable* per the membership tracker (NTT), independent of
/// the QUIC CONNECTED phase. On a hard node kill the dead node keeps its QUIC channel CONNECTED
/// until eviction, so `connectedPeers()` alone keeps the forwarder selecting it and burning the
/// full forward timeout per attempt. NTT reflects the death far sooner (SWIM + QUIC-disconnect
/// down-hysteresis); this SAM lets the forwarder consult it without depending on aether-deployment
/// (the node module binds the NTT-backed implementation via method reference).
///
/// Implementations MUST preserve input order (round-robin determinism in the forwarder).
@FunctionalInterface
public interface AccessibilityFilter {
    List<NodeId> keepOnlyAccessible(List<NodeId> candidates);
    /// Default no-op filter: returns candidates unchanged (back-compat for call sites and tests
    /// that do not wire NTT).
    AccessibilityFilter IDENTITY = candidates -> candidates;
}
