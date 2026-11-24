// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.StreamEntityLogSubstrate;
import org.pragmatica.aether.node.StreamEntityLogSubstrate.CheckpointReclaim;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EntityCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EntityFoldCheckpointValue;
import org.pragmatica.aether.stream.EvictionListener;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.Retry;
import org.pragmatica.lang.utils.Retry.BackoffStrategy;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.LocalDiskTier;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.storage.StorageTier;
import org.pragmatica.storage.TierLevel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.node.StreamEntityLogSubstrate.CheckpointReclaim.checkpointReclaim;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// Streams PR-3c (spec 6.4 item 1, I-15): a checkpoint writer reclaims, through `StorageInstance#delete`, the block its confirmed pointer
/// swap displaced once a grace period has passed (NT-61), deletes its own new block at once when the fenced pointer put was refused (NT-62),
/// and gives every block an identity of its own, `(keyspace, partition, throughOffset)` in the frame header, so no two pointers can name one
/// block. The header is a flag day for the block layout: a build without it must read a new block as "no usable checkpoint".
///
/// The second tier records every put and delete and stands in for the shared DHT tier; a delete it refuses is the write quorum being
/// unreachable.
class EntityCheckpointReclaimTest {
    private static final String KEYSPACE = "orders";
    private static final long ONE_GB = 1024L * 1024 * 1024;
    private static final TimeSpan GRACE = TimeSpan.timeSpan(2).seconds();
    private static final TimeSpan LONG_GRACE = TimeSpan.timeSpan(1).hours();
    private static final TimeSpan SHORT_GRACE = TimeSpan.timeSpan(200).millis();
    /// Long enough for a delete scheduled at SHORT_GRACE to have run.
    private static final long PAST_SHORT_GRACE_MS = 1_000L;
    private static final int DELETE_ATTEMPTS = 3;
    private static final Retry FAST_RETRY = Retry.retry()
                                                 .attempts(DELETE_ATTEMPTS)
                                                 .strategy(BackoffStrategy.fixed().interval(TimeSpan.timeSpan(50).millis()));
    private static final byte[] LINEAGE = "a recorded lineage".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OLD_MARKER = "LNGCKPT1".getBytes(StandardCharsets.US_ASCII);

