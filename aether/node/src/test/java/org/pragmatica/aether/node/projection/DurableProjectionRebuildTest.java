// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.projection;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.IntStream;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.endpoint.EndpointRegistry;
import org.pragmatica.aether.endpoint.TopicSubscriptionRegistry;
import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.node.stream.ClusterCursorStore;
import org.pragmatica.aether.node.stream.ConsumerAssignmentWriter;
import org.pragmatica.aether.node.stream.ConsumerAssignmentWriter.CommittedAssignments;
import org.pragmatica.aether.node.stream.StreamConsumerManager;
import org.pragmatica.aether.node.stream.StreamConsumerRegistry;
import org.pragmatica.aether.node.stream.TopicGroupDeclarationSource;
import org.pragmatica.aether.resource.projection.InMemoryProjectionClaims;
import org.pragmatica.aether.resource.projection.InMemoryProjectionStore;
import org.pragmatica.aether.resource.projection.Projection;
import org.pragmatica.aether.resource.projection.ProjectionStore;
import org.pragmatica.aether.resource.projection.ProjectionStore.ReplayStatus;
import org.pragmatica.aether.resource.projection.ProjectionStore.RewindToken;
import org.pragmatica.aether.slice.DefaultSliceBridge;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.aether.slice.stream.PublishOutcome;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.RewindEpoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConsumerAssignmentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamCursorCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.TopicSubscriptionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue.AssignmentToken;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamCursorCheckpointValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopicSubscriptionValue;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.topic.ContextualEvent;
import org.pragmatica.aether.slice.topic.Topic;
import org.pragmatica.aether.stream.DeadLetterHandler;
import org.pragmatica.aether.stream.DeadLetterHandler.DeadLetterEntry;
import org.pragmatica.aether.stream.StreamConsumerRuntime;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamReadRouter;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore;
import org.pragmatica.aether.stream.topic.DurableGroupIdentity;
import org.pragmatica.aether.stream.topic.DurableTopicPublisher;
import org.pragmatica.aether.stream.topic.TopicCodecsStream;
import org.pragmatica.aether.stream.topic.TopicEventEnvelope;
import org.pragmatica.aether.update.DeploymentManager;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.serialization.SliceCodec;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;


/// #1333 in-JVM twin of `DurableProjectionRebuildForgeTest` — the same scenario on the real
/// in-process runtime, so the mutation table can be run in seconds. Everything on the path is REAL:
/// the stream store, the consumer runtime with its retry and dead-letter handling, the real `KVStore`
/// applier (so the epoch fence is the production one), `ClusterCursorStore`, the projection commit hook,
/// the registry, `NodeReplayCursor`, `StreamConsumerManager` with its rewind restart, `SliceInvoker`,
/// `DefaultSliceBridge`, and the `Projection` facade over the in-memory store and claims. The network,
/// serializers and deployment manager are mocks; consensus is a single-thread executor applying commands
/// in order. #1271 (merged after the first round): the group's partition is assigned by a COMMITTED
/// record the manager's own writer puts on the first reconcile (this node is the leader); the consumer
/// attaches on the pass after it lands, every checkpoint carries the assignment token, and the rewind
/// record does too — so the twin runs both applier fences, not one.
///
/// Scenario (design §4): publish seq 1..6 → model 123456. Arm poison for seq 3. Rebuild → REBUILDING over
/// `[0, 5]`. The rewound consumer replays 1, 2 (admitted in order), dead-letters seq 3 after 5 attempts,
/// and its FORCED cursor commit (`3`, one past the dead-lettered offset 2) is reported stamped with the
/// rewind token, so the store skips offset 2 and admits 4, 5, 6 → model 12456, LIVE, exactly ONE dead
/// letter. Publish
/// seq 7 → 124567, still one dead letter.
///
/// Mutation table (each production hunk reverted → the named test goes red):
/// - hook reports nothing (`ProjectionAwareCursorStore.report` removed) — the RED-first shape: `rebuild_…`
///   times out REBUILDING with model `12`;
/// - forced commit removed (`completeDeadLetter` without `requestCheckpoint`): `rebuild_…` red on the
///   dead-letter count (2, the cadence gap);
/// - hook stamps the CURRENT committed epoch instead of the consumer's (X6): the detached consumer's final
///   flush at cursor 6 is honoured, the partition goes LIVE before replaying, `rebuild_…` red on the model;
/// - applier fence dropped (`StreamCursorCheckpointValue` no longer `EpochBearing`):
///   `zombieCheckpoint_…` red — the zombie's put replaces the rewound cursor;
/// - resume by offset only (`Cursor.later` on offset): `resume_…` red;
/// - rewind restart removed (`StreamConsumerManager.restartRewound`): `rebuild_…` red, REBUILDING forever.
class DurableProjectionRebuildTest {
    record AppEvent(int seq) {}

