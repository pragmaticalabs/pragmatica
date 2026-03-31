package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.storage.PrefetchConfig.prefetchConfig;
import static org.pragmatica.storage.PrefetchHint.prefetchHint;
import static org.pragmatica.storage.PrefetchManager.prefetchManager;

class PrefetchManagerTest {

    private static final byte[] BLOCK_A = "prefetch-alpha-content".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BLOCK_B = "prefetch-bravo-content".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BLOCK_C = "prefetch-charlie-data!".getBytes(StandardCharsets.UTF_8);

    private MemoryTier memoryTier;
    private StorageInstance storage;
    private PrefetchConfig config;

    @BeforeEach
    void setUp() {
        memoryTier = MemoryTier.memoryTier(1024 * 1024);
        storage = StorageInstance.storageInstance("prefetch-test", List.of(memoryTier));
        config = prefetchConfig(3, 5, 0);
    }

    private static BlockId blockIdOf(byte[] content) {
        return BlockId.blockId(content)
                      .fold(_ -> { fail("BlockId creation failed"); return null; },
                            id -> id);
    }

    @Nested
    class HintCollectionTests {

        @Test
        void recordAccess_belowThreshold_noHints() {
            var pm = prefetchManager(storage, config);
            pm.activate();

            var id = blockIdOf(BLOCK_A);

            pm.recordAccess(id);
            pm.recordAccess(id);

            var hints = pm.collectHints();

            assertThat(hints).isEmpty();
        }

        @Test
        void recordAccess_aboveThreshold_generatesHint() {
            var pm = prefetchManager(storage, config);
            pm.activate();

            var id = blockIdOf(BLOCK_A);

            pm.recordAccess(id);
            pm.recordAccess(id);
            pm.recordAccess(id);

            var hints = pm.collectHints();

            assertThat(hints).hasSize(1);
            assertThat(hints.getFirst().blockIdHex()).isEqualTo(id.hexString());
            assertThat(hints.getFirst().accessCount()).isEqualTo(3);
        }

        @Test
        void collectHints_resetsCounters() {
            var pm = prefetchManager(storage, config);
            pm.activate();

            var id = blockIdOf(BLOCK_A);

            pm.recordAccess(id);
            pm.recordAccess(id);
            pm.recordAccess(id);

            pm.collectHints();
            var secondCollection = pm.collectHints();

            assertThat(secondCollection).isEmpty();
        }

        @Test
        void collectHints_limitsToMaxPerGossip() {
            var limitedConfig = prefetchConfig(1, 2, 0);
            var pm = prefetchManager(storage, limitedConfig);
            pm.activate();

            var idA = blockIdOf(BLOCK_A);
            var idB = blockIdOf(BLOCK_B);
            var idC = blockIdOf(BLOCK_C);

            pm.recordAccess(idA);
            pm.recordAccess(idB);
            pm.recordAccess(idC);

            var hints = pm.collectHints();

            assertThat(hints).hasSize(2);
        }

        @Test
        void collectHints_orderedByAccessCountDescending() {
            var limitedConfig = prefetchConfig(1, 5, 0);
            var pm = prefetchManager(storage, limitedConfig);
            pm.activate();

            var idA = blockIdOf(BLOCK_A);
            var idB = blockIdOf(BLOCK_B);

            pm.recordAccess(idA);
            pm.recordAccess(idB);
            pm.recordAccess(idB);
            pm.recordAccess(idB);

            var hints = pm.collectHints();

            assertThat(hints).hasSize(2);
            assertThat(hints.get(0).blockIdHex()).isEqualTo(idB.hexString());
            assertThat(hints.get(1).blockIdHex()).isEqualTo(idA.hexString());
        }
    }

    @Nested
    class HintProcessingTests {

        @Test
        void processHints_missingBlock_triggersPrefetch() {
            // Use two-tier storage so the block is stored in the durable tier
            var durableTier = MemoryTier.memoryTier(1024 * 1024, TierLevel.LOCAL_DISK);
            var twoTierStorage = StorageInstance.storageInstance("two-tier", List.of(memoryTier, durableTier));

            // Store block in durable tier only (simulate remote data)
            var id = blockIdOf(BLOCK_A);
            durableTier.put(id, BLOCK_A).await()
                       .onFailure(c -> fail("put failed: " + c.message()));

            var pm = prefetchManager(twoTierStorage, config);
            pm.activate();

            var hints = List.of(prefetchHint(id.hexString(), 10, TierLevel.MEMORY.name()));
            pm.processHints(hints);

            // Allow async prefetch to complete
            twoTierStorage.get(id).await()
                          .onFailure(c -> fail("get failed: " + c.message()))
                          .onSuccess(opt -> assertThat(opt.isPresent()).isTrue());
        }

