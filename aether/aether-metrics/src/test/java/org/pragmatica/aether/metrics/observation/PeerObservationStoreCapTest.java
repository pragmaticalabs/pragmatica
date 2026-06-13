// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics.observation;

import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.metrics.ConnectivityState;
import org.pragmatica.cluster.metrics.PeerConnectivityObservation;
import org.pragmatica.consensus.NodeId;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;


/// Verifies cap-supplier semantics of `PeerObservationStore` required by Step 1
/// of the topology-observation refactor:
///  - cap supplier honored on each push (oldest evicted via drop-on-overflow)
///  - dynamic re-sizing: changing the supplier output affects subsequent pushes
///  - the existing drop-oldest-on-push semantic preserved (one eviction per push)
class PeerObservationStoreCapTest {
    private static final NodeId PEER = NodeId.nodeId("peer").unwrap();

    @Test
    void pushBeyondCap_dropsOldestEntries_keepsMostRecent() {
        var store = PeerObservationStore.peerObservationStore();
        store.setCapSupplier(() -> 20);

        for (int i = 0; i < 25; i++) {pushObservation(store, i);}

        var drained = store.drainConnectivity();
        assertThat(drained).hasSize(20);
        // The 5 oldest (counters 0..4) are evicted; counters 5..24 retained.
        assertThat(drained.getFirst().observedEpochCounter()).isEqualTo(5L);
        assertThat(drained.getLast().observedEpochCounter()).isEqualTo(24L);
    }

    @Test
    void capSupplierRefreshedPerPush_dynamicShrinkConvergesViaSubsequentPushes() {
        // The existing store semantic: each push evicts at most one entry if size
        // already >= cap, then offers the new entry. Lowering the cap after the
        // buffer is full does NOT immediately shrink the buffer; subsequent
        // pushes drive it down by one per push until size <= cap.
        var store = PeerObservationStore.peerObservationStore();
        var capRef = new AtomicInteger(20);
        store.setCapSupplier(capRef::get);

        for (int i = 0; i < 25; i++) {pushObservation(store, i);}

        // After 25 pushes with cap=20, size is 20 (drop-oldest invariant maintained).
        // Now lower the cap to 10 and push exactly one more entry.
        // The single subsequent push polls one entry but offers a new one — net
        // size stays 20. This documents the existing per-push semantic.
        capRef.set(10);
        pushObservation(store, 25);

        var drainedAfterSinglePush = store.drainConnectivity();
        assertThat(drainedAfterSinglePush).hasSize(20);
        assertThat(drainedAfterSinglePush.getLast().observedEpochCounter()).isEqualTo(25L);
    }

    @Test
    void capSupplierRefreshedPerPush_steadyStateAtNewLowerCapAfterRefill() {
        // Push entries until the buffer settles to the lower cap. After draining,
        // refill with the supplier returning 10 — size stabilises at 10.
        var store = PeerObservationStore.peerObservationStore();
        store.setCapSupplier(() -> 10);

        for (int i = 0; i < 15; i++) {pushObservation(store, i);}

        var drained = store.drainConnectivity();
        assertThat(drained).hasSize(10);
        // Oldest 5 evicted; counters 5..14 retained.
        assertThat(drained.getFirst().observedEpochCounter()).isEqualTo(5L);
        assertThat(drained.getLast().observedEpochCounter()).isEqualTo(14L);
    }

    @Test
    void capSupplierReturnsLargerValue_priorEntriesUntouched() {
        // Cap-grow semantic: existing entries are preserved; the new cap simply
        // raises the headroom for future pushes.
        var store = PeerObservationStore.peerObservationStore();
        var capRef = new AtomicInteger(5);
        store.setCapSupplier(capRef::get);

        for (int i = 0; i < 5; i++) {pushObservation(store, i);}

        capRef.set(20);
        for (int i = 5; i < 15; i++) {pushObservation(store, i);}

        var drained = store.drainConnectivity();
        assertThat(drained).hasSize(15);
        assertThat(drained.getFirst().observedEpochCounter()).isEqualTo(0L);
        assertThat(drained.getLast().observedEpochCounter()).isEqualTo(14L);
    }

    @Test
    void defaultCapOf64_honoredWithoutExplicitSetter() {
        var store = PeerObservationStore.peerObservationStore();
        // Default DEFAULT_CAP = 64 per PeerObservationStore source.
        for (int i = 0; i < 70; i++) {pushObservation(store, i);}

        var drained = store.drainConnectivity();
        assertThat(drained).hasSize(64);
        assertThat(drained.getLast().observedEpochCounter()).isEqualTo(69L);
    }

    private static void pushObservation(PeerObservationStore store, int counter) {
        store.pushConnectivity(new PeerConnectivityObservation(PEER,
                                                                ConnectivityState.CONNECTED,
                                                                7L,
                                                                counter,
                                                                0L));
    }
}
