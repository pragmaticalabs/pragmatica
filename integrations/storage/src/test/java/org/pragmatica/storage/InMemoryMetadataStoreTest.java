package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.storage.InMemoryMetadataStore.inMemoryMetadataStore;

class InMemoryMetadataStoreTest {

    private static final byte[] CONTENT_A = "metadata-store-alpha".getBytes(StandardCharsets.UTF_8);

    private InMemoryMetadataStore store;

    @BeforeEach
    void setUp() {
        store = inMemoryMetadataStore("test-instance");
    }

    private static BlockId blockIdOf(byte[] content) {
        return BlockId.blockId(content)
                      .fold(_ -> { fail("BlockId creation failed"); return null; },
                            id -> id);
    }

    @Nested
    class LifecycleTests {

        @Test
        void getLifecycle_unknownBlock_returnsNone() {
            var id = blockIdOf(CONTENT_A);

            var result = store.getLifecycle(id);

            assertThat(result.isEmpty()).isTrue();
        }

        @Test
        void createLifecycle_newBlock_canBeRetrieved() {
            var id = blockIdOf(CONTENT_A);
            var lifecycle = BlockLifecycle.blockLifecycle(id, TierLevel.MEMORY);

            store.createLifecycle(lifecycle);

            var retrieved = store.getLifecycle(id);
            assertThat(retrieved.isPresent()).isTrue();
            retrieved.onPresent(lc -> {
                assertThat(lc.blockId()).isEqualTo(id);
                assertThat(lc.presentIn()).contains(TierLevel.MEMORY);
                assertThat(lc.refCount()).isEqualTo(1);
            });
        }

        @Test
        void computeLifecycle_existingBlock_appliesUpdater() {
            var id = blockIdOf(CONTENT_A);
            store.createLifecycle(BlockLifecycle.blockLifecycle(id, TierLevel.MEMORY));

            var updated = store.computeLifecycle(id, BlockLifecycle::withRefCountIncremented);

            assertThat(updated.isPresent()).isTrue();
            updated.onPresent(lc -> assertThat(lc.refCount()).isEqualTo(2));
        }

        @Test
        void computeLifecycle_unknownBlock_returnsNone() {
            var id = blockIdOf(CONTENT_A);

            var result = store.computeLifecycle(id, BlockLifecycle::withRefCountIncremented);

            assertThat(result.isEmpty()).isTrue();
        }

        @Test
        void removeLifecycle_existingBlock_removesEntry() {
            var id = blockIdOf(CONTENT_A);
            store.createLifecycle(BlockLifecycle.blockLifecycle(id, TierLevel.MEMORY));

            store.removeLifecycle(id);

            assertThat(store.getLifecycle(id).isEmpty()).isTrue();
        }
    }

    @Nested
    class ClaimTests {

        @Test
        void claimBlock_firstClaim_returnsTrue() {
            var id = blockIdOf(CONTENT_A);
            var sentinel = BlockLifecycle.blockLifecycle(id, TierLevel.LOCAL_DISK);

            var claimed = store.claimBlock(id, sentinel);

            assertThat(claimed).isTrue();
        }

        @Test
        void claimBlock_duplicateClaim_returnsFalse() {
            var id = blockIdOf(CONTENT_A);
            var sentinel = BlockLifecycle.blockLifecycle(id, TierLevel.LOCAL_DISK);

            store.claimBlock(id, sentinel);
            var secondClaim = store.claimBlock(id, sentinel);

            assertThat(secondClaim).isFalse();
        }

        @Test
        void releaseClaim_afterClaim_allowsReClaim() {
            var id = blockIdOf(CONTENT_A);
            var sentinel = BlockLifecycle.blockLifecycle(id, TierLevel.LOCAL_DISK);

            store.claimBlock(id, sentinel);
            var released = store.releaseClaim(id, sentinel);

            assertThat(released).isTrue();
            assertThat(store.claimBlock(id, sentinel)).isTrue();
        }

        @Test
        void releaseClaim_afterRefCountBumped_preservesClaim() {
            var id = blockIdOf(CONTENT_A);
            var sentinel = BlockLifecycle.blockLifecycle(id, TierLevel.LOCAL_DISK);

            store.claimBlock(id, sentinel);
            store.computeLifecycle(id, BlockLifecycle::withRefCountIncremented);
            var released = store.releaseClaim(id, sentinel);

            assertThat(released).isFalse();
            assertThat(store.containsBlock(id)).isTrue();
        }
    }

    @Nested
    class ContainsTests {

        @Test
        void containsBlock_afterCreate_returnsTrue() {
            var id = blockIdOf(CONTENT_A);
            store.createLifecycle(BlockLifecycle.blockLifecycle(id, TierLevel.MEMORY));

            assertThat(store.containsBlock(id)).isTrue();
        }

        @Test
        void containsBlock_afterRemove_returnsFalse() {
            var id = blockIdOf(CONTENT_A);
            store.createLifecycle(BlockLifecycle.blockLifecycle(id, TierLevel.MEMORY));

            store.removeLifecycle(id);

            assertThat(store.containsBlock(id)).isFalse();
        }
    }