    private static final SliceCodec.TypeCodec<AppEvent> APP_EVENT_CODEC = new SliceCodec.TypeCodec<>(AppEvent.class,
                                                                                                     SliceCodec.deterministicTag(AppEvent.class.getName()),
                                                                                                     (codec, buf, value) -> codec.write(buf,
                                                                                                                                        value.seq()),
                                                                                                     (codec, buf) -> new AppEvent(codec.read(buf)));

    private static final Artifact ARTIFACT = Artifact.artifact("org.example:orders:1.0.0").unwrap();
    private static final MethodName ON_PROJECTION_EVENT = MethodName.methodName("onProjectionEvent").unwrap();
    private static final NodeId SELF = NodeId.nodeId("node-1").unwrap();
    private static final String TOPIC_NAME = "projection-events";
    private static final String TOPIC_ADDRESS = "org.example:" + TOPIC_NAME + ":1.0.0";
    private static final String TOPIC_STREAM = "topic:" + TOPIC_ADDRESS;
    private static final String GROUP = DurableGroupIdentity.groupId(ARTIFACT, ON_PROJECTION_EVENT);
    private static final Topic<AppEvent> TOPIC = Topic.of(TOPIC_NAME, AppEvent.class);
    private static final String MODEL_KEY = "model";
    private static final int PARTITION = 0;
    private static final ConsumerAssignmentKey ASSIGNMENT_KEY = ConsumerAssignmentKey.consumerAssignmentKey(TOPIC_STREAM, PARTITION, GROUP);

