package org.pragmatica.aether.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.Command;
import org.pragmatica.consensus.StateMachine;
import org.pragmatica.consensus.rabia.Phase;
import org.pragmatica.consensus.rabia.RabiaPersistence;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.ContentStore;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.storage.StorageBackedPersistence.storageBackedPersistence;

class StorageBackedPersistenceTest {

    private static final long MEMORY_MAX = 16 * 1024 * 1024;
    private static final byte[] SNAPSHOT_DATA = {1, 2, 3, 4, 5};

    private TestStateMachine stateMachine;
    private RabiaPersistence<TestCommand> persistence;

    @BeforeEach
    void setUp() {
        var storage = StorageInstance.storageInstance("persistence-test", List.of(MemoryTier.memoryTier(MEMORY_MAX)));
        var contentStore = ContentStore.contentStore(storage);
        stateMachine = new TestStateMachine();
        persistence = storageBackedPersistence(contentStore);
    }

    @Nested
    class SaveAndLoadTests {

        @Test
        void save_load_roundTrip() {
            stateMachine.setSnapshot(SNAPSHOT_DATA);
            var phase = Phase.phase(42);

            persistence.save(stateMachine, phase, List.of())
                       .onFailure(_ -> fail("Save should succeed"));

            var loaded = persistence.load();

            assertThat(loaded.isPresent()).isTrue();
            loaded.onPresent(state -> assertRestoredState(state, SNAPSHOT_DATA, phase));
        }

        @Test
        void save_overwritesPrevious() {
            stateMachine.setSnapshot(new byte[]{1, 2});
            persistence.save(stateMachine, Phase.phase(1), List.of())
                       .onFailure(_ -> fail("First save should succeed"));

            var updatedSnapshot = new byte[]{10, 20, 30};
            stateMachine.setSnapshot(updatedSnapshot);
            var updatedPhase = Phase.phase(99);
            persistence.save(stateMachine, updatedPhase, List.of())
                       .onFailure(_ -> fail("Second save should succeed"));

            var loaded = persistence.load();

            assertThat(loaded.isPresent()).isTrue();
            loaded.onPresent(state -> assertRestoredState(state, updatedSnapshot, updatedPhase));
        }

        @Test
        void save_emptySnapshot_roundTrips() {
            stateMachine.setSnapshot(new byte[0]);
            var phase = Phase.ZERO;

            persistence.save(stateMachine, phase, List.of())
                       .onFailure(_ -> fail("Save should succeed"));

            var loaded = persistence.load();

            assertThat(loaded.isPresent()).isTrue();
            loaded.onPresent(state -> assertRestoredState(state, new byte[0], phase));
        }

        @Test
        void save_pendingBatchesNotPersisted_loadReturnsEmptyBatches() {
            stateMachine.setSnapshot(SNAPSHOT_DATA);

            persistence.save(stateMachine, Phase.phase(5), List.of())
                       .onFailure(_ -> fail("Save should succeed"));

            var loaded = persistence.load();

            assertThat(loaded.isPresent()).isTrue();
            loaded.onPresent(state -> assertThat(state.pendingBatches()).isEmpty());
        }
    }

    @Nested
    class LoadTests {

        @Test
        void load_noData_returnsNone() {
            var loaded = persistence.load();

            assertThat(loaded.isPresent()).isFalse();
        }
    }

    @Nested
    class EncodingTests {

        @Test
        void encodeSnapshot_decodeSnapshot_roundTrip() {
            var snapshot = new byte[]{100, (byte) 200, 50};
            var phase = Phase.phase(Long.MAX_VALUE);

            var encoded = StorageBackedPersistence.encodeSnapshot(snapshot, phase);
            var decoded = StorageBackedPersistence.<TestCommand>decodeSnapshot(encoded);

            assertThat(decoded.isPresent()).isTrue();
            decoded.onPresent(state -> assertRestoredState(state, snapshot, phase));
        }

        @Test
        void decodeSnapshot_tooShort_returnsNone() {
            var decoded = StorageBackedPersistence.<TestCommand>decodeSnapshot(new byte[]{1, 2, 3});

            assertThat(decoded.isPresent()).isFalse();
        }

        @Test
        void encodeSnapshot_phaseZero_roundTrips() {
            var snapshot = new byte[]{7, 8, 9};

            var encoded = StorageBackedPersistence.encodeSnapshot(snapshot, Phase.ZERO);
            var decoded = StorageBackedPersistence.<TestCommand>decodeSnapshot(encoded);

            assertThat(decoded.isPresent()).isTrue();
            decoded.onPresent(state -> assertRestoredState(state, snapshot, Phase.ZERO));
        }
    }

    @Nested
    class CustomSnapshotNameTests {

        @Test
        void storageBackedPersistence_customName_isolatesData() {
            var storage = StorageInstance.storageInstance("custom-test", List.of(MemoryTier.memoryTier(MEMORY_MAX)));
            var contentStore = ContentStore.contentStore(storage);

            RabiaPersistence<TestCommand> persistence1 = storageBackedPersistence(contentStore, "node-1/snapshot");
            RabiaPersistence<TestCommand> persistence2 = storageBackedPersistence(contentStore, "node-2/snapshot");

            stateMachine.setSnapshot(new byte[]{1});
            persistence1.save(stateMachine, Phase.phase(10), List.of())
                        .onFailure(_ -> fail("Save to persistence1 should succeed"));

            stateMachine.setSnapshot(new byte[]{2});
            persistence2.save(stateMachine, Phase.phase(20), List.of())
                        .onFailure(_ -> fail("Save to persistence2 should succeed"));

            var loaded1 = persistence1.load();
            var loaded2 = persistence2.load();

            assertThat(loaded1.isPresent()).isTrue();
            loaded1.onPresent(state -> {
                assertThat(state.snapshot()).isEqualTo(new byte[]{1});
                assertThat(state.lastCommittedPhase()).isEqualTo(Phase.phase(10));
            });

            assertThat(loaded2.isPresent()).isTrue();
            loaded2.onPresent(state -> {
                assertThat(state.snapshot()).isEqualTo(new byte[]{2});
                assertThat(state.lastCommittedPhase()).isEqualTo(Phase.phase(20));
            });
        }
    }

    // --- Helpers ---

    private static void assertRestoredState(RabiaPersistence.SavedState<TestCommand> state,
                                            byte[] expectedSnapshot,
                                            Phase expectedPhase) {
        assertThat(state.snapshot()).isEqualTo(expectedSnapshot);
        assertThat(state.lastCommittedPhase()).isEqualTo(expectedPhase);
        assertThat(state.pendingBatches()).isEmpty();
    }

    // --- Test types ---

    record TestCommand(String value) implements Command {}

    static class TestStateMachine implements StateMachine<TestCommand> {
        private byte[] currentSnapshot = new byte[0];

        void setSnapshot(byte[] data) {
            this.currentSnapshot = data.clone();
        }

        @Override
        public <R> R process(TestCommand command) {
            return null;
        }

        @Override
        public Result<byte[]> makeSnapshot() {
            return Result.success(currentSnapshot.clone());
        }

        @Override
        public Result<Unit> restoreSnapshot(byte[] data) {
            this.currentSnapshot = data.clone();
            return Result.unitResult();
        }

        @Override
        public Unit reset() {
            currentSnapshot = new byte[0];
            return Unit.unit();
        }
    }
}
