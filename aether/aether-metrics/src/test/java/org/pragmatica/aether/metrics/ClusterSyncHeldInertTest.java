// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.aether.metrics.DirectoryFixtures.RecordingNetwork;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
import org.pragmatica.cluster.metrics.DirectoryVersion;
import org.pragmatica.cluster.metrics.HeldEnd;
import org.pragmatica.cluster.metrics.HeldReport;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// This change is inert: nothing produces `HELD` yet. The only producer of this node's reported state is the holder, which moves
/// SYNCING, READY, DRAINING and nothing else; the only producer of a pong's report is the collector's supplier, which nothing wires, so
/// every pong carries none. A later step that wires either makes these tests fail on purpose and replaces them.
/// What this class can and cannot detect: it drives the holder and a hand-built collector, so it sees a change INSIDE them (a holder
/// that produces HELD; a collector whose default supplier returns a report). It cannot see production wiring that sets a supplier on
/// the collector from outside; that is pinned on the assembled node's source by `ClusterSyncWiringTest#assembly_wiresNoHeldReportSupplier`
/// (in `aether/node`). The step that wires the reporter must replace both.
/// Mutations: let `onDrainStarted` return `HELD`; make the collector's default supplier return a report; drop `heldReportSupplier.get()`
/// from the pong (the wired-supplier test).
class ClusterSyncHeldInertTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId CORE = NodeId.nodeId("core").unwrap();

    private static HeldReport report() {
        return HeldReport.heldReport("episode-1", 3L, 0L, "digest", 1000L, List.of("k1"), HeldEnd.NONE);
    }

    private static ClusterSyncPong pongToPing(ClusterSyncCollector responder, RecordingNetwork network) {
        responder.onClusterSyncPing(DirectoryFixtures.ping(SELF, 5L, false, DirectoryVersion.NONE, Option.none()));

        return network.sentTo(SELF, ClusterSyncPong.class)
                      .getFirst();
    }

    @Test
    void holder_neverReportsHeld_acrossEveryTransition() {
        var active = new AtomicBoolean(false);
        var holder = NodeReportedStateHolder.nodeReportedStateHolder(active::get);
        var seen = new ArrayList<NodeReportedState>();

        seen.add(holder.current());
        active.set(true);
        seen.add(holder.current());
        holder.onSubsystemsReady();
        seen.add(holder.current());
        holder.onDrainStarted();
        seen.add(holder.current());

        assertThat(seen).doesNotContain(NodeReportedState.HELD);
        assertThat(seen.getLast()).isEqualTo(NodeReportedState.DRAINING);
    }

    @Test
    void collector_withNoSupplierWired_pongCarriesNoHeldReport() {
        var network = new RecordingNetwork(Set.of(SELF));
        var responder = ClusterSyncCollector.clusterSyncCollector(CORE, network);

        assertThat(pongToPing(responder, network).heldReport()).isEqualTo(Option.none());
    }

    /// The guard in `setHeldReportSupplier`: a null supplier means no report (the default), never a failure on the pong path.
    @Test
    void collector_withANullSupplier_pongCarriesNoHeldReport() {
        var network = new RecordingNetwork(Set.of(SELF));
        var responder = ClusterSyncCollector.clusterSyncCollector(CORE, network);

        responder.setHeldReportSupplier(() -> Option.some(report()));
        responder.setHeldReportSupplier(null);

        assertThat(pongToPing(responder, network).heldReport()).isEqualTo(Option.none());
    }

    @Test
    void collector_withSupplierWired_pongCarriesTheReport() {
        var network = new RecordingNetwork(Set.of(SELF));
        var responder = ClusterSyncCollector.clusterSyncCollector(CORE, network);

        responder.setHeldReportSupplier(() -> Option.some(report()));

        assertThat(pongToPing(responder, network).heldReport()).isEqualTo(Option.some(report()));
    }
}