    private final SliceCodec nodeCodec = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(),
                                                               TopicCodecsStream.CODECS);

    private final SliceCodec sliceCodec = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(),
                                                                List.of(APP_EVENT_CODEC));

    private final AtomicInteger poisonSeq = new AtomicInteger(-1);
    private final AtomicInteger attempts = new AtomicInteger();
    private final InMemoryProjectionStore<Long> store = InMemoryProjectionStore.inMemoryProjectionStore();
    private final InMemoryClusterCursorStore localCursors = new InMemoryClusterCursorStore();
    private ExecutorService consensus;
    private KVStore<AetherKey, AetherValue> kv;
    private StreamPartitionManager partitions;
    private DeadLetterHandler deadLetters;
    private ConsumerCursorStore cursorStore;
    private ProjectionRegistry registry;
    private ProjectionNodeSupport support;
    private StreamConsumerRuntime runtime;
    private StreamConsumerManager manager;
    private Projection<Long, AppEvent> projection;

    @BeforeEach
    void setUp() throws InterruptedException {
        consensus = Executors.newSingleThreadExecutor();
        var router = MessageRouter.mutable();

        kv = new KVStore<>(router, stubSerializer(), stubDeserializer());
        partitions = StreamPartitionManager.streamPartitionManager();
        partitions.createStream(StreamConfig.streamConfig(TOPIC_STREAM,
                                                          1,
                                                          RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 600_000),
                                                          "earliest"))
                  .onFailure(cause -> fail(cause.message()));
        deadLetters = DeadLetterHandler.deadLetterHandler();
        var topics = TopicSubscriptionRegistry.topicSubscriptionRegistry();

        subscribe(topics);
        registry = ProjectionRegistry.projectionRegistry(topics::allSubscriptions);
        var cluster = ClusterCursorStore.clusterCursorStore(localCursors,
                                                            SELF,
                                                            this::committed,
                                                            this::committedAssignment,
                                                            this::applyAll,
                                                            () -> false);
        var hook = ProjectionAwareCursorStore.projectionAwareCursorStore(cluster, registry);

        cursorStore = hook;
        support = ProjectionNodeSupport.projectionNodeSupport(registry,
                                                              hook::reportFailureCount,
                                                              _ -> Option.some(1),
                                                              PartitionBounds.routed(StreamReadRouter.localOnly(partitions)),
                                                              this::applyAll,
                                                              this::committed,
                                                              this::committedAssignment);
        projection = support.attach(ARTIFACT.base(),
                                    TOPIC_STREAM,
                                    Option.none(),
                                    Projection.of(TOPIC)
                                              .into(store, _ -> MODEL_KEY)
                                              .apply(DurableProjectionRebuildTest::fold)
                                              .withClaims(InMemoryProjectionClaims.inMemoryProjectionClaims(),
                                                          TimeSpan.timeSpan(30).seconds()))
                            .unwrap();
        runtime = StreamConsumerRuntime.streamConsumerRuntime(partitions, deadLetters, cursorStore);
        manager = wireManager(topics);
        router.addRoute(ValuePut.class, (ValuePut<?, ?> put) -> manager.onCheckpointPut(put));
        // #1271: the first pass computes and PUTS the assignment (fire-and-forget through consensus) and
        // admits nothing; the consumer attaches on the pass after the record is committed.
        manager.reconcile();
        awaitAssignment(5_000);
        manager.reconcile();
    }

    private void awaitAssignment(long maxMs) throws InterruptedException {
        var deadline = System.currentTimeMillis() + maxMs;

        while (committedAssignment(TOPIC_STREAM, PARTITION, GROUP).isEmpty() && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }

        assertThat(committedAssignment(TOPIC_STREAM, PARTITION, GROUP).map(ConsumerAssignmentValue::assignee))
                  .as("the committed consumer assignment names this node")
                  .isEqualTo(Option.some(SELF));
    }

    private Option<ConsumerAssignmentValue> committedAssignment(String stream, int partition, String group) {
        return kv.getTyped(ConsumerAssignmentKey.consumerAssignmentKey(stream, partition, group), ConsumerAssignmentValue.class);
    }

    /// The committed assignment's token and epoch — what every checkpoint and rewind record must carry.
    private AssignmentToken selfToken() {
        return committedAssignment(TOPIC_STREAM, PARTITION, GROUP).map(ConsumerAssignmentValue::token).unwrap();
    }

    private Epoch assignmentEpoch() {
        return selfToken().epoch();
    }

    @AfterEach
    void tearDown() throws Exception {
        manager.stop();
        runtime.close();
        partitions.close();
        consensus.shutdownNow();
    }

    /// Order-sensitive fold: `state * 10 + seq`, so `123456` says every event applied exactly once, in order.
    private static Long fold(Option<Long> current, AppEvent event) {
        return current.or(0L) * 10 + event.seq();
    }

    @Test
    void rebuild_replaysInOrder_skipsTheDeadLetteredOffsetOnTheCommittedCursor_andGoesLive() throws InterruptedException {
        publishAll(1, 6);
        awaitModel(123456L, 15_000);
        assertThat(deadLettersForGroup()).as("control: nothing dead-lettered before the rebuild").isEmpty();
        poisonSeq.set(3);
        projection.rebuild().await().onFailure(cause -> fail("rebuild refused: " + cause.message()));
        var afterRebuild = replayStatus();

        assertThat(afterRebuild.generation()).isEqualTo(1L);
        assertThat(afterRebuild.rebuilding()).as("REBUILDING over [0, 5] on partition 0").containsOnlyKeys(PARTITION);
        assertThat(afterRebuild.rebuilding().get(PARTITION).throughOffset()).as("captured head = last visible offset")
                  .isEqualTo(5L);
        // The checkpoint-put trigger restarts the consumer inside the rewind's own apply, so by the time
        // rebuild() resolves (after the committed read-back) offsets 0 and 1 may already be replayed. The
        // poison offset 2 cannot have been passed: it needs the dead letter first.
        assertThat(afterRebuild.rebuilding().get(PARTITION).nextOffset()).as("replay started at 0 and has not passed the poison offset")
                  .isBetween(0L, 2L);
        var token = afterRebuild.currentRewind().unwrap();

        awaitLive(20_000);
        assertThat(model()).as("seq 3 dead-lettered and SKIPPED on the committed cursor; 4, 5, 6 applied in order")
                  .isEqualTo(Option.some(12456L));
        var entries = deadLettersForGroup();

        assertThat(entries).as("exactly ONE dead letter for the poison replay offset — a second one is the cadence gap the forced commit closes")
                  .hasSize(1);
        assertThat(entries.getFirst().offset()).isEqualTo(2L);
        assertThat(entries.getFirst().attemptCount()).isEqualTo(5);
        assertThat(committedEpoch()).as("the rewound consumer commits under the rewind token")
                  .isEqualTo(Option.some(NodeReplayCursor.epochOf(token)));
        // LIVE is observed on the forced commit at 3; the acks of 4, 5, 6 reach KV through the follow-up the
        // forced request scheduled (one interval after the commit at 3 persists). Budgeted generously: a
        // full-module run on a shared host missed 5s once (committed still 3 at 6.8s), and the property is
        // "lands without a further event", not "lands within N ms".
        awaitCommittedCursor(6L, 20_000);
        poisonSeq.set(-1);
        publishAll(7, 7);
        awaitModel(124567L, 15_000);
        assertThat(deadLettersForGroup()).as("live writes admitted after LIVE; no Rebuilding cascade").hasSize(1);
    }

    /// The fence, on the real applier: a zombie's checkpoint stamped with the pre-rewind epoch is refused, so
    /// the rewound cursor stands. Dropping `EpochBearing` from the checkpoint value turns this red.
    @Test
    void zombieCheckpoint_atTheOldEpoch_isRefusedByTheApplier() {
        var rewound = RewindEpoch.rewindEpoch(1L, 1L);

        applyNow(checkpoint(6L, RewindEpoch.NONE));
        assertThat(committedCursor()).as("control: the pre-rewind checkpoint landed").isEqualTo(Option.some(6L));
        applyNow(checkpoint(0L, rewound));
        assertThat(committedCursor()).as("the rewind lowered the cursor").isEqualTo(Option.some(0L));
        applyNow(checkpoint(6L, RewindEpoch.NONE));
        assertThat(committedCursor()).as("the zombie's put at the old epoch is REFUSED by the applier")
                  .isEqualTo(Option.some(0L));
        assertThat(committedEpoch()).isEqualTo(Option.some(rewound));
        applyNow(checkpoint(3L, rewound));
        assertThat(committedCursor()).as("the rewound consumer's own same-epoch checkpoint is accepted")
                  .isEqualTo(Option.some(3L));
    }

    /// Ruling (know 504ee397f): a pre-restart consumer's put under the old rewind epoch is refused AND that
    /// consumer is detached by `restartRewound` in the SAME apply — asserted on the runtime's delivery
    /// state right after the rewind's apply, not on the refused put alone. The rewind record's KV
    /// notification runs the manager's reconcile inside the apply (`onCheckpointPut` → `reconcile` →
    /// `restartRewound` → `abandon`), so when `rebuild()` resolves the subscription that ran under
    /// `RewindEpoch.NONE` is gone: the only subscription for the group either already runs under the
    /// minted epoch or is still fetching its cursor. Nothing else observable distinguishes the zombie from
    /// its successor — the consumer key is the same — so the epoch on the live snapshot IS the assertion.
    /// Its late put at the old epoch is then refused by the applier and leaves the committed epoch alone.
    @Test
    void rewind_detachesTheZombieInTheSameApply_andRefusesItsLateCheckpoint() throws InterruptedException {
        publishAll(1, 6);
        awaitModel(123456L, 15_000);
        // No committed-cursor control here: six events inside the first 500 ms on a partition that then
        // goes quiet leave NO checkpoint (#1385); the zombie's late put below is what lands one at NONE.
        var before = subscriptionsForGroup();

        assertThat(before).as("control: one live consumer, never rewound, cursor fetched").hasSize(1);
        assertThat(before.getFirst().rewindEpoch()).isEqualTo(RewindEpoch.NONE);
        assertThat(before.getFirst().awaitingCursorFetch()).isFalse();
        projection.rebuild().await().onFailure(cause -> fail("rebuild refused: " + cause.message()));
        var token = replayStatus().currentRewind().unwrap();
        var epoch = NodeReplayCursor.epochOf(token);
        var after = subscriptionsForGroup();

        assertThat(after).as("exactly one subscription for the group right after the rewind's apply: %s", after).hasSize(1);
        assertThat(after.getFirst().awaitingCursorFetch() || after.getFirst().rewindEpoch().equals(epoch))
                  .as("the NONE-epoch consumer is detached in the rewind's own apply; what remains is its successor (fetching, or running under %s): %s",
                      epoch,
                      after.getFirst())
                  .isTrue();
        assertThat(after.getFirst().rewindEpoch().equals(RewindEpoch.NONE) && !after.getFirst().awaitingCursorFetch())
                  .as("a consumer still running under NONE after the apply is the zombie: %s", after.getFirst())
                  .isFalse();
        applyNow(checkpoint(6L, RewindEpoch.NONE));
        assertThat(committedEpoch()).as("the zombie's late checkpoint at the old epoch is refused by the applier")
                  .isEqualTo(Option.some(epoch));
        awaitLive(20_000);
        assertThat(model()).isEqualTo(Option.some(123456L));
    }

    private List<StreamConsumerRuntime.SubscriptionSnapshot> subscriptionsForGroup() {
        return runtime.subscriptions()
                      .stream()
                      .filter(snapshot -> snapshot.streamName().equals(TOPIC_STREAM) && snapshot.partition() == PARTITION && snapshot.consumerGroup().equals(GROUP))
                      .toList();
    }

    /// rev1369 probe B: a FRESH store after a committed rewind (node restart, or the assignee moved). A
    /// process-local mint restarts at 1/1 — equal to the committed 1/1 — so the put is accepted at an equal
    /// epoch, the running consumer is never restarted (`restartRewound` needs strictly newer) and its next
    /// checkpoint drives the EMPTY rebuilt model LIVE. Minting from committed state gives a strictly newer
    /// token: the consumer restarts and the fresh store is rebuilt.
    @Test
    void freshStoreAfterACommittedRewind_mintsAStrictlyNewerEpoch_andRestartsTheConsumer() throws InterruptedException {
        publishAll(1, 6);
        awaitModel(123456L, 15_000);
        projection.rebuild().await().onFailure(cause -> fail("first rebuild refused: " + cause.message()));
        awaitLive(20_000);
        awaitModel(123456L, 15_000);
        awaitCommittedEpoch(RewindEpoch.rewindEpoch(1L, 1L), 20_000);
        // The assignee's slice restarts: the registration goes with it, and a NEW store (generation 0)
        // comes back. Whatever the store counts, the committed epoch is 1/1.
        registry.unregister(ARTIFACT.base(), TOPIC_STREAM);
        var freshStore = InMemoryProjectionStore.<Long> inMemoryProjectionStore();

        projection = support.attach(ARTIFACT.base(),
                                    TOPIC_STREAM,
                                    Option.none(),
                                    Projection.of(TOPIC)
                                              .into(freshStore, _ -> MODEL_KEY)
                                              .apply(DurableProjectionRebuildTest::fold)
                                              .withClaims(InMemoryProjectionClaims.inMemoryProjectionClaims(),
                                                          TimeSpan.timeSpan(30).seconds()))
                            .unwrap();
        assertThat(freshStore.read(MODEL_KEY).await().unwrap()).as("control: the fresh store is empty")
                  .isEqualTo(Option.none());
        projection.rebuild().await().onFailure(cause -> fail("second rebuild refused: " + cause.message()));
        var minted = freshStore.replayStatus().await().unwrap().currentRewind().unwrap();

        assertThat(NodeReplayCursor.epochOf(minted).isStrictlyAfter(RewindEpoch.rewindEpoch(1L, 1L))).as("minted strictly after the committed 1/1, from committed state: %s",
                                                                                                         minted)
                  .isTrue();
        awaitCommittedEpoch(NodeReplayCursor.epochOf(minted), 20_000);
        var deadline = System.currentTimeMillis() + 20_000;

        while (!freshStore.read(MODEL_KEY).await().unwrap().filter(value -> value == 123456L).isPresent() && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(50);
        }

        assertThat(freshStore.read(MODEL_KEY).await().unwrap()).as("the consumer restarted under the new epoch and rebuilt the FRESH store")
                  .isEqualTo(Option.some(123456L));
        assertThat(freshStore.replayStatus().await().unwrap().isLive()).isTrue();
    }

    /// rev1369 pin (2): an equal-epoch rewind record is REFUSED by the applier (it mints its epoch, so
    /// equal is stale), and `rebuild()` reports the refusal instead of Success. The race: another rebuild of
    /// the group committed the same next epoch between this one's mint and its put.
    @Test
    void equalEpochRewindPut_isRefusedByTheApplier_andRebuildReportsIt() throws InterruptedException {
        publishAll(1, 3);
        awaitModel(123L, 15_000);
        var racingCursor = new RacingCursor(projection.replayCursor());
        var racing = projection.withReplayCursor(racingCursor);
        var outcome = racing.rebuild().await();

        assertThat(outcome.isFailure()).as("the losing rebuild is refused, never Success: %s", outcome).isTrue();
        assertThat(outcome.fold(Cause::message, _ -> "")).contains("not committed");
        // The competitor's record restarts the consumer, whose same-epoch checkpoints are accepted — so
        // what is committed is the competitor's record or a checkpoint at ITS epoch, never OUR rewind
        // record (same epoch, `rewind = true`, a later timestamp).
        var committed = committed(StreamCursorCheckpointKey.streamCursorCheckpointKey(TOPIC_STREAM, PARTITION, GROUP)).unwrap();

        assertThat(committed.rewindEpoch()).isEqualTo(racingCursor.competitor.rewindEpoch());
        assertThat(committed.rewind() && committed.commitTimestamp() != racingCursor.competitor.commitTimestamp()).as("our equal-epoch rewind record never committed: %s",
                                                                                                                      committed)
                  .isFalse();
    }

    /// Between the mint and the put, a competing rebuild commits a rewind record under the SAME epoch.
    private final class RacingCursor implements Projection.ReplayCursor {
        private final StreamCursorCheckpointValue competitor = new StreamCursorCheckpointValue(0L, 1L, selfToken(), 1L, 1L, true);
        private final Projection.ReplayCursor real;

        private RacingCursor(Projection.ReplayCursor real) {
            this.real = real;
        }

        @Override
        public Promise<ProjectionStore.ReplayRange> capture() {
            return real.capture();
        }

        @Override
        public Promise<RewindToken> mintRewindToken(long generation) {
            return real.mintRewindToken(generation);
        }

        @Override
        public Promise<Unit> rewind(ProjectionStore.ReplayRange range, RewindToken token) {
            applyNow(new KVCommand.Put<AetherKey, AetherValue>(StreamCursorCheckpointKey.streamCursorCheckpointKey(TOPIC_STREAM,
                                                                                                                   PARTITION,
                                                                                                                   GROUP),
                                                               competitor));
            assertThat(NodeReplayCursor.epochOf(token)).as("control: both minted the same next epoch")
                      .isEqualTo(competitor.rewindEpoch());

            return real.rewind(range, token);
        }
    }

    /// rev1369 MEDIUM: after a CLEAN rebuild (no dead letter, no further event) the replay lands inside one
    /// cadence, so nothing would checkpoint the rebuilt head — the committed cursor would sit at the rewind
    /// offset. The rewound consumer's catch-up pass requests one.
    @Test
    void cleanRebuild_commitsTheRebuiltHead_withoutAFurtherEvent() throws InterruptedException {
        publishAll(1, 6);
        awaitModel(123456L, 15_000);
        projection.rebuild().await().onFailure(cause -> fail("rebuild refused: " + cause.message()));
        awaitLive(20_000);
        assertThat(model()).isEqualTo(Option.some(123456L));
        assertThat(deadLettersForGroup()).isEmpty();
        awaitCommittedCursor(6L, 5_000);
        assertThat(committedEpoch()).isEqualTo(Option.some(RewindEpoch.rewindEpoch(1L, 1L)));
    }

    /// Resume order is `(epoch, offset)`: a stale-high local cursor from before the rewind loses to the
    /// rewound cluster cursor, however low. `max(local, cluster)` on the offset turns this red.
    @Test
    void resume_prefersTheRewoundClusterCursor_overAStaleHighLocalOne() {
        var rewound = RewindEpoch.rewindEpoch(1L, 1L);

        localCursors.commit(GROUP, TOPIC_STREAM, PARTITION, 6L, assignmentEpoch(), RewindEpoch.NONE).await();
        applyNow(checkpoint(0L, rewound));
        var resumed = cursorStore.fetchCursor(GROUP, TOPIC_STREAM, PARTITION, assignmentEpoch()).await().unwrap();

        assertThat(resumed).isEqualTo(Option.some(ConsumerCursorStore.Cursor.cursor(0L, rewound)));
    }

    // ---- fixture -------------------------------------------------------------------------------
    private StreamConsumerManager wireManager(TopicSubscriptionRegistry topics) {
        var handler = InvocationHandler.invocationHandler(SELF, mock(ClusterNetwork.class));
        var bridge = DefaultSliceBridge.defaultSliceBridge(ARTIFACT, () -> List.of(subscriber()), sliceCodec);

        handler.registerSlice(ARTIFACT, bridge);
        var invoker = SliceInvoker.sliceInvoker(SELF,
                                                mock(ClusterNetwork.class),
                                                EndpointRegistry.endpointRegistry(),
                                                handler,
                                                mock(Serializer.class),
                                                mock(Deserializer.class),
                                                mock(DeploymentManager.class));
        StreamConsumerManager.SlicePlacement placement = _ -> Map.of(SELF, SliceState.ACTIVE);

        return StreamConsumerManager.streamConsumerManager(StreamConsumerRegistry.streamConsumerRegistry(),
                                                           runtime,
                                                           invoker,
                                                           handler,
                                                           nodeCodec,
                                                           new SelfOwnsEverything(),
                                                           placement,
                                                           SELF,
                                                           TopicGroupDeclarationSource.topicGroupDeclarationSource(topics,
                                                                                                                   _ -> true),
                                                           authority(),
                                                           (stream, partition, group) -> committed(StreamCursorCheckpointKey.streamCursorCheckpointKey(stream,
                                                                                                                                                       partition,
                                                                                                                                                       group)).map(StreamCursorCheckpointValue::rewindEpoch));
    }

    /// #1271: this node is the leader, so the manager's own writer commits the assignment through the
    /// real applier; every node's admission (here, this one's) reads the committed record.
    private StreamConsumerManager.AssignmentAuthority authority() {
        return StreamConsumerManager.AssignmentAuthority.assignmentAuthority(this::committedAssignment,
                                                                             ConsumerAssignmentWriter.consumerAssignmentWriter(() -> true,
                                                                                                                               () -> 1L,
                                                                                                                               HlcClock.hlcClock(SELF),
                                                                                                                               this::committedAssignment),
                                                                             this::applyAll);
    }

    /// The slice's durable subscriber: the generated-adapter shape (a [ContextualEvent] parameter) delegating
    /// to the ATTACHED projection. The poison arm refuses BEFORE the fold, like a fold that throws would.
    private SliceMethod<Unit, ContextualEvent> subscriber() {
        return new SliceMethod<>(ON_PROJECTION_EVENT,
                                 this::onProjectionEvent,
                                 new TypeToken<Unit>() {},
                                 new TypeToken<ContextualEvent>() {});
    }

    private Promise<Unit> onProjectionEvent(ContextualEvent contextual) {
        var event = (AppEvent) contextual.event();

        attempts.incrementAndGet();
        if (event.seq() == poisonSeq.get()) {
            return Causes.cause("poison: seq " + event.seq()).promise();
        }

        return projection.onEvent(event, contextual.context());
    }

    private static final class SelfOwnsEverything implements StreamConsumerManager.PartitionOwnership {
        @Override
        public Option<Integer> partitionCount(String streamName) {
            return Option.some(1);
        }

        @Override
        public Option<NodeId> ownerOf(String streamName, int partition) {
            return Option.some(SELF);
        }

        @Override
        public List<NodeId> liveMembers() {
            return List.of(SELF);
        }
    }

    private static void subscribe(TopicSubscriptionRegistry topics) {
        var key = TopicSubscriptionKey.topicSubscriptionKey(ResourceAddress.resourceAddress(TOPIC_ADDRESS).unwrap(),
                                                            ARTIFACT,
                                                            ON_PROJECTION_EVENT);

        topics.onSubscriptionPut(new ValuePut<>(new KVCommand.Put<>(key,
                                                                    TopicSubscriptionValue.topicSubscriptionValue(SELF)),
                                                Option.none()));
    }

    /// Consensus stand-in: commands are applied in order on one thread, asynchronously to the caller —
    /// a commit issued from inside a KV notification never re-enters the applier.
    private Promise<Unit> applyAll(List<KVCommand<AetherKey>> commands) {
        return Promise.promise(promise -> consensus.execute(() -> {
            kv.process(kv.createBatch(commands));
            promise.succeed(Unit.unit());
        }));
    }

    private Promise<Unit> apply(KVCommand<AetherKey> command) {
        return applyAll(List.of(command));
    }

    /// A checkpoint under this node's committed assignment token at `epoch`.
    private KVCommand<AetherKey> checkpoint(long offset, RewindEpoch epoch) {
        return ClusterCursorStore.checkpointCommand(GROUP, TOPIC_STREAM, PARTITION, offset, selfToken(), epoch);
    }

    private void applyNow(KVCommand<AetherKey> command) {
        apply(command).await().onFailure(cause -> fail(cause.message()));
    }

    private Option<StreamCursorCheckpointValue> committed(StreamCursorCheckpointKey key) {
        return kv.getTyped(key, StreamCursorCheckpointValue.class);
    }

    private Option<Long> committedCursor() {
        return committed(StreamCursorCheckpointKey.streamCursorCheckpointKey(TOPIC_STREAM, PARTITION, GROUP)).map(StreamCursorCheckpointValue::committedOffset);
    }

    private Option<RewindEpoch> committedEpoch() {
        return committed(StreamCursorCheckpointKey.streamCursorCheckpointKey(TOPIC_STREAM, PARTITION, GROUP)).map(StreamCursorCheckpointValue::rewindEpoch);
    }

    private void publishAll(int fromSeq, int toSeq) {
        for (var seq = fromSeq; seq <= toSeq; seq++) {
            publish(new AppEvent(seq));
        }
    }

    private void publish(AppEvent event) {
        var appended = new AtomicReference<Long>();

        new DurableTopicPublisher<AppEvent>(sliceCodec, singlePublisher(captured -> append(captured, appended))).publish(event)
                                                                                                                .await()
                                                                                                                .onFailure(cause -> fail(cause.message()));
    }

    /// #1342 made `publishBatch` abstract; this fixture publishes one envelope at a time, so the batch form
    /// answers each event as published and is never reached (same adapter as `DurableTopicContextDeliveryTest`).
    private static StreamPublisher<TopicEventEnvelope> singlePublisher(Function<TopicEventEnvelope, Promise<Unit>> publish) {
        return new StreamPublisher<>() {
            @Override
            public Promise<Unit> publish(TopicEventEnvelope event) {
                return publish.apply(event);
            }

            @Override
            public Promise<List<PublishOutcome>> publishBatch(List<TopicEventEnvelope> events) {
                return Promise.success(IntStream.range(0,
                                                       events.size())
                                                .<PublishOutcome> mapToObj(PublishOutcome.Published::new)
                                                .toList());
            }
        };
    }

    private Promise<Unit> append(TopicEventEnvelope envelope, AtomicReference<Long> offset) {
        return partitions.publishLocal(TOPIC_STREAM,
                                       PARTITION,
                                       nodeCodec.encode(envelope),
                                       System.currentTimeMillis())
                         .onSuccess(offset::set)
                         .async()
                         .mapToUnit();
    }

    private Option<Long> model() {
        return store.read(MODEL_KEY)
                    .await()
                    .unwrap();
    }

    private ReplayStatus replayStatus() {
        return store.replayStatus()
                    .await()
                    .unwrap();
    }

    private List<DeadLetterEntry> deadLettersForGroup() {
        return deadLetters.read(TOPIC_STREAM, 100)
                          .stream()
                          .filter(entry -> entry.failingGroup()
                                                .equals(GROUP))
                          .toList();
    }

    private void awaitModel(long expected, long maxMs) throws InterruptedException {
        var deadline = System.currentTimeMillis() + maxMs;

        while (!model().filter(value -> value == expected).isPresent() && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(50);
        }

        assertThat(model()).as("model within " + maxMs + "ms (attempts so far: " + attempts.get() + ")")
                  .isEqualTo(Option.some(expected));
    }

    private void awaitCommittedCursor(long expected, long maxMs) throws InterruptedException {
        var deadline = System.currentTimeMillis() + maxMs;

        while (!committedCursor().filter(value -> value == expected).isPresent() && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(50);
        }

        assertThat(committedCursor()).as("committed cursor within " + maxMs + "ms").isEqualTo(Option.some(expected));
    }

    private void awaitCommittedEpoch(RewindEpoch expected, long maxMs) throws InterruptedException {
        var deadline = System.currentTimeMillis() + maxMs;

        while (!committedEpoch().filter(expected::equals).isPresent() && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(50);
        }

        assertThat(committedEpoch()).as("committed epoch within " + maxMs + "ms").isEqualTo(Option.some(expected));
    }

    private void awaitLive(long maxMs) throws InterruptedException {
        var deadline = System.currentTimeMillis() + maxMs;

        while (!replayStatus().isLive() && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(50);
        }

        assertThat(replayStatus().isLive()).as("LIVE within " + maxMs
                                              + "ms; status " + replayStatus()
                                              + ", model " + model()
                                              + ", dead letters " + deadLettersForGroup().size())
                  .isTrue();
    }

    /// The node-local half of the cluster store, in memory: a cursor per key carrying the assignment epoch
    /// it was written under and its rewind epoch, like the 40-byte disk block; a fenced fetch answers only
    /// for the tenure it was written under (#1271).
    private static final class InMemoryClusterCursorStore implements ConsumerCursorStore {
        private record Stored(Cursor cursor, Option<Epoch> assignmentEpoch) {}

        private final Map<String, Stored> cursors = new ConcurrentHashMap<>();

        @Override
        public Promise<CommitOutcome> commit(String consumerGroup, String streamName, int partition, long offset) {
            cursors.put(consumerGroup + "/" + streamName + "/" + partition, new Stored(Cursor.unrewound(offset), Option.none()));

            return Promise.success(CommitOutcome.persisted());
        }

        @Override
        public Promise<CommitOutcome> commit(String consumerGroup,
                                             String streamName,
                                             int partition,
                                             long offset,
                                             Epoch assignmentEpoch,
                                             RewindEpoch rewindEpoch) {
            cursors.put(consumerGroup + "/" + streamName + "/" + partition,
                        new Stored(Cursor.cursor(offset, rewindEpoch), Option.some(assignmentEpoch)));

            return Promise.success(CommitOutcome.persisted());
        }

        @Override
        public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition) {
            return Promise.success(Option.option(cursors.get(consumerGroup + "/" + streamName + "/" + partition))
                                         .map(stored -> stored.cursor().offset()));
        }

        @Override
        public Promise<Option<Cursor>> fetchCursor(String consumerGroup, String streamName, int partition, Epoch assignmentEpoch) {
            return Promise.success(Option.option(cursors.get(consumerGroup + "/" + streamName + "/" + partition))
                                         .filter(stored -> stored.assignmentEpoch().filter(assignmentEpoch::equals).isPresent())
                                         .map(Stored::cursor));
        }
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
