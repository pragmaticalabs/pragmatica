// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;


/// The two things a confirmed member death must reach, bound together so neither can be dropped
/// silently (#926, round 2).
///
/// Both sit on the ungated `MembershipFsm` DEAD edge, and both were previously written as two separate
/// statements inside an `AetherNode` boot lambda. An adversarial probe deleted the alert call from that
/// lambda and **all 1217 tests still passed** — the `AlertManager` behaviour was pinned in isolation,
/// but nothing pinned that the production call site actually reached it. A mutation that leaves every
/// gate green is an unpinned behaviour, not an independent one.
///
/// Extracting the pair into one named unit makes the composition itself testable: a test can drive
/// [`#onConfirmedDeparture`] against a real aggregator and a real alert manager and assert that BOTH
/// surfaces respond, so deleting either call turns it red. That does not pin the FSM-to-lambda wire —
/// only a booted node does that — but it removes the gap the probe found, which is that the two calls
/// were individually deletable with no signal at all.
public record NodeDepartureNotifier(ClusterEventAggregator aggregator, AlertManager alertManager, NodeId self) {
    public static NodeDepartureNotifier nodeDepartureNotifier(ClusterEventAggregator aggregator,
                                                              AlertManager alertManager,
                                                              NodeId self) {
        return new NodeDepartureNotifier(aggregator, alertManager, self);
    }

    /// Fan a confirmed departure out to both observability surfaces.
    ///
    /// The event goes to `CLUSTER_EVENTS` un-gated, so it survives a cluster with no leader. The alert
    /// goes to this node's own `/api/alerts`, which needs no leader, quorum, replica or partition
    /// ownership to be raised OR read back — unlike the events stream, whose read path prefers a
    /// possibly-dead remote replica. The alert self-suppresses for an announced departure; the event
    /// does not, because a graceful `NodeLeft` and this `NodeFailed` are both legitimate stream
    /// history, while a CRITICAL alert on a routine rolling restart is only noise.
    @Contract
    public void onConfirmedDeparture(NodeId departed) {
        aggregator.onConfirmedDeparture(departed);
        alertManager.onNodeFailed(departed, self);
    }
}
