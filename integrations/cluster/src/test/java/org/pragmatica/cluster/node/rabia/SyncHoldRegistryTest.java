package org.pragmatica.cluster.node.rabia;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;


/// Verifies Phase 2 PR-B leader-side `SyncHoldRegistry` semantics — register/clear, deadline
/// filtering (self-prune on read), idempotency, and clock-driven expiry.
class SyncHoldRegistryTest {
    private static final NodeId PEER_A = NodeId.nodeId("peer-a").unwrap();
    private static final NodeId PEER_B = NodeId.nodeId("peer-b").unwrap();

    @Test
    void activeHolds_freshRegistry_returnsEmptySet() {
        var registry = SyncHoldRegistry.syncHoldRegistry(() -> 0L);

        assertThat(registry.activeHolds()).isEmpty();
    }

    @Test
    void register_activeDeadline_appearsInActiveHolds() {
        var clock = new AtomicLong(1_000L);
        var registry = SyncHoldRegistry.syncHoldRegistry(clock::get);

        registry.register(PEER_A, 5_000L);

        assertThat(registry.activeHolds()).containsExactly(PEER_A);
    }

    @Test
    void register_multipleDistinctPeers_allActive() {
        var clock = new AtomicLong(1_000L);
        var registry = SyncHoldRegistry.syncHoldRegistry(clock::get);

        registry.register(PEER_A, 5_000L);
        registry.register(PEER_B, 6_000L);

        assertThat(registry.activeHolds()).containsExactlyInAnyOrder(PEER_A, PEER_B);
    }

    @Test
    void register_samePeerTwice_overwritesDeadline() {
        var clock = new AtomicLong(1_000L);
        var registry = SyncHoldRegistry.syncHoldRegistry(clock::get);

        registry.register(PEER_A, 2_000L);
        registry.register(PEER_A, 10_000L);
        // Advance past the FIRST deadline but before the second — still active.
        clock.set(3_000L);

        assertThat(registry.activeHolds()).containsExactly(PEER_A);
    }

    @Test
    void clear_afterRegister_removesFromActive() {
        var clock = new AtomicLong(1_000L);
        var registry = SyncHoldRegistry.syncHoldRegistry(clock::get);
        registry.register(PEER_A, 5_000L);

        registry.clear(PEER_A);

        assertThat(registry.activeHolds()).isEmpty();
    }

    @Test
    void clear_unknownPeer_isNoOp() {
        var registry = SyncHoldRegistry.syncHoldRegistry(() -> 0L);

        registry.clear(PEER_A);

        assertThat(registry.activeHolds()).isEmpty();
    }

    @Test
    void activeHolds_pastDeadline_excludesExpiredEntries() {
        var clock = new AtomicLong(1_000L);
        var registry = SyncHoldRegistry.syncHoldRegistry(clock::get);
        registry.register(PEER_A, 2_000L);
        registry.register(PEER_B, 10_000L);

        clock.set(5_000L);

        assertThat(registry.activeHolds()).containsExactly(PEER_B);
    }

    @Test
    void activeHolds_selfPrunesExpired_sizeReflectsPrune() {
        var clock = new AtomicLong(1_000L);
        var registry = SyncHoldRegistry.syncHoldRegistry(clock::get);
        registry.register(PEER_A, 2_000L);
        registry.register(PEER_B, 10_000L);
        clock.set(5_000L);

        // First call self-prunes expired PEER_A.
        registry.activeHolds();

        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void register_nullPeer_isNoOp() {
        var registry = SyncHoldRegistry.syncHoldRegistry(() -> 0L);

        registry.register(null, 1_000L);

        assertThat(registry.activeHolds()).isEmpty();
    }

    @Test
    void clear_nullPeer_isNoOp() {
        var registry = SyncHoldRegistry.syncHoldRegistry(() -> 0L);
        registry.register(PEER_A, 5_000L);

        registry.clear(null);

        assertThat(registry.activeHolds()).containsExactly(PEER_A);
    }
}
