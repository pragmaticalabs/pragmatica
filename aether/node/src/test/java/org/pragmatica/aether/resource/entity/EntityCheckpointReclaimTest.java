// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager(64L * 1024 * 1024, EvictionListener.NOOP, ReplicationManager.NONE, Option.none(), (_, _) -> -1L);
        shared = new RecordingTier();
        storage = StorageInstance.storageInstance("entity-ckpt",
                                                  List.of(MemoryTier.memoryTier(ONE_GB), shared),
                                                  MetadataStore.inMemoryMetadataStore("entity-ckpt"));
        kv = new KVStore<>(MessageRouter.mutable(), noopSerializer(), noopDeserializer());
    }

    @AfterEach
    void tearDown() {
        manager.close();
        storage.shutdown();
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

    /// Hazard: a late duplicate publish of an equal-claim block (an abandoned in-flight save landing after a newer swap) names it again,
    /// so it must still be there.
    @Test
    void aLateDuplicatePublishAtAnEqualClaim_findsItsBlockStillThere() {
        var substrate = substrate(checkpointReclaim(SHORT_GRACE, FAST_RETRY));
        var parked = new AtomicReference<Promise<List<Object>>>();
        var parkedCommands = new AtomicReference<List<KVCommand<AetherKey>>>();
        var late = substrate(checkpointReclaim(SHORT_GRACE, FAST_RETRY), commands -> {
            parkedCommands.set(commands);
            parked.set(Promise.promise());

            return parked.get();
        });
        var block = save(substrate, 0, 5L, snapshot("x"), Option.none());
        var lateSave = late.saveCheckpoint(KEYSPACE, 0, 5L, snapshot("x"), Option.none());

        eventually("the late save's publish is parked", 5_000L, () -> parked.get() != null);
        save(substrate, 0, 5L, snapshot("y"), Option.none());
        pause(PAST_SHORT_GRACE_MS);
        apply(parkedCommands.get());
        parked.get().succeed(List.of());
        assertThat(lateSave.await().isSuccess()).isTrue();
        assertThat(pointed(0)).as("control: the late publish named the equal-claim block again").isEqualTo(block);
        pause(PAST_SHORT_GRACE_MS);
        assertThat(readable(block)).as("the block the current pointer names is readable").isTrue();
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

    /// Hazard: identical bytes at one claim from two writers are one block; neither deletes it.
    @Test
    void identicalBytesAtOneClaim_fromTwoWriters_areOneBlock_andItIsKept() {
        var first = save(substrate(checkpointReclaim(SHORT_GRACE, FAST_RETRY)), 0, 5L, snapshot("same"), Option.none());
        var second = save(substrate(checkpointReclaim(SHORT_GRACE, FAST_RETRY)), 0, 5L, snapshot("same"), Option.none());

        assertThat(second).isEqualTo(first);
        pause(PAST_SHORT_GRACE_MS);
        assertThat(readable(first)).isTrue();
        assertThat(shared.deletes).isEmpty();
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
        var again = save(substrate, 0, 1L, snapshot("same"), Option.none());

        assertThat(again).as("control: identical bytes at the same claim name the same block").isEqualTo(first);
        pause(3_000L);
        assertThat(readable(pointed(0))).as("the block the current pointer names is readable").isTrue();
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

    private EntityLogSubstrate substrate(CheckpointReclaim reclaim) {
        return substrate(reclaim, this::apply);
    }

    private EntityLogSubstrate substrate(CheckpointReclaim reclaim, Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier) {
        return substrate(reclaim, kv, applier);
    }

    private EntityLogSubstrate substrate(CheckpointReclaim reclaim,
                                         KVStore<AetherKey, AetherValue> reads,
                                         Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier) {
        return StreamEntityLogSubstrate.streamEntityLogSubstrate(manager,
                                                                 (_, _) -> new StreamPartitionManager.ReplicaCatchupSource.CatchupView(0, true),
                                                                 null,
                                                                 new SegmentIndex(),
                                                                 EvictionListener.NOOP,
                                                                 storage,
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

        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            return delegate.get(id);
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            puts.add(id);

            return delegate.put(id, content);
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