    private StreamPartitionManager manager;
    private RecordingTier shared;
    private StorageInstance storage;
    private KVStore<AetherKey, AetherValue> kv;
    /// Full scans of `kv` (`forEach`), which the delete-time check runs for a block without a readable identity header.
    private final AtomicInteger scans = new AtomicInteger();
    /// Names the storage instances and durable tiers a test builds beyond the fixture's.
    private final AtomicInteger durables = new AtomicInteger();
    @TempDir
    Path diskRoot;
    private final List<StorageInstance> extraStorages = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager(64L * 1024 * 1024, EvictionListener.NOOP, ReplicationManager.NONE, Option.none(), (_, _) -> -1L);
        shared = new RecordingTier();
        storage = StorageInstance.storageInstance("entity-ckpt",
                                                  List.of(MemoryTier.memoryTier(ONE_GB), shared),
                                                  MetadataStore.inMemoryMetadataStore("entity-ckpt"));
        kv = new KVStore<>(MessageRouter.mutable(), noopSerializer(), noopDeserializer()) {
            @Override
            public <KK, VV> void forEach(Class<KK> keyClass, Class<VV> valueClass, BiConsumer<KK, VV> consumer) {
                scans.incrementAndGet();
                super.forEach(keyClass, valueClass, consumer);
            }
        };
    }

    @AfterEach
    void tearDown() {
        manager.close();
        storage.shutdown();
        extraStorages.stream().filter(extra -> extra != storage).forEach(StorageInstance::shutdown);
    }

    /// NT-61: the block the replaced pointer named is deleted after the grace, and not before; the block the new pointer names is kept.
    @Test
    void aConfirmedSwap_deletesTheDisplacedBlock_onceTheGraceHasPassed_andNotBefore() {
        var substrate = substrate(checkpointReclaim(GRACE, FAST_RETRY));
        var displaced = save(substrate, 0, 1L, snapshot("v1"), Option.none());
        var current = save(substrate, 0, 2L, snapshot("v2"), Option.none());

        assertThat(current).as("control: the pointer moved to a new block").isNotEqualTo(displaced);
        pause(GRACE.millis() / 2);
        assertThat(readable(displaced)).as("not before the grace has passed").isTrue();
        eventually("the displaced block is deleted once the grace has passed", 15_000L, () -> !readable(displaced));
        assertThat(shared.deletes).as("deleted through StorageInstance#delete, which reaches the shared tier").contains(displaced);
        assertThat(readable(current)).as("the block the pointer names is kept").isTrue();
        assertThat(pointed(0)).isEqualTo(current);
    }

    /// NT-62: a put the fenced applier refused (a higher claim is already committed) deletes the writer's own new block at once, and
    /// nothing else: the block the committed pointer names stays.
    @Test
    void aRefusedPointerPut_deletesOnlyTheWritersOwnBlock_atOnce() {
        var substrate = substrate(checkpointReclaim(LONG_GRACE, FAST_RETRY));
        var committed = save(substrate, 0, 5L, snapshot("v5"), Option.none());

        save(substrate, 0, 2L, snapshot("v2"), Option.none());
        var own = shared.puts.getLast();

        assertThat(pointed(0)).as("control: the lower claim was refused").isEqualTo(committed);
        assertThat(own).as("control: the refused save wrote a block of its own").isNotEqualTo(committed);
        eventually("the refused writer's own block is deleted at once (the grace is an hour)", 5_000L, () -> !readable(own));
        assertThat(readable(committed)).as("the block the committed pointer names is untouched").isTrue();
        assertThat(shared.deletes).as("nothing but the writer's own block is deleted").containsOnly(own);
    }

    /// The flag day's compatibility condition (spec 6.4 Migration): a block in the new layout, read by the rc5 reader, is "no usable
    /// checkpoint" on every path an rc5 fold takes: the rc5 frame reader finds no lineage (a lineage-recording substrate discards the
    /// checkpoint) and the rc5 snapshot decoder refuses the first byte (any substrate discards it).
    @Test
    void aNewBlock_readByTheRc5Reader_isNoUsableCheckpoint() {
        var substrate = substrate(checkpointReclaim(LONG_GRACE, FAST_RETRY));
        var withLineage = save(substrate, 0, 3L, snapshot("v3"), Option.some(LINEAGE));
        var withoutLineage = save(substrate, 1, 3L, snapshot("v3"), Option.none());

        assertThat(List.of(withLineage, withoutLineage)).allSatisfy(id -> {
            var old = rc5Unframe(3L, block(id));

            assertThat(block(id)[0]).as("the first byte is neither snapshot version").isNotIn((byte) 1, (byte) 2);

            assertThat(old.lineage().isEmpty()).as("the rc5 frame reader finds no lineage").isTrue();
            assertThat(EntityFoldSnapshot.decode(old.snapshot()).isFailure()).as("the rc5 snapshot decoder refuses the block").isTrue();
        });
        var loaded = substrate.loadCheckpoint(KEYSPACE, 0).await().unwrap().unwrap();

        assertThat(loaded.snapshot()).as("control: this build reads its own block").isEqualTo(snapshot("v3"));
        assertThat(loaded.lineage().map(Arrays::toString).or("none")).isEqualTo(Arrays.toString(LINEAGE));
        assertThat(EntityFoldSnapshot.decode(loaded.snapshot()).isSuccess()).isTrue();
    }

    /// NT-63's identity clause, at component level: byte-identical snapshots (with and without a lineage) in two partitions, or at two
    /// claims, are different blocks, so a delete by one pointer cannot remove another pointer's block.
    @Test
    void identicalSnapshots_inTwoPartitionsOrAtTwoClaims_areDifferentBlocks() {
        var substrate = substrate(checkpointReclaim(LONG_GRACE, FAST_RETRY));
        var bytes = snapshot("same");

        assertThat(save(substrate, 0, 4L, bytes, Option.none())).as("empty lineage, two partitions").isNotEqualTo(save(substrate, 1, 4L, bytes, Option.none()));
        assertThat(save(substrate, 2, 4L, bytes, Option.some(LINEAGE))).as("same lineage, two partitions").isNotEqualTo(save(substrate, 3, 4L, bytes, Option.some(LINEAGE)));
        assertThat(save(substrate, 4, 4L, bytes, Option.none())).as("one partition, two claims").isNotEqualTo(save(substrate, 4, 5L, bytes, Option.none()));
        assertThat(save(substrate, "orders", 5, 4L, bytes)).as("two keyspaces of one length").isNotEqualTo(save(substrate, "events", 5, 4L, bytes));
        assertThat(substrate.saveCheckpoint(KEYSPACE, 6, 4L, bytes).await().isSuccess()).isTrue();
        assertThat(substrate.saveCheckpoint(KEYSPACE, 7, 4L, bytes).await().isSuccess()).isTrue();
        assertThat(pointed(6)).as("the lineage-less save path, two partitions").isNotEqualTo(pointed(7));
    }

    /// A delete the shared tier refuses (its quorum unreachable) is retried, and the block leaves once the store accepts.
    @Test
    void aRefusedDelete_isRetried_untilTheStoreAcceptsIt() {
        var substrate = substrate(checkpointReclaim(TimeSpan.timeSpan(0).millis(), FAST_RETRY));
        var displaced = save(substrate, 0, 1L, snapshot("v1"), Option.none());

        shared.refuseDeletes.set(DELETE_ATTEMPTS - 1);
        save(substrate, 0, 2L, snapshot("v2"), Option.none());
        eventually("the displaced block is deleted after the refusals", 5_000L, () -> !readable(displaced));
        assertThat(shared.deletes.stream().filter(displaced::equals).count()).as("one refused attempt per refusal, then the accepted one")
                                                                           .isEqualTo(DELETE_ATTEMPTS);
    }

    /// The retry is bounded: a delete the store keeps refusing stops after the bound, and the block is left (a leak, logged), never
    /// retried for ever.
    @Test
    void aDeleteTheStoreKeepsRefusing_stopsAfterTheBound() {
        var substrate = substrate(checkpointReclaim(TimeSpan.timeSpan(0).millis(), FAST_RETRY));
        var displaced = save(substrate, 0, 1L, snapshot("v1"), Option.none());

        shared.refuseDeletes.set(Integer.MAX_VALUE);
        save(substrate, 0, 2L, snapshot("v2"), Option.none());
        eventually("the bound is spent", 5_000L, () -> shared.deletes.size() >= DELETE_ATTEMPTS);
        pause(500L);
        assertThat(shared.deletes).as("exactly the bound, then no more").hasSize(DELETE_ATTEMPTS).containsOnly(displaced);
    }

    /// Equal claims (the fence ACCEPTS an equal claim): a block displaced at the claim the swap committed is not deleted, because a late
    /// duplicate publish could still name it; it is deleted, once, at the first later swap that passes its claim.
    @Test
    void anEqualClaimDisplacedBlock_waitsForTheNextSwapThatPassesIt_andIsDeletedOnce() {
        var substrate = substrate(checkpointReclaim(SHORT_GRACE, FAST_RETRY));
        var first = save(substrate, 0, 5L, snapshot("x"), Option.none());
        var second = save(substrate, 0, 5L, snapshot("y"), Option.none());

        pause(PAST_SHORT_GRACE_MS);
        assertThat(readable(first)).as("displaced at an equal claim: kept").isTrue();
        var third = save(substrate, 0, 6L, snapshot("z"), Option.none());

        eventually("both blocks the claim 6 passed are deleted", 5_000L, () -> !readable(first) && !readable(second));
        assertThat(readable(third)).isTrue();
        save(substrate, 0, 7L, snapshot("w"), Option.none());
        eventually("the claim 7 displaced the claim 6", 5_000L, () -> !readable(third));
        pause(PAST_SHORT_GRACE_MS);
        assertThat(deletesOf(first)).as("a deferred block is handed to one delete").isEqualTo(1L);
    }

    /// Hazard (#3480 P1, replaces the re-aimed late-duplicate test, which a per-put nonce made vacuous): ONE publish command applied twice.
    /// A block displaced at an equal claim is not dead: the fence accepts an equal claim, so a duplicate of the publish that first named it
    /// can name it again after the grace, and it must still be there. Only a strictly higher committed claim proves it dead.
    @Test
    void aDuplicatedPublishAtAnEqualClaim_afterTheGrace_findsTheBlockItNamesStillThere() {
        var recorded = new AtomicReference<List<KVCommand<AetherKey>>>();
        var recording = substrate(checkpointReclaim(SHORT_GRACE, FAST_RETRY), commands -> recordThenApply(recorded, commands));
        var first = save(recording, 0, 5L, snapshot("x"), Option.none());
        var second = save(substrate(checkpointReclaim(SHORT_GRACE, FAST_RETRY)), 0, 5L, snapshot("x"), Option.none());

        assertThat(second).as("control: the equal-claim save displaced the first block").isNotEqualTo(first);
        pause(PAST_SHORT_GRACE_MS);
        apply(recorded.get());
        assertThat(pointed(0)).as("control: the duplicated publish names the first block again").isEqualTo(first);
        pause(PAST_SHORT_GRACE_MS);
        assertThat(readable(first)).as("the block the current pointer names is readable").isTrue();
    }

    /// Hazard: a re-read that lags the publish (the local KV applies the swap after the applier answered) shows a LOWER claim or none.
    /// The writer deletes nothing on it: its own block may be the committed one. Once a later swap passes them, they go.
    @Test
    void aReReadThatLagsThePublish_deletesNothing() {
        var lagging = new CopyOnWriteArrayList<List<KVCommand<AetherKey>>>();
        var lag = new AtomicBoolean(true);
        var substrate = substrate(checkpointReclaim(SHORT_GRACE, FAST_RETRY), commands -> {
            if (!lag.get()) {
                return apply(commands);
            }

            lagging.add(commands);

            return Promise.success(List.of());
        });

        assertThat(substrate.saveCheckpoint(KEYSPACE, 0, 3L, snapshot("v3"), Option.none()).await().isSuccess()).isTrue();
        lagging.forEach(this::apply);
        lagging.clear();
        var first = pointed(0);

        assertThat(substrate.saveCheckpoint(KEYSPACE, 0, 4L, snapshot("v4"), Option.none()).await().isSuccess()).isTrue();
        lagging.forEach(this::apply);
        var second = pointed(0);

        pause(PAST_SHORT_GRACE_MS);
        assertThat(readable(second)).as("the committed block, its swap seen late, is kept").isTrue();
        assertThat(readable(first)).as("the block a lagging re-read still showed is kept").isTrue();
        assertThat(shared.deletes).as("nothing deleted on a lagging re-read").isEmpty();
        lag.set(false);
        var current = save(substrate, 0, 5L, snapshot("v5"), Option.none());

        eventually("a swap seen at once passes both, and they go", 5_000L, () -> !readable(first) && !readable(second));
        assertThat(readable(current)).isTrue();
    }

    /// Hazard: concurrent writers at a handover. The loser's publish lands after the winner's swap: it is refused, and the loser deletes
    /// its own block at once and nothing else, even with a zero grace; the block the winner displaced waits for the winner's grace.
    @Test
    void aHandoverLoser_deletesOnlyItsOwnBlock() {
        var winner = substrate(checkpointReclaim(LONG_GRACE, FAST_RETRY));
        var displaced = save(winner, 0, 5L, snapshot("v5"), Option.none());
        var winnerSwapped = new AtomicBoolean();
        var loser = substrate(checkpointReclaim(TimeSpan.timeSpan(0).millis(), FAST_RETRY), commands -> {
            winnerSwapped.set(winner.saveCheckpoint(KEYSPACE, 0, 9L, snapshot("v9"), Option.none()).await().isSuccess());

            return apply(commands);
        });

        assertThat(loser.saveCheckpoint(KEYSPACE, 0, 7L, snapshot("v7"), Option.none()).await().isSuccess()).isTrue();
        var own = shared.puts.stream().distinct().filter(id -> !id.equals(displaced) && !id.equals(pointed(0))).findFirst().orElseThrow();

        assertThat(winnerSwapped.get()).as("control: the winner swapped in between").isTrue();
        eventually("the loser's own block is deleted at once", 5_000L, () -> !readable(own));
        assertThat(readable(displaced)).as("the block the winner displaced is the winner's to delete, after its grace").isTrue();
        assertThat(readable(pointed(0))).as("the winner's block is the current one").isTrue();
        assertThat(shared.deletes).containsOnly(own);
    }

    /// Hazard: the reverse interleaving. The loser's swap lands first and the winner's pointer read (taken before it) is stale: the
    /// winner deletes the block its stale read named, the loser's block leaks (L-12), and the current block is never touched.
    @Test
    void aWinnerWithAStalePointerRead_neverTouchesTheCurrentBlock() {
        var loser = substrate(checkpointReclaim(SHORT_GRACE, FAST_RETRY));
        var displaced = save(loser, 0, 5L, snapshot("v5"), Option.none());
        var winner = substrate(checkpointReclaim(SHORT_GRACE, FAST_RETRY), commands -> {
            assertThat(loser.saveCheckpoint(KEYSPACE, 0, 7L, snapshot("v7"), Option.none()).await().isSuccess()).isTrue();

            return apply(commands);
        });
        var current = save(winner, 0, 9L, snapshot("v9"), Option.none());
        var loserBlock = shared.puts.stream().distinct().filter(id -> !id.equals(displaced) && !id.equals(current)).findFirst().orElseThrow();

        eventually("the block both pre-reads named is deleted", 5_000L, () -> !readable(displaced));
        pause(PAST_SHORT_GRACE_MS);
        assertThat(readable(current)).as("the current block is never touched").isTrue();
        assertThat(readable(loserBlock)).as("the loser's displaced block leaks: the winner never saw it (L-12)").isTrue();
    }

    /// #3303 A4a (was: identical bytes at one claim from two writers are one block): every put carries a fresh nonce, so identical bytes at one
    /// claim from two writers are two blocks. The second swap displaces the first writer's block at an EQUAL claim, which the reclaim defers
    /// (an equal claim is accepted, so it may be named again); a higher claim then releases it. The block the pointer names is kept.
    @Test
    void identicalBytesAtOneClaim_fromTwoWriters_areTwoBlocks_andTheDisplacedOneIsReclaimed() {
        var first = save(substrate(checkpointReclaim(SHORT_GRACE, FAST_RETRY)), 0, 5L, snapshot("same"), Option.none());
        var writer = substrate(checkpointReclaim(SHORT_GRACE, FAST_RETRY));
        var second = save(writer, 0, 5L, snapshot("same"), Option.none());

        assertThat(second).as("a fresh nonce per put: distinct block ids").isNotEqualTo(first);
        pause(PAST_SHORT_GRACE_MS);
        assertThat(readable(first)).as("an equal claim defers the displaced block: nothing is deleted yet").isTrue();
        assertThat(shared.deletes).isEmpty();
        var third = save(writer, 0, 6L, snapshot("later"), Option.none());

        eventually("a higher claim releases the deferred first block and displaces the second", 5_000L, () -> !readable(first) && !readable(second));
        assertThat(readable(third)).as("the block the pointer names is kept").isTrue();
    }

    /// T-A35 (#3303 A4a): the block is put, the writer crashes before the pointer Put; the one-order content is re-put; the pointer commits to
    /// the re-put block; the sweep deletes. With a per-put nonce the re-put has a new id, so the block the pointer names is kept; with a
    /// constant nonce the ids are equal and the delete of the orphan removes the block the pointer names (CHECKPOINT_LOST).
    @Test
    void aBlockPutBeforeACrash_andRePutWithTheSameContent_getsANewId_andTheCommittedBlockIsKept() {
        var substrate = substrate(checkpointReclaim(TimeSpan.timeSpan(0).millis(), FAST_RETRY));
        var crashing = substrate(checkpointReclaim(TimeSpan.timeSpan(0).millis(), FAST_RETRY), _ -> Promise.success(List.of()));

        assertThat(crashing.saveCheckpoint(KEYSPACE, 0, 5L, snapshot("same"), Option.none()).await().isSuccess()).as("control: the pointer Put never lands").isTrue();
        var orphan = shared.puts.getLast();

        var committed = save(substrate, 0, 5L, snapshot("same"), Option.none());

        assertThat(storage.delete(orphan).await().isSuccess()).as("the sweep deletes the orphan it decided on before the commit").isTrue();
        assertThat(committed).as("the re-put gets a new id").isNotEqualTo(orphan);
        pause(PAST_SHORT_GRACE_MS);
        assertThat(readable(pointed(0))).as("the block the committed pointer names is kept").isTrue();
        assertThat(pointed(0)).isEqualTo(committed);
    }

    /// Hazard: a loader holding the replaced pointer (its KV copy lags) races the delete and loses: it gets "no usable checkpoint" (the fold
    /// then rebuilds from the log), never a checkpoint of some other state.
    @Test
    void aLoaderHoldingTheReplacedPointer_getsNoUsableCheckpoint() {
        var stale = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), noopSerializer(), noopDeserializer());
        var both = new AtomicBoolean(true);
        var writer = substrate(checkpointReclaim(TimeSpan.timeSpan(0).millis(), FAST_RETRY), commands -> {
            if (both.get()) {
                stale.process(stale.createBatch(commands));
            }

            return apply(commands);
        });
        var loader = substrate(checkpointReclaim(LONG_GRACE, FAST_RETRY), stale, this::apply);
        var displaced = save(writer, 0, 1L, snapshot("v1"), Option.none());

        both.set(false);
        save(writer, 0, 2L, snapshot("v2"), Option.none());
        eventually("the displaced block is deleted", 5_000L, () -> !readable(displaced));
        assertThat(loader.loadCheckpoint(KEYSPACE, 0).await().unwrap().isEmpty()).as("no usable checkpoint").isTrue();
    }

    /// Crash between the block put and the swap (the publish fails): the replaced pointer and its block stay; the new block leaks (L-12).
    @Test
    void aFailedPublish_deletesNothing() {
        var fail = new AtomicBoolean();
        var substrate = substrate(checkpointReclaim(TimeSpan.timeSpan(0).millis(), FAST_RETRY), commands -> fail.get()
                                                                                                         ? Causes.cause("consensus unreachable").<List<Object>> promise()
                                                                                                         : apply(commands));
        var kept = save(substrate, 0, 1L, snapshot("v1"), Option.none());

        fail.set(true);
        assertThat(substrate.saveCheckpoint(KEYSPACE, 0, 2L, snapshot("v2"), Option.none()).await().isFailure()).isTrue();
        pause(PAST_SHORT_GRACE_MS);
        assertThat(pointed(0)).isEqualTo(kept);
        assertThat(readable(kept)).as("the block the pointer names stays").isTrue();
        assertThat(shared.puts.getLast()).as("the new block was written").isNotEqualTo(kept);
        assertThat(readable(shared.puts.getLast())).as("and leaks").isTrue();
        assertThat(shared.deletes).isEmpty();
    }

    /// Crash between the swap and the scheduled delete (the grace not yet passed): both blocks are there, the current one readable; a
    /// crash now leaks the displaced one (L-12), never the current one.
    @Test
    void beforeTheGrace_bothBlocksAreThere() {
        var substrate = substrate(checkpointReclaim(LONG_GRACE, FAST_RETRY));
        var displaced = save(substrate, 0, 1L, snapshot("v1"), Option.none());
        var current = save(substrate, 0, 2L, snapshot("v2"), Option.none());

        pause(PAST_SHORT_GRACE_MS);
        assertThat(readable(current)).isTrue();
        assertThat(readable(displaced)).isTrue();
    }

    /// The production reclaim, through the factory production calls: its grace covers two checkpoint ticks (the reason it was chosen), so
    /// right after a swap the displaced block is still there.
    @Test
    void theProductionReclaim_keepsTheDisplacedBlockForTwoCheckpointTicks() {
        assertThat(StreamEntityLogSubstrate.CHECKPOINT_RECLAIM.grace().millis()).as("two checkpoint ticks")
                                                                                .isGreaterThanOrEqualTo(2 * AetherNode.ENTITY_CHECKPOINT_INTERVAL.millis());
        var substrate = StreamEntityLogSubstrate.streamEntityLogSubstrate(manager,
                                                                          (_, _) -> new StreamPartitionManager.ReplicaCatchupSource.CatchupView(0, true),
                                                                          null,
                                                                          new SegmentIndex(),
                                                                          EvictionListener.NOOP,
                                                                          storage,
                                                                          kv,
                                                                          this::apply);
        var displaced = save(substrate, 0, 1L, snapshot("v1"), Option.none());

        save(substrate, 0, 2L, snapshot("v2"), Option.none());
        pause(PAST_SHORT_GRACE_MS);
        assertThat(readable(displaced)).as("inside the production grace").isTrue();
    }

    /// Hazard (v-2516 F1): the claim falls back outside the fence (the pointer removed, then rewritten at the same claim with identical
    /// bytes, which name the same block) while the delete of that block is pending. The delete checks the pointer when it runs and keeps
    /// the block the pointer names again.
    @Test
    void aPointerRemovedAndRewrittenAtTheSameClaim_duringTheGrace_keepsTheBlockItNames() {
        var substrate = substrate(checkpointReclaim(TimeSpan.timeSpan(1).seconds(), FAST_RETRY));
        var first = save(substrate, 0, 1L, snapshot("same"), Option.none());

        save(substrate, 0, 2L, snapshot("two"), Option.none());
        apply(List.of(new KVCommand.Remove<AetherKey>(EntityCheckpointKey.entityCheckpointKey(KEYSPACE, 0))));
        assertThat(kv.getTyped(EntityCheckpointKey.entityCheckpointKey(KEYSPACE, 0), EntityFoldCheckpointValue.class).isPresent()).as("control: the pointer is removed")
                                                                                                                         .isFalse();
        apply(List.of(new KVCommand.Put<AetherKey, AetherValue>(EntityCheckpointKey.entityCheckpointKey(KEYSPACE, 0),
                                                                EntityFoldCheckpointValue.entityFoldCheckpointValue(1L, first.hexString()))));
        assertThat(pointed(0)).as("control: the rewritten pointer names the displaced block again").isEqualTo(first);
        pause(3_000L);
        assertThat(readable(first)).as("the block the current pointer names is readable").isTrue();
    }

    /// The same hazard on a partition other than 0 (v-2516 delta): the check reads the pointer of the partition in the block's header, so
    /// a lookup of any other key (no pointer of partition 0 exists here) would delete the block the pointer names.
    @Test
    void aPointerRemovedAndRewrittenAtTheSameClaim_onPartition3_keepsTheBlockItNames() {
        var substrate = substrate(checkpointReclaim(TimeSpan.timeSpan(1).seconds(), FAST_RETRY));
        var first = save(substrate, 3, 1L, snapshot("same"), Option.none());

        save(substrate, 3, 2L, snapshot("two"), Option.none());
        apply(List.of(new KVCommand.Remove<AetherKey>(EntityCheckpointKey.entityCheckpointKey(KEYSPACE, 3))));
        apply(List.of(new KVCommand.Put<AetherKey, AetherValue>(EntityCheckpointKey.entityCheckpointKey(KEYSPACE, 3),
                                                                EntityFoldCheckpointValue.entityFoldCheckpointValue(1L, first.hexString()))));
        assertThat(pointed(3)).as("control: the rewritten pointer names the displaced block again").isEqualTo(first);
        assertThat(kv.getTyped(EntityCheckpointKey.entityCheckpointKey(KEYSPACE, 0), EntityFoldCheckpointValue.class).isPresent()).as("control: no pointer of partition 0")
                                                                                                                         .isFalse();
        pause(3_000L);
        assertThat(readable(first)).as("the block the current pointer names is readable").isTrue();
    }

    /// Cost (v-2516 delta): a present block with an identity header is checked by reading its own pointer, so its delete scans nothing; a
    /// block without the header costs one full scan of the KV per delete attempt (the control, which shows the count sees a scan).
    @Test
    void aDisplacedHeaderBlock_isCheckedWithoutAScan_andAPreHeaderBlockWithOne() {
        var substrate = substrate(checkpointReclaim(TimeSpan.timeSpan(0).millis(), FAST_RETRY));

        for (long claim = 1; claim <= 6; claim++) {
            save(substrate, 0, claim, snapshot("v" + claim), Option.none());
        }

        eventually("the five displaced blocks are deleted", 5_000L, () -> shared.deletes.size() >= 5);
        assertThat(shared.deletes.stream().distinct().count()).as("control: five displaced blocks deleted").isEqualTo(5L);
        assertThat(scans.get()).as("full KV scans for five present header blocks").isZero();
        var legacy = storage.put(snapshot("legacy")).await().unwrap();

        apply(List.of(new KVCommand.Put<AetherKey, AetherValue>(EntityCheckpointKey.entityCheckpointKey(KEYSPACE, 1),
                                                                EntityFoldCheckpointValue.entityFoldCheckpointValue(1L, legacy.hexString()))));
        save(substrate, 1, 2L, snapshot("partition 1, later"), Option.none());
        eventually("control: the pre-header block is deleted", 5_000L, () -> !readable(legacy));
        assertThat(scans.get()).as("control: the pre-header block's one delete attempt scanned the KV once").isEqualTo(1);
    }

    /// Upgrade window (v-2516 F1): a block written before the identity header is content-addressed with no identity, so pointers of two
    /// partitions, or of two keyspaces, with byte-identical folds name one block. The first new save of one partition displaces it; the
    /// delete finds the other pointers still naming it and keeps it.
    @Test
    void aPreHeaderBlockNamedByOtherPointers_survivesTheFirstNewSaveOfOnePartition() {
        var substrate = substrate(checkpointReclaim(SHORT_GRACE, FAST_RETRY));
        var legacy = storage.put(snapshot("shared")).await().unwrap();
        var otherKeyspace = EntityCheckpointKey.entityCheckpointKey("events", 0);

        for (var key : List.of(EntityCheckpointKey.entityCheckpointKey(KEYSPACE, 0), EntityCheckpointKey.entityCheckpointKey(KEYSPACE, 1), otherKeyspace)) {
            apply(List.of(new KVCommand.Put<AetherKey, AetherValue>(key, EntityFoldCheckpointValue.entityFoldCheckpointValue(1L, legacy.hexString()))));
        }

        assertThat(pointed(0)).as("control: the pointers name the pre-header block").isEqualTo(legacy);
        assertThat(pointed(1)).isEqualTo(legacy);
        save(substrate, 0, 2L, snapshot("partition 0, later"), Option.none());
        assertThat(pointed(0)).as("control: partition 0 moved off it").isNotEqualTo(legacy);
        pause(PAST_SHORT_GRACE_MS);
        assertThat(pointed(1)).isEqualTo(legacy);
        assertThat(readable(legacy)).as("the block the other pointers name").isTrue();
        save(substrate, 1, 2L, snapshot("partition 1, later"), Option.none());
        assertThat(pointed(1)).as("control: partition 1 moved off it too").isNotEqualTo(legacy);
        pause(PAST_SHORT_GRACE_MS);
        assertThat(readable(legacy)).as("the block only another keyspace's pointer names").isTrue();
    }

    /// #3480 P2a: a restart over a storage whose private durable tier holds the committed block re-saves nothing at the committed claim, so
    /// nothing is displaced at an equal claim and nothing is parked in a deferral a further restart loses. Three node lifetimes (three fresh
    /// substrates over one storage): save at 5, restart and save at 5 again, restart and advance to 6. Every block the claim 6 passed goes.
    @Test
    void aRestartOverAPrivateDurableCopy_writesNothingAtTheCommittedClaim_andTheNextAdvanceReclaimsEveryBlock() {
        useStorageWithAPrivateDurableTier();
        var committed = save(substrate(checkpointReclaim(SHORT_GRACE, FAST_RETRY)), 0, 5L, snapshot("same"), Option.none());
        var putsBeforeRestart = shared.puts.size();

        assertThat(save(substrate(checkpointReclaim(SHORT_GRACE, FAST_RETRY)), 0, 5L, snapshot("same"), Option.none()))
            .as("the restart's save at the committed claim keeps the committed block").isEqualTo(committed);
        assertThat(shared.puts).as("the restart's save wrote nothing").hasSize(putsBeforeRestart);
        var advanced = save(substrate(checkpointReclaim(SHORT_GRACE, FAST_RETRY)), 0, 6L, snapshot("later"), Option.none());

        eventually("every block the claim 6 passed is reclaimed", 5_000L, () -> leaked(advanced).isEmpty());
        assertThat(readable(advanced)).as("the block the pointer names is kept").isTrue();
    }

    /// #3480 A5: the skip also needs equal content. A restart over a private durable copy at the committed claim, whose lineage differs (the
    /// sealed floor now rides in it), RE-SAVES: the pointer moves to a block of its own and the committed block is no longer the one named.
    @Test
    void anEqualClaimWithChangedLineage_isReSaved_evenWithAPrivateDurableCopyHeld() {
        useStorageWithAPrivateDurableTier();
        var committed = save(substrate(checkpointReclaim(LONG_GRACE, FAST_RETRY)), 0, 5L, snapshot("same"), Option.some(LINEAGE));
        var putsBeforeRestart = shared.puts.size();
        var changed = "another lineage".getBytes(StandardCharsets.UTF_8);

        var resaved = save(substrate(checkpointReclaim(LONG_GRACE, FAST_RETRY)), 0, 5L, snapshot("same"), Option.some(changed));

        assertThat(shared.puts).as("the changed lineage was written").hasSize(putsBeforeRestart + 1);
        assertThat(resaved).as("the pointer names the block carrying the changed lineage").isNotEqualTo(committed);
    }

    /// #3480 A5 (t3480t, kills sc-ignoreLastByte): a snapshot-only change at the committed claim, the lineage unchanged, re-saves too.
    @Test
    void anEqualClaimWithChangedSnapshot_isReSaved_evenWithAPrivateDurableCopyHeld() {
        useStorageWithAPrivateDurableTier();
        var committed = save(substrate(checkpointReclaim(LONG_GRACE, FAST_RETRY)), 0, 5L, snapshot("fold-a"), Option.some(LINEAGE));
        var putsBeforeRestart = shared.puts.size();

        var resaved = save(substrate(checkpointReclaim(LONG_GRACE, FAST_RETRY)), 0, 5L, snapshot("fold-b"), Option.some(LINEAGE));

        assertThat(shared.puts).as("the changed snapshot was written").hasSize(putsBeforeRestart + 1);
        assertThat(resaved).as("the pointer names the block carrying the changed snapshot").isNotEqualTo(committed);
    }

    /// #3480 A5 (t3480t, kills A5-failedCheckSkips), FER "when unsure, re-save": a check on the private durable tier that fails is not an
    /// answer that the block is held, so the save at the committed claim writes its block.
    @Test
    void aFailedCheckOnThePrivateDurableTier_isReSaved() {
        var durable = new FaultyDurableTier(privateDurableTier());
        storage.shutdown();
        storage = storageOver(MemoryTier.memoryTier(ONE_GB), durable);
        var committed = save(substrate(checkpointReclaim(LONG_GRACE, FAST_RETRY)), 0, 5L, snapshot("same"), Option.none());
        var putsBeforeRestart = shared.puts.size();

        durable.failExists = true;
        var resaved = save(substrate(checkpointReclaim(LONG_GRACE, FAST_RETRY)), 0, 5L, snapshot("same"), Option.none());

        assertThat(shared.puts).as("the unanswered check was not taken as held").hasSize(putsBeforeRestart + 1);
        assertThat(resaved).isNotEqualTo(committed);
    }

    /// #3480 A5 (t3480t, kills A5-unknownBlockSkips): a tier that says it holds the committed block but whose read finds nothing gives no
    /// content to compare, so the save at the committed claim writes its block.
    @Test
    void aHeldBlockWhoseReadFindsNothing_isReSaved() {
        var durable = new FaultyDurableTier(privateDurableTier());
        storage.shutdown();
        var cache = new FaultyDurableTier(MemoryTier.memoryTier(ONE_GB));
        storage = storageOver(cache, durable);
        var committed = save(substrate(checkpointReclaim(LONG_GRACE, FAST_RETRY)), 0, 5L, snapshot("same"), Option.none());
        var putsBeforeRestart = shared.puts.size();

        cache.hideGets = true;
        durable.hideGets = true;
        shared.hideGets = true;
        var resaved = save(substrate(checkpointReclaim(LONG_GRACE, FAST_RETRY)), 0, 5L, snapshot("same"), Option.none());

        assertThat(storage.get(committed).await().unwrap().isEmpty()).as("control: the committed block reads as absent").isTrue();
        assertThat(durable.exists(committed).await().unwrap()).as("control: the private durable tier still says it holds it").isTrue();
        assertThat(shared.puts).as("the absent block was not taken as the same content").hasSize(putsBeforeRestart + 1);
        assertThat(resaved).isNotEqualTo(committed);
    }

    /// #3480 handover safety (t3480t, kills A1-dropNotShared): the production remote tier is shared AND durable. A new owner whose storage
    /// holds the committed block only there still writes a durable block of its own.
    @Test
    void aNewOwnerHoldingTheBlockOnlyInASharedDurableTier_writesItsOwnDurableBlock() {
        var previous = save(substrate(checkpointReclaim(LONG_GRACE, FAST_RETRY)), 0, 5L, snapshot("same"), Option.none());
        var newOwner = privateDurableTier();

        shared.durable = true;
        var newOwnerStorage = storageOver(MemoryTier.memoryTier(ONE_GB), newOwner);

        assertThat(newOwnerStorage.exists(previous).await().unwrap()).as("control: the shared durable tier answers for the new owner").isTrue();
        assertNewOwnerWritesItsOwnDurableBlock(newOwnerStorage, newOwner, previous);
    }

    /// #3480 handover safety: a new owner whose storage holds the committed block only in the cluster-shared tier still writes a durable
    /// block of its own at the committed claim. A shared copy is not this node's: `exists` would answer for the whole cluster.
    @Test
    void aNewOwnerHoldingTheBlockOnlyInTheSharedTier_writesItsOwnDurableBlock() {
        var previous = save(substrate(checkpointReclaim(LONG_GRACE, FAST_RETRY)), 0, 5L, snapshot("same"), Option.none());
        var newOwner = privateDurableTier();
        var newOwnerStorage = storageOver(MemoryTier.memoryTier(ONE_GB), newOwner);

        assertThat(newOwnerStorage.exists(previous).await().unwrap()).as("control: the shared tier answers for the new owner").isTrue();
        assertNewOwnerWritesItsOwnDurableBlock(newOwnerStorage, newOwner, previous);
    }

    /// #3480 handover safety: a new owner whose storage holds the committed block in the shared tier AND its own cache (the copy the
    /// restore's read, or a warm, promoted) still writes a durable block of its own: a cache copy does not survive a restart.
    @Test
    void aNewOwnerHoldingTheBlockInTheSharedTierAndItsCache_writesItsOwnDurableBlock() {
        var previous = save(substrate(checkpointReclaim(LONG_GRACE, FAST_RETRY)), 0, 5L, snapshot("same"), Option.none());
        var cache = MemoryTier.memoryTier(ONE_GB);
        var newOwner = privateDurableTier();
        var newOwnerStorage = storageOver(cache, newOwner);

        assertThat(newOwnerStorage.warm(previous).await().unwrap()).as("control: the warm copied the block into the cache").isTrue();
        assertThat(cache.exists(previous).await().unwrap()).as("control: the new owner's cache holds the block").isTrue();
        assertNewOwnerWritesItsOwnDurableBlock(newOwnerStorage, newOwner, previous);
    }

    /// #3480 P2b, ENABLED tripwire for the residual #3482 tracks: a handover with no advance. The new owner holds no private durable copy, so
    /// it re-saves at the committed claim and parks the displaced block in its in-memory deferral; the next owner advances, and that block
    /// leaks (L-12: nothing sweeps it). The fixture's storage, whose only private tier is a cache, stands in for each owner's: none of them
    /// holds a private durable copy. This asserts the leak as it is today. When #3482 closes it, this goes red: delete it and enable
    /// [#aHandoverWithoutAnAdvance_reclaimsEveryBlockTheNextAdvancePasses].
    @Test
    void aHandoverWithoutAnAdvance_leaksTheBlockTheNewOwnerDisplacedAtAnEqualClaim_tripwire3482() {
        var displaced = save(substrate(checkpointReclaim(SHORT_GRACE, FAST_RETRY)), 0, 5L, snapshot("same"), Option.none());
        var newOwnerCopy = save(substrate(checkpointReclaim(SHORT_GRACE, FAST_RETRY)), 0, 5L, snapshot("same"), Option.none());

        assertThat(newOwnerCopy).as("control: the new owner wrote a block of its own").isNotEqualTo(displaced);
        var advanced = save(substrate(checkpointReclaim(SHORT_GRACE, FAST_RETRY)), 0, 6L, snapshot("later"), Option.none());

        eventually("the claim 6 reclaims the block it displaced", 5_000L, () -> !readable(newOwnerCopy));
        pause(PAST_SHORT_GRACE_MS);
        assertThat(leaked(advanced)).as("tripwire #3482: the block the new owner displaced leaks; if this fails, #3482 fixed it: delete this"
                                        + " test and enable aHandoverWithoutAnAdvance_reclaimsEveryBlockTheNextAdvancePasses")
                                    .containsExactly(displaced);
    }

    /// #3480 P2b, the real assertion: a handover without an advance leaks nothing once the next advance passes it.
    @Disabled("#3482: the equal-claim deferral lives in one node's memory and nothing sweeps a lost entry (L-12)")
    @Test
    void aHandoverWithoutAnAdvance_reclaimsEveryBlockTheNextAdvancePasses() {
        save(substrate(checkpointReclaim(SHORT_GRACE, FAST_RETRY)), 0, 5L, snapshot("same"), Option.none());
        save(substrate(checkpointReclaim(SHORT_GRACE, FAST_RETRY)), 0, 5L, snapshot("same"), Option.none());
        var advanced = save(substrate(checkpointReclaim(SHORT_GRACE, FAST_RETRY)), 0, 6L, snapshot("later"), Option.none());

        eventually("every block the claim 6 passed is reclaimed", 5_000L, () -> leaked(advanced).isEmpty());
    }

    private void assertNewOwnerWritesItsOwnDurableBlock(StorageInstance newOwnerStorage, StorageTier newOwner, BlockId previous) {
        assertThat(newOwner.exists(previous).await().unwrap()).as("control: the new owner's durable tier lacks the block").isFalse();
        var written = save(substrate(checkpointReclaim(LONG_GRACE, FAST_RETRY), newOwnerStorage), 0, 5L, snapshot("same"), Option.none());

        assertThat(written).as("the new owner's save at the committed claim wrote a block of its own").isNotEqualTo(previous);
        assertThat(newOwner.exists(written).await().unwrap()).as("on its own durable tier").isTrue();
    }

    /// #2536: a put that failed indeterminately (the quorum was missed, some replicas hold the block) is never named by a pointer. Once a later
    /// save of the same partition succeeds and its swap is confirmed, the block is deleted; before that nothing confirms and it is kept.
    @Test
    void anIndeterminatePutThatLanded_isDeletedOnceALaterSaveConfirms() {
        var substrate = substrate(checkpointReclaim(TimeSpan.timeSpan(0).millis(), FAST_RETRY));
        var kept = save(substrate, 0, 1L, snapshot("v1"), Option.none());

        var stale = failedPutThatLanded(substrate, 0, 2L);

        assertThat(pointed(0)).as("control: the failed save moved no pointer").isEqualTo(kept);
        pause(PAST_SHORT_GRACE_MS);
        assertThat(readable(stale)).as("nothing has confirmed a later save yet").isTrue();
        var current = save(substrate, 0, 2L, snapshot("v2"), Option.none());

        eventually("the stale block is deleted once the retry's swap is confirmed", 5_000L, () -> !readable(stale));
        assertThat(deletesOf(stale)).as("through StorageInstance#delete, once").isEqualTo(1L);
        assertThat(readable(current)).as("the block the pointer names is kept").isTrue();
        assertThat(pointed(0)).isEqualTo(current);
    }

    /// The same, when the later save advances the claim: the idle-partition retry above and a fold that moved on both reclaim the block.
    @Test
    void anIndeterminatePutThatLanded_isDeletedWhenTheLaterSaveAdvancesTheClaim() {
        var substrate = substrate(checkpointReclaim(TimeSpan.timeSpan(0).millis(), FAST_RETRY));

        save(substrate, 0, 1L, snapshot("v1"), Option.none());
        var stale = failedPutThatLanded(substrate, 0, 2L);
        var current = save(substrate, 0, 3L, snapshot("v3"), Option.none());

        eventually("the stale block is deleted", 5_000L, () -> !readable(stale));
        assertThat(readable(current)).isTrue();
    }

    /// Two attempts that each landed a block of their own (every put carries a fresh nonce): one confirmed save deletes both, each once.
    @Test
    void twoIndeterminateAttempts_areBothDeletedByTheNextConfirmedSave() {
        var substrate = substrate(checkpointReclaim(TimeSpan.timeSpan(0).millis(), FAST_RETRY));
        var firstStale = failedPutThatLanded(substrate, 0, 1L);
        var secondStale = failedPutThatLanded(substrate, 0, 1L);

        assertThat(secondStale).as("control: the second attempt landed a block of its own").isNotEqualTo(firstStale);
        var current = save(substrate, 0, 1L, snapshot("v1"), Option.none());

        eventually("both stale blocks are deleted", 5_000L, () -> !readable(firstStale) && !readable(secondStale));
        pause(PAST_SHORT_GRACE_MS);
        assertThat(deletesOf(firstStale)).isEqualTo(1L);
        assertThat(deletesOf(secondStale)).isEqualTo(1L);
        assertThat(readable(current)).isTrue();
    }

    /// A save with no indeterminate attempt before it deletes what the swap displaced and nothing else.
    @Test
    void aSuccessWithNoPriorIndeterminateAttempt_deletesNothingExtra() {
        var substrate = substrate(checkpointReclaim(TimeSpan.timeSpan(0).millis(), FAST_RETRY));
        var displaced = save(substrate, 0, 1L, snapshot("v1"), Option.none());

        save(substrate, 0, 2L, snapshot("v2"), Option.none());
        eventually("the displaced block is deleted", 5_000L, () -> !readable(displaced));
        pause(PAST_SHORT_GRACE_MS);
        assertThat(shared.deletes).as("only the displaced block").containsOnly(displaced);
    }

    /// The delete of a stale block that the store refuses is retried within the bound; past it the block stays recorded, and the next
    /// confirmed save deletes it.
    @Test
    void aRefusedDeleteOfAStaleBlock_staysRecorded_andIsRetriedByTheNextConfirmedSave() {
        var substrate = substrate(checkpointReclaim(TimeSpan.timeSpan(0).millis(), FAST_RETRY));
        var stale = failedPutThatLanded(substrate, 0, 1L);

        shared.refuseDeletes.set(DELETE_ATTEMPTS);
        var first = save(substrate, 0, 1L, snapshot("v1"), Option.none());

        eventually("the bound is spent", 5_000L, () -> deletesOf(stale) >= DELETE_ATTEMPTS);
        pause(PAST_SHORT_GRACE_MS);
        assertThat(deletesOf(stale)).as("exactly the bound, then no more").isEqualTo(DELETE_ATTEMPTS);
        assertThat(readable(stale)).as("the refused block is still there").isTrue();
        save(substrate, 0, 2L, snapshot("v2"), Option.none());
        eventually("the next confirmed save deletes it", 5_000L, () -> !readable(stale) && !readable(first));
        assertThat(deletesOf(stale)).as("the bound, then the accepted attempt").isEqualTo(DELETE_ATTEMPTS + 1L);
    }

    /// A save the fence refused confirms nothing: the stale block is kept (the writer deletes only its own refused block), and it goes
    /// with the next save whose swap is confirmed.
    @Test
    void aRefusedSave_confirmsNothing_soTheStaleBlockWaitsForTheNextConfirmedSave() {
        var substrate = substrate(checkpointReclaim(TimeSpan.timeSpan(0).millis(), FAST_RETRY));
        var committed = save(substrate, 0, 5L, snapshot("v5"), Option.none());
        var stale = failedPutThatLanded(substrate, 0, 2L);

        save(substrate, 0, 2L, snapshot("v2"), Option.none());
        var refused = shared.puts.getLast();

        assertThat(pointed(0)).as("control: the lower claim was refused").isEqualTo(committed);
        eventually("the writer's own refused block is deleted", 5_000L, () -> !readable(refused));
        pause(PAST_SHORT_GRACE_MS);
        assertThat(readable(stale)).as("a refused swap confirms nothing").isTrue();
        var current = save(substrate, 0, 6L, snapshot("v6"), Option.none());

        eventually("a confirmed save deletes the stale block", 5_000L, () -> !readable(stale));
        assertThat(readable(current)).isTrue();
    }

    /// The delete checks the pointers when it runs: a stale block a pointer names by then (a restore outside the fence names it) is kept.
    @Test
    void aStaleBlockNamedByAPointerWhenTheDeleteRuns_isNeverDeleted() {
        var substrate = substrate(checkpointReclaim(TimeSpan.timeSpan(1).seconds(), FAST_RETRY));
        var stale = failedPutThatLanded(substrate, 0, 1L);

        save(substrate, 0, 1L, snapshot("v1"), Option.none());
        apply(List.of(new KVCommand.Put<AetherKey, AetherValue>(EntityCheckpointKey.entityCheckpointKey(KEYSPACE, 0),
                                                                EntityFoldCheckpointValue.entityFoldCheckpointValue(1L, stale.hexString()))));
        assertThat(pointed(0)).as("control: a pointer names the stale block").isEqualTo(stale);
        pause(3_000L);
        assertThat(readable(stale)).as("the block a pointer names is kept").isTrue();
        assertThat(deletesOf(stale)).isZero();
    }

    /// The record is bounded (16 per pointer): past it the oldest block is forgotten and stays in storage; the rest are deleted. The 16 is
    /// pinned here on purpose: a changed bound is a decision, not a refactor.
    @Test
    void moreFailedPutsThanTheBound_forgetTheOldest_andDeleteTheRest() {
        var substrate = substrate(checkpointReclaim(TimeSpan.timeSpan(0).millis(), FAST_RETRY));
        var stale = IntStream.range(0, 17).mapToObj(_ -> failedPutThatLanded(substrate, 0, 1L)).toList();

        save(substrate, 0, 1L, snapshot("v1"), Option.none());
        eventually("the 16 newest are deleted", 10_000L, () -> stale.stream().skip(1).noneMatch(this::readable));
        pause(PAST_SHORT_GRACE_MS);
        assertThat(readable(stale.getFirst())).as("the oldest was forgotten, and stays").isTrue();
        assertThat(deletesOf(stale.getFirst())).isZero();
    }

    /// #2536 deviation, question 1 (a put that failed at the caller landing LATER than the later confirmed save): the block lands after the
    /// delete ran, so it stays (a leak, the L-12 class, stated as a limit). Nothing names it: only a put that succeeded is published
    /// (`writeThenPublish`), so the pointer, the confirmed block and the reads are untouched. Red if a late landing could move the pointer.
    @Test
    void aFailedPutThatLandsAfterTheLaterConfirmedSave_isALeak_neverANamedBlock() {
        var substrate = substrate(checkpointReclaim(TimeSpan.timeSpan(0).millis(), FAST_RETRY));

        shared.failThenLandLater.set(1);
        assertThat(substrate.saveCheckpoint(KEYSPACE, 0, 5L, snapshot("lost"), Option.none()).await().isFailure()).as("control: the put failed").isTrue();
        var stale = shared.puts.getLast();
        var current = save(substrate, 0, 5L, snapshot("v5"), Option.none());

        eventually("the delete of the stale block ran while it was absent", 5_000L, () -> deletesOf(stale) >= 1L);
        shared.landPending();
        assertThat(readable(stale)).as("control: the block landed after the delete").isTrue();
        pause(PAST_SHORT_GRACE_MS);
        assertThat(pointed(0)).as("the late landing names nothing").isEqualTo(current);
        assertThat(readable(current)).isTrue();
        assertThat(namedBlocks()).as("no pointer names the late block").doesNotContain(stale);
    }

    /// #2536 deviation, question 2 (a recorded id named by a pointer another node wrote): another node at the SAME claim writes a block of its
    /// own (fresh 128-bit nonce per frame), so its pointer cannot name the recorded id. The first node's confirmed save at the equal claim
    /// deletes the stale block and nothing else: the other node's block is displaced at an equal claim and deferred, not deleted.
    @Test
    void aStaleBlockIsNeverNamedByTheOtherNodesPointer_andItsReleaseDeletesNothingElse() {
        var first = substrate(checkpointReclaim(TimeSpan.timeSpan(0).millis(), FAST_RETRY));
        var second = substrate(checkpointReclaim(TimeSpan.timeSpan(0).millis(), FAST_RETRY),
                               storageOver(MemoryTier.memoryTier(ONE_GB), MemoryTier.memoryTier(ONE_GB)));
        var stale = failedPutThatLanded(first, 0, 5L);
        var takeover = save(second, 0, 5L, snapshot("v5-other"), Option.none());

        assertThat(takeover).as("the other node's pointer names a block of its own").isNotEqualTo(stale);
        var current = save(first, 0, 5L, snapshot("v5"), Option.none());

        eventually("the stale block is deleted", 5_000L, () -> !readable(stale));
        pause(PAST_SHORT_GRACE_MS);
        assertThat(shared.deletes).as("the other node's block is deferred at the equal claim, not deleted").containsOnly(stale);
        assertThat(readable(current)).isTrue();
        assertThat(readable(takeover)).isTrue();
    }

    /// Fails one save at `through` after its block landed, and answers that block.
    private BlockId failedPutThatLanded(EntityLogSubstrate substrate, int partition, long through) {
        shared.landThenFailPuts.set(1);
        assertThat(substrate.saveCheckpoint(KEYSPACE, partition, through, snapshot("lost-" + through), Option.none()).await().isFailure())
                .as("control: the put failed indeterminately").isTrue();
        var stale = shared.puts.getLast();

        assertThat(readable(stale)).as("control: the failed put landed").isTrue();

        return stale;
    }

    /// Every block the shared tier was given that is still readable, other than `current`.
    private List<BlockId> leaked(BlockId current) {
        return shared.puts.stream().distinct().filter(id -> !id.equals(current)).filter(this::readable).toList();
    }

    private Promise<List<Object>> recordThenApply(AtomicReference<List<KVCommand<AetherKey>>> recorded, List<KVCommand<AetherKey>> commands) {
        recorded.set(commands);

        return apply(commands);
    }

    /// Replaces the fixture's storage, whose only private tier is a cache, with one that has a private durable tier too.
    private void useStorageWithAPrivateDurableTier() {
        storage.shutdown();
        storage = storageOver(MemoryTier.memoryTier(ONE_GB), privateDurableTier());
    }

    /// A node's storage: a private cache, a private durable tier and the cluster-shared tier every node in the test sees. Shut down in
    /// [#tearDown].
    private StorageInstance storageOver(StorageTier cache, StorageTier durable) {
        var name = "entity-ckpt-" + durables.incrementAndGet();
        var built = StorageInstance.storageInstance(name, List.of(cache, durable, shared), MetadataStore.inMemoryMetadataStore(name));

        extraStorages.add(built);

        return built;
    }

    private StorageTier privateDurableTier() {
        return LocalDiskTier.localDiskTier(diskRoot.resolve("node-" + durables.incrementAndGet()), ONE_GB).unwrap();
    }

    private EntityLogSubstrate substrate(CheckpointReclaim reclaim) {
        return substrate(reclaim, this::apply);
    }

    private EntityLogSubstrate substrate(CheckpointReclaim reclaim, Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier) {
        return substrate(reclaim, kv, applier);
    }

    private EntityLogSubstrate substrate(CheckpointReclaim reclaim, StorageInstance over) {
        return substrate(reclaim, over, kv, this::apply);
    }

    private EntityLogSubstrate substrate(CheckpointReclaim reclaim,
                                         KVStore<AetherKey, AetherValue> reads,
                                         Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier) {
        return substrate(reclaim, storage, reads, applier);
    }

    private EntityLogSubstrate substrate(CheckpointReclaim reclaim,
                                         StorageInstance over,
                                         KVStore<AetherKey, AetherValue> reads,
                                         Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier) {
        return StreamEntityLogSubstrate.streamEntityLogSubstrate(manager,
                                                                 (_, _) -> new StreamPartitionManager.ReplicaCatchupSource.CatchupView(0, true),
                                                                 null,
                                                                 new SegmentIndex(),
                                                                 EvictionListener.NOOP,
                                                                 over,
                                                                 reads,
                                                                 applier,
                                                                 reclaim);
    }

    private Promise<List<Object>> apply(List<KVCommand<AetherKey>> commands) {
        kv.process(kv.createBatch(commands));

        return Promise.success(List.of());
    }

    private BlockId save(EntityLogSubstrate substrate, String keyspace, int partition, long through, byte[] snapshot) {
        assertThat(substrate.saveCheckpoint(keyspace, partition, through, snapshot, Option.none()).await().isSuccess()).isTrue();

        return BlockId.fromHex(kv.getTyped(EntityCheckpointKey.entityCheckpointKey(keyspace, partition), EntityFoldCheckpointValue.class)
                                 .or(() -> fail("no pointer"))
                                 .blockIdHex())
                      .unwrap();
    }

    private long deletesOf(BlockId id) {
        return shared.deletes.stream().filter(id::equals).count();
    }

    /// Saves, and answers the block the committed pointer names afterwards.
    private BlockId save(EntityLogSubstrate substrate, int partition, long through, byte[] snapshot, Option<byte[]> lineage) {
        assertThat(substrate.saveCheckpoint(KEYSPACE, partition, through, snapshot, lineage).await().isSuccess()).as("the save resolves").isTrue();

        return pointed(partition);
    }

    private BlockId pointed(int partition) {
        var pointer = kv.getTyped(EntityCheckpointKey.entityCheckpointKey(KEYSPACE, partition), EntityFoldCheckpointValue.class)
                        .or(() -> fail("no pointer for partition " + partition));

        return BlockId.fromHex(pointer.blockIdHex()).unwrap();
    }

    private List<BlockId> namedBlocks() {
        var named = new ArrayList<BlockId>();

        kv.forEach(EntityCheckpointKey.class, EntityFoldCheckpointValue.class, (_, pointer) -> named.add(BlockId.fromHex(pointer.blockIdHex()).unwrap()));

        return named;
    }

    private boolean readable(BlockId id) {
        return storage.get(id).await().unwrap().isPresent();
    }

    private byte[] block(BlockId id) {
        return storage.get(id).await().unwrap().or(() -> fail("block " + id + " is not readable"));
    }

    private static byte[] snapshot(String value) {
        return EntityFoldSnapshot.encode(Map.of("k", value.getBytes(StandardCharsets.UTF_8)), Map.of());
    }

    /// The rc5 reader, as `CheckpointFrame#unframe` was at `ffc0b6260d`: a block that does not start with `LNGCKPT1` is the snapshot alone,
    /// with no lineage.
    private static EntityLogSubstrate.EntityCheckpoint rc5Unframe(long throughOffset, byte[] block) {
        if (block.length < OLD_MARKER.length + Integer.BYTES || !Arrays.equals(block, 0, OLD_MARKER.length, OLD_MARKER, 0, OLD_MARKER.length)) {
            return EntityLogSubstrate.EntityCheckpoint.entityCheckpoint(throughOffset, block);
        }

        var buffer = ByteBuffer.wrap(block, OLD_MARKER.length, block.length - OLD_MARKER.length);
        var length = buffer.getInt();

        if (length < 0 || length > buffer.remaining()) {
            return EntityLogSubstrate.EntityCheckpoint.entityCheckpoint(throughOffset, block);
        }

        var identity = new byte[length];

        buffer.get(identity);
        var snapshot = new byte[buffer.remaining()];

        buffer.get(snapshot);

        return EntityLogSubstrate.EntityCheckpoint.entityCheckpoint(throughOffset, snapshot, Option.some(identity));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static void eventually(String what, long budgetMs, BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + budgetMs;

        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }

            pause(20L);
        }

        fail("not within " + budgetMs + " ms: " + what);
    }

    @SuppressWarnings("JBCT-EX-01")
    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted", e);
        }
    }

    /// A shared tier that records every put and delete; `refuseDeletes` refusals are answered first, as a DHT remove that missed its
    /// write quorum.
    private static final class RecordingTier implements StorageTier {
        private final StorageTier delegate = MemoryTier.memoryTier(ONE_GB);
        final List<BlockId> puts = new CopyOnWriteArrayList<>();
        final List<BlockId> deletes = new CopyOnWriteArrayList<>();
        final AtomicInteger refuseDeletes = new AtomicInteger();
        /// Puts that store the block and then fail, as a DHT write that missed its quorum with some replicas already holding the block.
        final AtomicInteger landThenFailPuts = new AtomicInteger();
        /// Puts that fail at the caller at once (a timeout while the replica writes are still in flight) and store the block only when
        /// [#landPending] runs: a block that lands after the caller was told it failed.
        final AtomicInteger failThenLandLater = new AtomicInteger();
        final Map<BlockId, byte[]> pendingLandings = new ConcurrentHashMap<>();
        /// Answers as the production remote tier does: shared and durable.
        volatile boolean durable;
        /// Reads find nothing, whatever the tier holds.
        volatile boolean hideGets;

        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            return hideGets
                   ? Promise.success(Option.none())
                   : delegate.get(id);
        }

        @Override
        public boolean isDurable() {
            return durable;
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            puts.add(id);

            if (failThenLandLater.getAndDecrement() > 0) {
                pendingLandings.put(id, content);

                return Causes.cause("write timed out, replica writes in flight").<Unit> promise();
            }

            return landThenFailPuts.getAndDecrement() > 0
                   ? delegate.put(id, content).flatMap(_ -> Causes.cause("write quorum missed, replicas may hold the block").<Unit> promise())
                   : delegate.put(id, content);
        }

        void landPending() {
            pendingLandings.forEach((id, content) -> delegate.put(id, content).await());
            pendingLandings.clear();
        }

        @Override
        public Promise<Unit> delete(BlockId id) {
            deletes.add(id);

            return refuseDeletes.getAndDecrement() > 0
                   ? Causes.cause("write quorum unreachable").promise()
                   : delegate.delete(id);
        }

        @Override
        public Promise<Boolean> exists(BlockId id) {
            return delegate.exists(id);
        }

        @Override
        public TierLevel level() {
            return TierLevel.REMOTE;
        }

        @Override
        public long usedBytes() {
            return delegate.usedBytes();
        }

        @Override
        public long maxBytes() {
            return delegate.maxBytes();
        }

        @Override
        public boolean isShared() {
            return true;
        }
    }

    /// A private durable tier over a real one, whose answers a test can spoil: `failExists` makes the existence check fail, `hideGets`
    /// makes a read find nothing while `exists` still says the block is there.
    private static final class FaultyDurableTier implements StorageTier {
        private final StorageTier delegate;
        volatile boolean failExists;
        volatile boolean hideGets;

        FaultyDurableTier(StorageTier delegate) {
            this.delegate = delegate;
        }

        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            return hideGets
                   ? Promise.success(Option.none())
                   : delegate.get(id);
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            return delegate.put(id, content);
        }

        @Override
        public Promise<Unit> delete(BlockId id) {
            return delegate.delete(id);
        }

        @Override
        public Promise<Boolean> exists(BlockId id) {
            return failExists
                   ? Causes.cause("tier unreachable").promise()
                   : delegate.exists(id);
        }

        @Override
        public TierLevel level() {
            return delegate.level();
        }

        @Override
        public long usedBytes() {
            return delegate.usedBytes();
        }

        @Override
        public long maxBytes() {
            return delegate.maxBytes();
        }

        @Override
        public boolean isDurable() {
            return delegate.isDurable();
        }

        @Override
        public Unit release() {
            return delegate.release();
        }
    }

    private static Serializer noopSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer noopDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