    @Nested
    class RefTests {

        @Test
        void putRef_andResolve_returnsBlockId() {
            var id = blockIdOf(CONTENT_A);

            store.putRef("my-artifact", id);

            var resolved = store.resolveRef("my-artifact");
            assertThat(resolved.isPresent()).isTrue();
            resolved.onPresent(resolvedId -> assertThat(resolvedId).isEqualTo(id));
        }

        @Test
        void resolveRef_unknownRef_returnsNone() {
            var resolved = store.resolveRef("nonexistent");

            assertThat(resolved.isEmpty()).isTrue();
        }

        @Test
        void removeRef_existingRef_returnsBlockId() {
            var id = blockIdOf(CONTENT_A);
            store.putRef("temp-ref", id);

            var removed = store.removeRef("temp-ref");

            assertThat(removed.isPresent()).isTrue();
            removed.onPresent(removedId -> assertThat(removedId).isEqualTo(id));
        }

        @Test
        void removeRef_unknownRef_returnsNone() {
            var removed = store.removeRef("nonexistent");

            assertThat(removed.isEmpty()).isTrue();
        }
    }

    /// SB-02: a claim records that a write has begun, not that its bytes are held. Until the ack point it is not
    /// listed (so no snapshot captures it) and is reported unacknowledged (so no presence check reads it).
    @Nested
    class UnacknowledgedClaimTests {

        @Test
        void claimUnacknowledged_isNotListed_untilAcknowledged() {
            var id = blockIdOf(CONTENT_A);

            assertThat(store.claimUnacknowledged(id, BlockLifecycle.blockLifecycle(id, TierLevel.LOCAL_DISK))).isTrue();

            assertThat(store.isUnacknowledged(id)).isTrue();
            assertThat(store.getLifecycle(id).isPresent()).as("the writer's own bookkeeping still reads it").isTrue();
            assertThat(store.listAllLifecycles()).isEmpty();

            store.acknowledgeClaim(id);

            assertThat(store.isUnacknowledged(id)).isFalse();
            assertThat(store.listAllLifecycles()).extracting(BlockLifecycle::blockId).containsExactly(id);
        }

        /// A claim for a real block replaces a cache-only marker and keeps the presence the marker recorded;
        /// the replacement is a visible-to-nobody claim until acknowledged, and the epoch moves once, on the ack.
        @Test
        void claimUnacknowledged_overACacheMarker_replacesItKeepingItsPresence() {
            var id = blockIdOf(CONTENT_A);

            store.claimBlock(id, BlockLifecycle.cacheOnlyLifecycle(id, java.util.Set.of(TierLevel.REMOTE)));

            assertThat(store.claimUnacknowledged(id, BlockLifecycle.blockLifecycle(id, TierLevel.LOCAL_DISK))).isTrue();

            var record = store.getLifecycle(id).unwrap();

            assertThat(record.cacheOnly()).isFalse();
            assertThat(record.presentIn()).containsExactlyInAnyOrder(TierLevel.REMOTE, TierLevel.LOCAL_DISK);
            assertThat(store.isUnacknowledged(id)).isTrue();
        }

        /// A claim for a cache-only marker counts no epoch step, as for any marker: it is not a write.
        @Test
        void claimUnacknowledged_ofACacheMarker_movesNoEpoch() {
            var id = blockIdOf(CONTENT_A);
            var before = store.currentEpoch();

            assertThat(store.claimUnacknowledged(id, BlockLifecycle.cacheOnlyLifecycle(id, java.util.Set.of(TierLevel.REMOTE)))).isTrue();
            assertThat(store.currentEpoch()).isEqualTo(before);
        }

        /// A marker never replaces a marker: the second claim for one marker loses, as one real claim loses to another.
        @Test
        void claimUnacknowledged_ofACacheMarker_overACacheMarker_loses() {
            var id = blockIdOf(CONTENT_A);
            var marker = BlockLifecycle.cacheOnlyLifecycle(id, java.util.Set.of(TierLevel.REMOTE));

            assertThat(store.claimUnacknowledged(id, marker)).isTrue();
            assertThat(store.claimUnacknowledged(id, marker)).isFalse();
        }

        @Test
        void acknowledgeClaim_advancesTheEpoch_becauseTheRecordBecomesVisibleToSnapshots() {
            var id = blockIdOf(CONTENT_A);

            store.claimUnacknowledged(id, BlockLifecycle.blockLifecycle(id, TierLevel.LOCAL_DISK));

            var before = store.currentEpoch();

            store.acknowledgeClaim(id);

            assertThat(store.currentEpoch()).isEqualTo(before + 1);
        }

        @Test
        void claimUnacknowledged_losingToAnInFlightClaim_leavesTheOwnersClaimUnacknowledged() {
            var id = blockIdOf(CONTENT_A);
            var sentinel = BlockLifecycle.blockLifecycle(id, TierLevel.LOCAL_DISK);

            store.claimUnacknowledged(id, sentinel);

            assertThat(store.claimUnacknowledged(id, sentinel)).isFalse();
            assertThat(store.isUnacknowledged(id)).as("the loser must not unmark the owner").isTrue();
            assertThat(store.listAllLifecycles()).isEmpty();
        }