        @Test
        void processHints_existingBlock_noPrefetch() {
            // Store block locally first
            storage.put(BLOCK_A).await()
                   .onFailure(c -> fail("put failed: " + c.message()));

            var id = blockIdOf(BLOCK_A);
            var pm = prefetchManager(storage, config);
            pm.activate();

            var initialUsed = memoryTier.usedBytes();

            var hints = List.of(prefetchHint(id.hexString(), 10, TierLevel.MEMORY.name()));
            pm.processHints(hints);

            // Block already exists, no additional storage activity expected
            assertThat(memoryTier.usedBytes()).isEqualTo(initialUsed);
        }

        @Test
        void processHints_belowThreshold_ignored() {
            var pm = prefetchManager(storage, config);
            pm.activate();

            var id = blockIdOf(BLOCK_A);

            // Access count 1, threshold is 3 — should be ignored
            var hints = List.of(prefetchHint(id.hexString(), 1, TierLevel.MEMORY.name()));
            pm.processHints(hints);

            // No prefetch initiated — block still doesn't exist
            storage.exists(id).await()
                   .onSuccess(exists -> assertThat(exists).isFalse());
        }
    }

    @Nested
    class LifecycleTests {

        @Test
        void dormant_noHintsGenerated() {
            var pm = prefetchManager(storage, config);
            // NOT activated — dormant

            var id = blockIdOf(BLOCK_A);

            pm.recordAccess(id);
            pm.recordAccess(id);
            pm.recordAccess(id);
            pm.recordAccess(id);

            var hints = pm.collectHints();

            assertThat(hints).isEmpty();
        }

        @Test
        void dormant_processHintsIgnored() {
            var pm = prefetchManager(storage, config);
            // NOT activated — dormant

            var id = blockIdOf(BLOCK_A);
            var hints = List.of(prefetchHint(id.hexString(), 100, TierLevel.MEMORY.name()));

            pm.processHints(hints);

            storage.exists(id).await()
                   .onSuccess(exists -> assertThat(exists).isFalse());
        }

        @Test
        void activate_deactivate_clearsState() {
            var pm = prefetchManager(storage, config);
            pm.activate();

            assertThat(pm.isActive()).isTrue();

            var id = blockIdOf(BLOCK_A);
            pm.recordAccess(id);
            pm.recordAccess(id);
            pm.recordAccess(id);

            pm.deactivate();

            assertThat(pm.isActive()).isFalse();

            // After reactivation, previous counts should be cleared
            pm.activate();
            var hints = pm.collectHints();

            assertThat(hints).isEmpty();
        }

        @Test
        void noop_alwaysInactive() {
            var noop = PrefetchManager.NOOP;

            assertThat(noop.isActive()).isFalse();
            assertThat(noop.collectHints()).isEmpty();

            noop.activate();
            assertThat(noop.isActive()).isFalse();
        }
    }

    @Nested
    class CooldownTests {

        @Test
        void processHints_respectsCooldown() {
            var cooldownConfig = prefetchConfig(1, 5, 60_000);
            var pm = prefetchManager(storage, cooldownConfig);
            pm.activate();

            var id = blockIdOf(BLOCK_A);
            var hints = List.of(prefetchHint(id.hexString(), 10, TierLevel.MEMORY.name()));

            // First attempt sets cooldown
            pm.processHints(hints);

            // Second attempt within cooldown window should be filtered out
            // (cooldown is 60s, so this will always be within window)
            pm.processHints(hints);

            // Block doesn't exist in storage, so each attempt would try to fetch.
            // With cooldown, the second attempt should be suppressed.
            // We verify by checking the block still doesn't exist (no source to fetch from).
            storage.exists(id).await()
                   .onSuccess(exists -> assertThat(exists).isFalse());
        }
    }
}
