package org.pragmatica.aether.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.storage.StorageReadinessGate.storageReadinessGate;

class StorageReadinessGateTest {

    private StorageReadinessGate gate;

    @BeforeEach
    void setUp() {
        gate = storageReadinessGate();
    }

    @Nested
    class InitialStateTests {

        @Test
        void state_initiallyLoadingSnapshot() {
            assertThat(gate.state()).isEqualTo(ReadinessState.LOADING_SNAPSHOT);
        }

        @Test
        void isReadReady_beforeSnapshotLoaded_returnsFalse() {
            assertThat(gate.isReadReady()).isFalse();
        }

        @Test
        void isWriteReady_beforeConsensusSynced_returnsFalse() {
            assertThat(gate.isWriteReady()).isFalse();
        }
    }

    @Nested
    class SnapshotLoadedTests {

        @Test
        void snapshotLoaded_transitionsToSnapshotLoaded() {
            gate.snapshotLoaded();

            assertThat(gate.state()).isEqualTo(ReadinessState.SNAPSHOT_LOADED);
        }

        @Test
        void snapshotLoaded_makesReadReady() {
            gate.snapshotLoaded();

            assertThat(gate.isReadReady()).isTrue();
        }

        @Test
        void snapshotLoaded_idempotent_secondCallNoOp() {
            gate.snapshotLoaded();
            gate.snapshotLoaded();

            assertThat(gate.state()).isEqualTo(ReadinessState.SNAPSHOT_LOADED);
            assertThat(gate.isReadReady()).isTrue();
        }
    }

    @Nested
    class ConsensusSyncedTests {

        @Test
        void consensusSynced_transitionsToReady() {
            gate.snapshotLoaded();
            gate.consensusSynced();

            assertThat(gate.state()).isEqualTo(ReadinessState.READY);
        }

        @Test
        void consensusSynced_makesWriteReady() {
            gate.snapshotLoaded();
            gate.consensusSynced();

            assertThat(gate.isWriteReady()).isTrue();
        }

        @Test
        void consensusSynced_idempotent_secondCallNoOp() {
            gate.snapshotLoaded();
            gate.consensusSynced();
            gate.consensusSynced();

            assertThat(gate.state()).isEqualTo(ReadinessState.READY);
            assertThat(gate.isWriteReady()).isTrue();
        }
    }

    @Nested
    class AwaitTests {

        @Test
        void awaitReadReady_afterSnapshotLoaded_resolves() {
            gate.snapshotLoaded();

            gate.awaitReadReady().await().unwrap();
        }

        @Test
        void awaitWriteReady_afterConsensusSynced_resolves() {
            gate.snapshotLoaded();
            gate.consensusSynced();

            gate.awaitWriteReady().await().unwrap();
        }
    }

    @Nested
    class OutOfOrderTests {

        @Test
        void consensusSynced_beforeSnapshotLoaded_staysLoadingSnapshot() {
            gate.consensusSynced();

            assertThat(gate.state()).isEqualTo(ReadinessState.LOADING_SNAPSHOT);
            assertThat(gate.isWriteReady()).isFalse();
        }
    }
}