        @Test
        void claimUnacknowledged_losingToAnAcknowledgedRecord_doesNotHideIt() {
            var id = blockIdOf(CONTENT_A);
            var sentinel = BlockLifecycle.blockLifecycle(id, TierLevel.LOCAL_DISK);

            store.claimUnacknowledged(id, sentinel);
            store.acknowledgeClaim(id);

            assertThat(store.claimUnacknowledged(id, sentinel)).isFalse();
            assertThat(store.isUnacknowledged(id)).as("a dedup must not re-hide an acknowledged block").isFalse();
            assertThat(store.listAllLifecycles()).extracting(BlockLifecycle::blockId).containsExactly(id);
        }

        @Test
        void acknowledgeClaim_ofABlockWithNoClaim_isANoOp() {
            var id = blockIdOf(CONTENT_A);

            store.createLifecycle(BlockLifecycle.blockLifecycle(id, TierLevel.LOCAL_DISK));

            var before = store.currentEpoch();

            store.acknowledgeClaim(id);

            assertThat(store.currentEpoch()).isEqualTo(before);
        }

        @Test
        void releaseClaim_andRemoveLifecycle_dropTheClaimMark() {
            var released = blockIdOf(CONTENT_A);
            var removed = blockIdOf("metadata-store-bravo".getBytes(StandardCharsets.UTF_8));
            var sentinel = BlockLifecycle.blockLifecycle(released, TierLevel.LOCAL_DISK);

            store.claimUnacknowledged(released, sentinel);
            store.claimUnacknowledged(removed, BlockLifecycle.blockLifecycle(removed, TierLevel.LOCAL_DISK));
            store.releaseClaim(released, sentinel);
            store.removeLifecycle(removed);

            assertThat(store.isUnacknowledged(released)).isFalse();
            assertThat(store.isUnacknowledged(removed)).isFalse();
        }

        @Test
        void releaseClaim_andRemoveLifecycle_forgetTheReadsSeen() {
            var released = blockIdOf(CONTENT_A);
            var removed = blockIdOf("metadata-store-bravo".getBytes(StandardCharsets.UTF_8));
            var sentinel = BlockLifecycle.blockLifecycle(released, TierLevel.LOCAL_DISK);

            store.claimUnacknowledged(released, sentinel);
            store.createLifecycle(BlockLifecycle.blockLifecycle(removed, TierLevel.LOCAL_DISK));
            store.accessTracker().record(released);
            store.accessTracker().record(removed);
            store.releaseClaim(released, sentinel);
            store.removeLifecycle(removed);

            assertThat(store.accessTracker().of(released).isEmpty()).isTrue();
            assertThat(store.accessTracker().of(removed).isEmpty()).isTrue();
        }

        @Test
        void restoreLifecycles_dropsEveryClaimMark() {
            var id = blockIdOf(CONTENT_A);

            store.claimUnacknowledged(id, BlockLifecycle.blockLifecycle(id, TierLevel.LOCAL_DISK));
            store.restoreLifecycles(java.util.List.of());

            assertThat(store.isUnacknowledged(id)).isFalse();
        }
    }

    /// C-08: an observation updates the record without advancing the epoch that triggers snapshots.
    @Nested
    class ObservationTests {

        @Test
        void observeLifecycle_updatesTheRecord_withoutAdvancingTheEpoch() {
            var id = blockIdOf(CONTENT_A);

            store.createLifecycle(BlockLifecycle.blockLifecycle(id, TierLevel.LOCAL_DISK));

            var before = store.currentEpoch();

            store.observeLifecycle(id, BlockLifecycle::withAccessTimestamp);

            assertThat(store.getLifecycle(id).unwrap().accessCount()).isEqualTo(1);
            assertThat(store.currentEpoch()).isEqualTo(before);
        }

        @Test
        void computeLifecycle_stillAdvancesTheEpoch() {
            var id = blockIdOf(CONTENT_A);

            store.createLifecycle(BlockLifecycle.blockLifecycle(id, TierLevel.LOCAL_DISK));

            var before = store.currentEpoch();

            store.computeLifecycle(id, BlockLifecycle::withAccessTimestamp);

            assertThat(store.currentEpoch()).isEqualTo(before + 1);
        }

        @Test
        void claimAndReleaseOfACacheOnlyMarker_doNotAdvanceTheEpoch() {
            var id = blockIdOf(CONTENT_A);
            var marker = BlockLifecycle.cacheOnlyLifecycle(id, java.util.EnumSet.of(TierLevel.MEMORY));
            var before = store.currentEpoch();

            assertThat(store.claimBlock(id, marker)).isTrue();
            assertThat(store.releaseClaim(id, marker)).isTrue();

            assertThat(store.currentEpoch()).isEqualTo(before);
        }
    }

    @Nested
    class InstanceNameTests {

        @Test
        void instanceName_returnsConfiguredName() {
            assertThat(store.instanceName()).isEqualTo("test-instance");
        }
    }
}
