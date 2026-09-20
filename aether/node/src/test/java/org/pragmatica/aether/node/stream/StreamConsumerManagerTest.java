// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.stream;

import org.pragmatica.aether.slice.stream.PublishOutcome;
import org.pragmatica.aether.slice.StreamPublisher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.node.stream.StreamConsumerManager.PartitionAssignment;
import org.pragmatica.aether.node.stream.StreamConsumerManager.AssignmentAuthority;
import org.pragmatica.aether.node.stream.StreamConsumerManager.PartitionOwnership;
import org.pragmatica.aether.node.stream.StreamConsumerManager.SlicePlacement;
import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.ObservabilityStrategyCell;
import org.pragmatica.aether.slice.DefaultSliceBridge;
import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConsumerAssignmentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamRegistrationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamRegistrationValue;
import org.pragmatica.aether.slice.topic.ContextualEvent;
import org.pragmatica.aether.slice.topic.MessageContext;
import org.pragmatica.aether.stream.ConsumerFence;
import org.pragmatica.aether.stream.DeadLetterHandler;
import org.pragmatica.aether.stream.StreamConsumerRuntime;
import org.pragmatica.aether.stream.consumer.TransactionalCursorCommit;
import org.pragmatica.aether.stream.topic.DurableGroupIdentity;
import org.pragmatica.aether.stream.topic.DurableTopicPublisher;
import org.pragmatica.aether.stream.topic.TopicEventEnvelope;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


/// Partition ASSIGNMENT for declarative `[streams.X]` consumers (#488 gating, #535 placement repair).
///
/// The decision table under test: exactly one node consumes `(S, P)` — the HRW owner when the slice is
/// ACTIVE there, otherwise the HRW pick over the nodes where it IS active. The #535 defect was that the
/// first half was the WHOLE rule, so a default deployment (slice on a subset that excludes the owner)
/// delivered nothing at all.
///
/// The node codec here is the REAL [FrameworkCodecs], so the `eventTypePublishable` assertions exercise
/// the actual condition rather than a mocked stand-in: `java.lang.String` is registered in it, [AppEvent]
/// is not — and after #526 the probe consults the SLICE's codec, where it can be.
class StreamConsumerManagerTest {
    /// A real, loadable application-defined event type. Real matters: the publishability probe does
    /// `Class.forName` first, so a fictional name would report "unpublishable" for the wrong reason
    /// and the codec half of the check would never run.
    record AppEvent(String id) {}

    private static final String APP_EVENT_TYPE = AppEvent.class.getName();

    private static final SliceCodec.TypeCodec<AppEvent> APP_EVENT_CODEC = new SliceCodec.TypeCodec<>(AppEvent.class,
                                                                                                     SliceCodec.deterministicTag(APP_EVENT_TYPE),
                                                                                                     (codec, buf, value) -> codec.write(buf,
                                                                                                                                        value.id()),
                                                                                                     (codec, buf) -> new AppEvent(codec.read(buf)));

    private static final Artifact ARTIFACT = Artifact.artifact("org.example:orders:1.0.0").unwrap();
    private static final MethodName METHOD = MethodName.methodName("onOrderEvent").unwrap();
    private static final NodeId SELF = NodeId.nodeId("node-1").unwrap();
    private static final NodeId PEER = NodeId.nodeId("node-2").unwrap();
    private static final NodeId THIRD = NodeId.nodeId("node-3").unwrap();
    /// A live member that never hosts the slice — the node that can OWN a partition without being able
    /// to consume it. That combination is the whole of #535, and no test of the repair can express it
    /// without a node outside the placement set.
    private static final NodeId OUTSIDER = NodeId.nodeId("node-4").unwrap();
    /// The nodes that host the slice under [#deploySliceEverywhere] — everything except [#OUTSIDER].
    private static final List<NodeId> CANDIDATES = List.of(SELF, PEER, THIRD);
    private static final List<NodeId> CLUSTER = List.of(SELF, PEER, THIRD, OUTSIDER);
    private static final String STREAM = "orders";
    private static final String CONFIG_SECTION = "streams.orders";
    private static final String GROUP = "orders-onOrderEvent";
    private static final int PARTITION_COUNT = 4;
    /// Wide enough that HRW concentrating every partition on one of three candidates has probability
    /// 3 x (1/3)^32 — the point at which a distribution assertion stops being a coin flip.
    private static final int WIDE_PARTITION_COUNT = 32;
    private static final Epoch EPOCH_1 = Epoch.epoch(1L, 1L);
    private static final Epoch EPOCH_2 = Epoch.epoch(1L, 2L);
    private static final Epoch EPOCH_3 = Epoch.epoch(1L, 3L);

    private StreamConsumerRegistry registry;
    private RecordingRuntime runtime;
    private InvocationHandler invocationHandler;
    private SliceInvoker invoker;
    private MutableOwnership ownership;
    private MutablePlacement placement;
    /// #1271: the committed consumer assignments every manager in a test reads — the stand-in for the
    /// consensus KV. Writes land synchronously, so a leader's reconcile commits its computed assignment
    /// and admits itself in the same pass, exactly the steady state the pre-#1271 tests describe.
    private Map<ConsumerAssignmentKey, ConsumerAssignmentValue> committedAssignments;

    @BeforeEach
    void setUp() {
        committedAssignments = new ConcurrentHashMap<>();
        registry = StreamConsumerRegistry.streamConsumerRegistry();
        runtime = new RecordingRuntime();
        invocationHandler = mock(InvocationHandler.class);
        invoker = mock(SliceInvoker.class);
        ownership = new MutableOwnership();
        placement = new MutablePlacement();
        when(invocationHandler.localSlice(any())).thenReturn(Option.none());
    }

    private StreamConsumerManager manager() {
        return managerFor(SELF, runtime);
    }

    private StreamConsumerManager managerFor(NodeId self, StreamConsumerRuntime consumerRuntime) {
        return managerFor(self, consumerRuntime, ownership);
    }

    private StreamConsumerManager managerFor(NodeId self,
                                             StreamConsumerRuntime consumerRuntime,
                                             PartitionOwnership nodeOwnership) {
        return managerFor(self, consumerRuntime, nodeOwnership, true);
    }

    private StreamConsumerManager managerFor(NodeId self,
                                             StreamConsumerRuntime consumerRuntime,
                                             PartitionOwnership nodeOwnership,
                                             boolean leader) {
        return StreamConsumerManager.streamConsumerManager(registry,
                                                           consumerRuntime,
                                                           invoker,
                                                           invocationHandler,
                                                           FrameworkCodecs.frameworkCodecs(),
                                                           nodeOwnership,
                                                           placement,
                                                           self,
                                                           authority(leader));
    }

    /// The committed-assignment authority over [#committedAssignments]; `leader` gates its writer the
    /// way the node's leadership does.
    private AssignmentAuthority authority(boolean leader) {
        ConsumerAssignmentWriter.CommittedAssignments committed = (stream, partition, group) -> Option.option(committedAssignments.get(ConsumerAssignmentKey.consumerAssignmentKey(stream,
                                                                                                                                                                           partition,
                                                                                                                                                                           group)));

        return AssignmentAuthority.assignmentAuthority(committed,
                                                       ConsumerAssignmentWriter.consumerAssignmentWriter(() -> leader,
                                                                                                         () -> 1L,
                                                                                                         HlcClock.hlcClock(SELF),
                                                                                                         committed),
                                                       this::applyAssignments);
    }

    private Promise<Unit> applyAssignments(List<KVCommand<AetherKey>> commands) {
        commands.forEach(this::applyAssignment);

        return Promise.unitPromise();
    }

    private void applyAssignment(KVCommand<AetherKey> command) {
        if (command instanceof KVCommand.Put<?, ?> put && put.key() instanceof ConsumerAssignmentKey key && put.value() instanceof ConsumerAssignmentValue value) {
            committedAssignments.put(key, value);
        }
    }

    /// Commit an assignment directly — a leader elsewhere reassigning the partition.
    private void commitAssignment(int partition, NodeId assignee, Epoch epoch) {
        committedAssignments.put(ConsumerAssignmentKey.consumerAssignmentKey(STREAM, partition, GROUP), assignmentRecord(assignee, epoch));
    }

    private static ConsumerAssignmentValue assignmentRecord(NodeId assignee, Epoch epoch) {
        return ConsumerAssignmentValue.consumerAssignmentValue(assignee, epoch, epoch.localCounter(), HlcTimestamp.ZERO);
    }

    /// The same seam for the committed-assignment reader: a record that moves BETWEEN two reads of one
    /// pass. Follower writer, so the pass itself commits nothing.
    private StreamConsumerManager managerReading(ConsumerAssignmentWriter.CommittedAssignments committed) {
        return StreamConsumerManager.streamConsumerManager(registry,
                                                           runtime,
                                                           invoker,
                                                           invocationHandler,
                                                           FrameworkCodecs.frameworkCodecs(),
                                                           ownership,
                                                           placement,
                                                           SELF,
                                                           AssignmentAuthority.assignmentAuthority(committed,
                                                                                                   ConsumerAssignmentWriter.consumerAssignmentWriter(() -> false,
                                                                                                                                                     () -> 1L,
                                                                                                                                                     HlcClock.hlcClock(SELF),
                                                                                                                                                     committed),
                                                                                                   this::applyAssignments));
    }

    /// A seam for a registry whose answer changes BETWEEN reads — a KV notification landing while a
    /// pass is in flight. A real registry answers every read from its current state, so only a mock can
    /// script the change deterministically.
    private StreamConsumerManager managerWithRegistry(StreamConsumerRegistry customRegistry) {
        return StreamConsumerManager.streamConsumerManager(customRegistry,
                                                           runtime,
                                                           invoker,
                                                           invocationHandler,
                                                           FrameworkCodecs.frameworkCodecs(),
                                                           ownership,
                                                           placement,
                                                           SELF,
                                                           authority(true));
    }

    private void declare(String eventType, boolean batchMode) {
        var key = StreamRegistrationKey.streamRegistrationKey(STREAM, CONFIG_SECTION, ARTIFACT, METHOD);
        var value = StreamRegistrationValue.streamRegistrationValue(SELF, GROUP, batchMode, eventType);

        registry.onStreamRegistrationPut(new ValuePut<>(new KVCommand.Put<>(key, value), Option.none()));
    }

    private void declareStringConsumer() {
        declare("java.lang.String", false);
    }

    private void undeclare() {
        var key = StreamRegistrationKey.streamRegistrationKey(STREAM, CONFIG_SECTION, ARTIFACT, METHOD);

        registry.onStreamRegistrationRemove(new ValueRemove<>(new KVCommand.Remove<>(key), Option.none()));
    }

    /// The slice is loadable here AND reported ACTIVE here cluster-wide — both halves are needed, since
    /// the local bridge is what invokes and the placement view is what makes this node a candidate.
    private void deploySliceLocally() {
        placement.activeOn(SELF);
        when(invocationHandler.localSlice(ARTIFACT)).thenReturn(Option.some(new StubBridge(Option.none())));
    }

    /// Deploy a slice whose OWN codec knows [AppEvent] — what a real deployed slice looks like once
    /// its resources are provisioned with the slice codec rather than the node codec (#526).
    private void deploySliceLocallyWithAppCodec() {
        var sliceCodec = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), List.of(APP_EVENT_CODEC));

        placement.activeOn(SELF);
        when(invocationHandler.localSlice(ARTIFACT)).thenReturn(Option.some(new StubBridge(Option.some(sliceCodec))));
    }

    private void deploySliceEverywhere() {
        placement.activeOn(SELF, PEER, THIRD);
        when(invocationHandler.localSlice(ARTIFACT)).thenReturn(Option.some(new StubBridge(Option.none())));
    }

    @Nested
    class Assignment {
        @Test
        void reconcile_subscribesOwnedPartitionsOnly_whenSliceActiveOnEveryOwner() {
            declareStringConsumer();
            deploySliceEverywhere();
            ownership.ownedBySelf(0, 2);
            ownership.ownedBy(PEER, 1, 3);
            manager().reconcile();
            assertThat(runtime.subscribedPartitions()).describedAs("the owner can run the slice, so the owner consumes — the #488 case, unchanged")
                      .containsExactlyInAnyOrder(0, 2);
        }

        @Test
        void reconcile_subscribesNothing_whenAnotherOwnerHostsTheSlice() {
            declareStringConsumer();
            deploySliceEverywhere();
            ownership.ownedBy(PEER, 0, 1, 2, 3);
            manager().reconcile();
            assertThat(runtime.subscribedPartitions()).describedAs("every partition's owner can run the slice itself — nothing for this node to do")
                      .isEmpty();
        }

        /// The #535 defect, inverted into a guarantee: at default placement the owner usually does NOT
        /// host the slice, and before the fix that meant ZERO delivery cluster-wide.
        @Test
        void reconcile_subscribesPartitionsOwnedElsewhere_whenSliceActiveOnlyHere() {
            declareStringConsumer();
            deploySliceLocally();
            ownership.ownedBy(PEER, 0, 1, 2, 3);
            manager().reconcile();
            assertThat(runtime.subscribedPartitions()).describedAs("no owner can run the slice, so the only node that can consumes them all, reading remotely")
                      .containsExactlyInAnyOrder(0, 1, 2, 3);
        }

        @Test
        void reconcile_subscribesNothing_whenSliceNotDeployedLocally() {
            declareStringConsumer();
            placement.activeOn(PEER);
            ownership.ownedBySelf(0, 1, 2, 3);
            manager().reconcile();
            assertThat(runtime.subscribedPartitions()).describedAs("owning a partition is not enough — the slice must be here to invoke")
                      .isEmpty();
        }

        @Test
        void reconcile_subscribesNothing_whenStreamUnknownLocally() {
            declareStringConsumer();
            deploySliceLocally();
            ownership.forgetStream();
            manager().reconcile();
            assertThat(runtime.subscribedPartitions()).describedAs("a stream whose config has not reached this node yet is a transient state, not an error")
                      .isEmpty();
        }

        @Test
        void reconcile_subscribesNothing_whenMembershipUnresolved() {
            declareStringConsumer();
            deploySliceLocally();
            ownership.forgetMembership();
            manager().reconcile();
            assertThat(runtime.subscribedPartitions()).describedAs("assigning against an unresolved member view would claim partitions on guesswork")
                      .isEmpty();
        }

        @Test
        void reconcile_unsubscribes_whenOwnershipIsLost() {
            declareStringConsumer();
            deploySliceEverywhere();
            ownership.ownedBySelf(0, 1);
            ownership.ownedBy(PEER, 2, 3);
            var manager = manager();

            manager.reconcile();
            assertThat(runtime.subscribedPartitions()).containsExactlyInAnyOrder(0, 1);
            ownership.ownedBySelf(1);
            ownership.ownedBy(PEER, 0, 2, 3);
            manager.reconcile();
            assertThat(runtime.subscribedPartitions()).describedAs("losing ownership of partition 0 detaches only that partition")
                      .containsExactly(1);
        }

        /// A placement change moves the consumer even though ownership did not: the node that was the
        /// only candidate stops being one, and the assignment must follow.
        @Test
        void reconcile_unsubscribes_whenSliceBecomesActiveOnTheOwnerInstead() {
            declareStringConsumer();
            deploySliceLocally();
            ownership.ownedBy(PEER, 0, 1, 2, 3);
            var manager = manager();

            manager.reconcile();
            assertThat(runtime.subscribedPartitions()).containsExactlyInAnyOrder(0, 1, 2, 3);
            placement.activeOn(SELF, PEER);
            manager.reconcile();
            assertThat(runtime.subscribedPartitions()).describedAs("once the owner can run the slice it takes its own partitions back")
                      .isEmpty();
        }

        @Test
        void reconcile_unsubscribesEverything_whenDeclarationIsRemoved() {
            declareStringConsumer();
            deploySliceEverywhere();
            ownership.ownedBySelf(0, 1);
            var manager = manager();

            manager.reconcile();
            undeclare();
            manager.reconcile();
            assertThat(runtime.subscribedPartitions()).isEmpty();
        }

        @Test
        void reconcile_addsNoDuplicateSubscription_whenCalledRepeatedly() {
            declareStringConsumer();
            deploySliceEverywhere();
            ownership.ownedBySelf(0, 1);
            ownership.ownedBy(PEER, 2, 3);
            var manager = manager();

            manager.reconcile();
            manager.reconcile();
            manager.reconcile();
            assertThat(runtime.subscribeCalls).describedAs("reconcile is idempotent — a re-tick must not re-subscribe")
                      .isEqualTo(2);
            assertThat(manager.activeSubscriptionCount()).isEqualTo(2);
        }

        /// The non-duplication INVARIANT, checked the only way that means anything: run the SAME inputs
        /// through every node's own manager and assert the partitions they claim form a partition of
        /// the stream — each consumed exactly once, none dropped, every assignee a real candidate. This
        /// is what makes it safe to drop the owner-gate that previously kept delivery single.
        ///
        /// Deliberately NO distribution claim here. "HRW spreads the load" is statistical, and with a
        /// small candidate set all-on-one has real probability — asserting it alongside the invariant
        /// would make a hard correctness test fail for a soft reason. The spread property has its own
        /// test below, over a space where concentration is negligible.
        @Test
        void reconcile_assignsEveryPartitionToExactlyOneNode_whenNoOwnerHostsTheSlice() {
            declareStringConsumer();
            deploySliceEverywhere();
            ownership.ownedBy(OUTSIDER, 0, 1, 2, 3);
            var claimed = new ArrayList<Integer>();

            for (var node : CANDIDATES) {
                var nodeRuntime = new RecordingRuntime();

                managerFor(node, nodeRuntime).reconcile();
                claimed.addAll(nodeRuntime.subscribedPartitions());
            }

            assertThat(claimed).describedAs("every partition consumed exactly once cluster-wide — no gap, no duplicate")
                      .containsExactlyInAnyOrder(0, 1, 2, 3);
        }

        /// Every assignee must be a node that can actually run the slice. An assignment naming a
        /// non-candidate would consume nothing while the endpoint reported it as assigned — the exact
        /// silent-wrong-state shape #535 is about.
        @Test
        void reconcile_assignsOnlyToCandidateNodes_whenOwnersCannotRunTheSlice() {
            declareStringConsumer();
            deploySliceEverywhere();
            ownership.ownedBy(OUTSIDER, 0, 1, 2, 3);
            var manager = manager();

            manager.reconcile();
            assertThat(manager.statuses()).singleElement()
                      .satisfies(status -> assertThat(status.partitionAssignments()).allSatisfy(assignment -> assertThat(assignment.consumerNode()).describedAs("partition %s assigned outside the candidate set",
                                                                                                                                                                assignment.partition())
                                                                                                                        .isIn(CANDIDATES.stream()
                                                                                                                                        .map(Option::some)
                                                                                                                                        .toList())));
        }

        /// The distribution property, asserted where it is not a coin flip: over 32 partitions and 3
        /// candidates, concentration on a single node has probability 3 x (1/3)^32 — indistinguishable
        /// from zero. At the 4 partitions the invariant test uses it would be ~3.7%, which is exactly
        /// the kind of assertion that fails in CI once a quarter and gets blamed on the wrong change.
        @Test
        void reconcile_spreadsPartitionsAcrossCandidates_overALargePartitionSpace() {
            declareStringConsumer();
            deploySliceEverywhere();
            ownership.withPartitionCount(WIDE_PARTITION_COUNT);
            ownership.ownedBy(OUTSIDER, allPartitions(WIDE_PARTITION_COUNT));
            var busyNodes = 0;

            for (var node : CANDIDATES) {
                var nodeRuntime = new RecordingRuntime();

                managerFor(node, nodeRuntime).reconcile();
                busyNodes += nodeRuntime.subscribedPartitions().isEmpty()
                             ? 0
                             : 1;
            }

            assertThat(busyNodes).describedAs("HRW must not pile every partition on one node").isGreaterThan(1);
        }

        private static int[] allPartitions(int count) {
            return IntStream.range(0, count).toArray();
        }

        /// Owner-preference is what keeps the live-validated co-located case bit-identical: when the
        /// owner CAN run the slice, the HRW pick over candidates must not steal the partition from it.
        @Test
        void reconcile_prefersTheOwner_whenTheOwnerIsAlsoACandidate() {
            declareStringConsumer();
            deploySliceEverywhere();
            ownership.ownedBySelf(0, 1, 2, 3);
            manager().reconcile();
            assertThat(runtime.subscribedPartitions()).describedAs("the owner reads locally and pushes — never hand its partitions to a remote reader")
                      .containsExactlyInAnyOrder(0, 1, 2, 3);
        }
    }

    @Nested
    class LoudFailures {
        @Test
        void statuses_reportUnassignedPartitions_whenSliceIsActiveNowhere() {
            declareStringConsumer();
            ownership.ownedBySelf(1, 3);
            var manager = manager();

            manager.reconcile();
            assertThat(manager.statuses()).singleElement()
                      .satisfies(status -> {
                                     assertThat(status.sliceDeployedLocally()).isFalse();
                                     assertThat(status.unassignedPartitions()).describedAs("the gap must be named, not silent: nothing can run this handler")
                                               .containsExactlyInAnyOrder(0, 1, 2, 3);
                                     assertThat(status.diagnostic().or("")).contains("ACTIVE on no live node")
                                               .contains("NOT being consumed");
                                 });
        }

        @Test
        void statuses_reportNoGap_whenAnotherNodeConsumesThePartitions() {
            declareStringConsumer();
            placement.activeOn(PEER);
            ownership.ownedBySelf(0, 1, 2, 3);
            var manager = manager();

            manager.reconcile();
            assertThat(manager.statuses()).singleElement()
                      .satisfies(status -> {
                                     assertThat(status.unassignedPartitions()).describedAs("owning a partition another node consumes is not a gap — since #535 the owner need not host the slice")
                                               .isEmpty();
                                     assertThat(status.diagnostic()).isEqualTo(Option.none());
                                 });
        }

        /// The endpoint must name WHO consumes each partition, so an operator can answer "who has
        /// partition 3?" from any node without correlating five responses.
        @Test
        void statuses_reportPartitionAssignments_namingConsumerAndOwner() {
            declareStringConsumer();
            deploySliceLocally();
            ownership.ownedBy(PEER, 0, 1, 2, 3);
            var manager = manager();

            manager.reconcile();
            assertThat(manager.statuses()).singleElement()
                      .satisfies(status -> assertThat(status.partitionAssignments()).describedAs("consumer and owner are both named, and they differ — so reads are forwarded")
                                                     .containsExactly(new PartitionAssignment(0,
                                                                                              Option.some(SELF),
                                                                                              Option.some(PEER)),
                                                                      new PartitionAssignment(1,
                                                                                              Option.some(SELF),
                                                                                              Option.some(PEER)),
                                                                      new PartitionAssignment(2,
                                                                                              Option.some(SELF),
                                                                                              Option.some(PEER)),
                                                                      new PartitionAssignment(3,
                                                                                              Option.some(SELF),
                                                                                              Option.some(PEER))));
        }

        /// Routine forwarding is normal operation, not a fault, so it must stay OUT of `diagnostic` —
        /// otherwise "diagnostic is non-empty" stops meaning "something is wrong". It is visible where
        /// an operator actually looks for it: `consumerNode` differing from `ownerNode`.
        @Test
        void statuses_reportForwardingStructurally_andLeaveTheFaultChannelEmpty() {
            declareStringConsumer();
            deploySliceLocally();
            ownership.ownedBy(PEER, 0, 1, 2, 3);
            var manager = manager();

            manager.reconcile();
            assertThat(manager.statuses()).singleElement()
                      .satisfies(status -> {
                                     assertThat(status.diagnostic()).describedAs("forwarding is not a fault — the fault channel must stay clean")
                                               .isEqualTo(Option.none());
                                     assertThat(status.partitionAssignments()).describedAs("and it must still be visible: consumer differs from owner on every forwarded partition")
                                               .allSatisfy(assignment -> {
                                                               assertThat(assignment.consumerNode()).isEqualTo(Option.some(SELF));
                                                               assertThat(assignment.ownerNode()).isEqualTo(Option.some(PEER));
                                                           });
                                 });
        }

        /// A deployment still activating produces the SAME empty candidate set as a slice that is
        /// nowhere. Only the second is an operator's problem, so the two must not read alike — the
        /// forge run showed the undifferentiated version putting a GAP error in the log of every
        /// successful deploy.
        @Test
        void statuses_reportActivating_ratherThanAGap_whenTheSliceIsStillDeploying() {
            declareStringConsumer();
            placement.activatingOn(SELF, PEER);
            var manager = manager();

            manager.reconcile();
            assertThat(manager.statuses()).singleElement()
                      .satisfies(status -> {
                                     assertThat(status.unassignedPartitions()).describedAs("nothing consumes them yet — that part is still true")
                                               .containsExactlyInAnyOrder(0, 1, 2, 3);
                                     assertThat(status.diagnostic().or("")).describedAs("a deploy in flight must not read like a slice that is nowhere")
                                               .contains("not being consumed YET")
                                               .doesNotContain("NOT being consumed by anyone");
                                 });
        }

        /// An owner that has not resolved yet is the bootstrap window, not a forwarding condition.
        /// Claiming "reads are forwarded to the owner" before anything knows who the owner is would put
        /// a statement on the operator surface that nothing can back.
        @Test
        void statuses_reportNoForwarding_whenTheOwnerIsNotResolvedYet() {
            declareStringConsumer();
            deploySliceLocally();
            var manager = manager();

            manager.reconcile();
            assertThat(manager.statuses()).singleElement()
                      .satisfies(status -> {
                                     assertThat(status.diagnostic()).describedAs("no owner is known, so nothing may be claimed about where reads go")
                                               .isEqualTo(Option.none());
                                     assertThat(status.partitionAssignments()).describedAs("this node is still assigned the work — an unknown owner does not block consumption")
                                               .allSatisfy(assignment -> {
                                                               assertThat(assignment.consumerNode()).isEqualTo(Option.some(SELF));
                                                               assertThat(assignment.ownerNode()).isEqualTo(Option.none());
                                                           });
                                 });
        }

        @Test
        void statuses_reportEventTypeUnpublishable_whenNoCodecKnowsTheAppType() {
            declare(APP_EVENT_TYPE, false);
            deploySliceLocally();
            ownership.ownedBySelf(0);
            var manager = manager();

            manager.reconcile();
            assertThat(manager.statuses()).singleElement()
                      .satisfies(status -> {
                                     assertThat(status.eventTypePublishable()).describedAs("no codec anywhere knows this type, so it cannot be published at all")
                                               .isEqualTo(Option.some(false));
                                     assertThat(status.diagnostic().or("")).describedAs("the operator must learn the real reason, naming the type")
                                               .contains(APP_EVENT_TYPE)
                                               .contains("cannot be PUBLISHED");
                                 });
        }

        /// The #526 payoff on the operator surface: once a slice's resources are provisioned with the
        /// SLICE's codec, an application-defined event type is genuinely publishable — and the
        /// diagnostic must stop crying wolf about it.
        @Test
        void statuses_reportEventTypePublishable_whenSliceCodecKnowsTheAppType() {
            declare(APP_EVENT_TYPE, false);
            deploySliceLocallyWithAppCodec();
            ownership.ownedBySelf(0);
            var manager = manager();

            manager.reconcile();
            assertThat(manager.statuses()).singleElement()
                      .satisfies(status -> {
                                     assertThat(status.eventTypePublishable()).describedAs("the slice's own codec registers this type, so publishing works")
                                               .isEqualTo(Option.some(true));
                                     assertThat(status.diagnostic()).isEqualTo(Option.none());
                                 });
        }

        @Test
        void statuses_reportEventTypePublishable_forFrameworkType() {
            declareStringConsumer();
            deploySliceLocally();
            ownership.ownedBySelf(0);
            var manager = manager();

            manager.reconcile();
            assertThat(manager.statuses()).singleElement()
                      .satisfies(status -> {
                                     assertThat(status.eventTypePublishable()).isEqualTo(Option.some(true));
                                     assertThat(status.diagnostic()).isEqualTo(Option.none());
                                 });
        }

        /// A node without the slice has no slice codec to probe, so it cannot know whether the type is
        /// publishable. Reporting `false` there would be a fabricated value — the honest answer is that
        /// there is no answer from here.
        @Test
        void statuses_reportPublishabilityUnknown_whenSliceIsNotLocal() {
            declare(APP_EVENT_TYPE, false);
            placement.activeOn(PEER);
            ownership.ownedBySelf(0);
            var manager = manager();

            manager.reconcile();
            assertThat(manager.statuses()).singleElement()
                      .satisfies(status -> assertThat(status.eventTypePublishable()).describedAs("this node cannot know, and must not invent an answer")
                                                     .isEqualTo(Option.none()));
        }
    }

    /// #545: two DIFFERENT ARTIFACTS declaring the same `(stream, group)` collide — `SubscriptionKey`
    /// and `ConsumerKey` are deliberately artifact-free, so nothing downstream can tell them apart, and
    /// the historical `declarationFor` `findFirst()` picked a winner arbitrarily. Two VERSIONS of the
    /// SAME artifact sharing a group is the opposite case: the intended blue-green collapse, and must
    /// keep working exactly as before.
    @Nested
    class GroupCollisions {
        private static final Artifact OTHER_ARTIFACT = Artifact.artifact("org.example:payments:1.0.0").unwrap();

        private void declare(Artifact artifact, String eventType, boolean batchMode) {
            var key = StreamRegistrationKey.streamRegistrationKey(STREAM, CONFIG_SECTION, artifact, METHOD);
            var value = StreamRegistrationValue.streamRegistrationValue(SELF, GROUP, batchMode, eventType);

            registry.onStreamRegistrationPut(new ValuePut<>(new KVCommand.Put<>(key, value), Option.none()));
        }

        @Test
        void reconcile_subscribesNeitherSide_whenTwoArtifactsShareOneGroup() {
            declare(ARTIFACT, "java.lang.String", false);
            declare(OTHER_ARTIFACT, "java.lang.String", false);
            deploySliceLocally();
            when(invocationHandler.localSlice(OTHER_ARTIFACT)).thenReturn(Option.some(new StubBridge(Option.none())));
            ownership.ownedBySelf(0, 1, 2, 3);
            manager().reconcile();
            assertThat(runtime.subscribedPartitions()).describedAs("neither colliding artifact may consume — an arbitrary winner was the #545 defect, not a feature to preserve for either side")
                      .isEmpty();
        }

        @Test
        void statuses_nameBothArtifactsStreamAndGroup_whenTheyCollide() {
            declare(ARTIFACT, "java.lang.String", false);
            declare(OTHER_ARTIFACT, "java.lang.String", false);
            var manager = manager();

            manager.reconcile();
            assertThat(manager.statuses()).hasSize(2)
                      .allSatisfy(status -> assertThat(status.diagnostic().or("")).describedAs("an operator reading EITHER artifact's status must see both names, the stream and the group — not just its own")
                                                     .contains(ARTIFACT.base().asString())
                                                     .contains(OTHER_ARTIFACT.base().asString())
                                                     .contains(STREAM)
                                                     .contains(GROUP));
        }

        @Test
        void reconcile_doesNotFlagCollision_whenTwoVersionsOfOneArtifactShareTheGroup() {
            var upgraded = Artifact.artifact("org.example:orders:1.1.0").unwrap();

            declare(ARTIFACT, "java.lang.String", false);
            declare(upgraded, "java.lang.String", false);
            deploySliceLocally();
            when(invocationHandler.localSlice(upgraded)).thenReturn(Option.some(new StubBridge(Option.none())));
            ownership.ownedBySelf(0, 1, 2, 3);
            var manager = manager();

            manager.reconcile();
            assertThat(manager.statuses()).hasSize(2)
                      .allSatisfy(status -> assertThat(status.diagnostic()).describedAs("two VERSIONS of one artifact sharing a group is the intended blue-green collapse (see TopicGroupDeclarationSource), not a collision")
                                                     .isEqualTo(Option.none()));
            assertThat(runtime.subscribedPartitions()).describedAs("the shared group keeps consuming across the upgrade window — the collision guard must not treat it as a fault")
                      .containsExactlyInAnyOrder(0, 1, 2, 3);
        }

        /// #1267 replaced what this test used to pin. A pass used to read the declarations once to build
        /// `desired` and again, per key, to resolve each key back to a declaration, so a colliding
        /// declaration landing between those reads reached `declarationFor`'s refusal branch mid-pass.
        /// A pass now reads ONE snapshot and resolves every key against it, so that interleaving no
        /// longer exists: a collision landing mid-pass is invisible to that pass and is acted on by the
        /// next one. Pinned here: the registry is read exactly once per pass, the first pass acts on
        /// what it read, and the next pass retracts the attachment the moment the collision is visible —
        /// fail-closed per pass, never an arbitrary winner.
        @Test
        void reconcile_readsOneSnapshotPerPass_andRetractsOnTheNextPass_whenACollisionLandsBetweenPasses() {
            var declarationA = new StreamConsumerRegistry.ConsumerDeclaration(STREAM, CONFIG_SECTION, ARTIFACT, METHOD, GROUP, false, "java.lang.String");
            var declarationB = new StreamConsumerRegistry.ConsumerDeclaration(STREAM, CONFIG_SECTION, OTHER_ARTIFACT, METHOD, GROUP, false, "java.lang.String");
            var raceyRegistry = mock(StreamConsumerRegistry.class);

            when(raceyRegistry.allDeclarations()).thenReturn(List.of(declarationA))
                                                 .thenReturn(List.of(declarationA, declarationB));
            deploySliceLocally();
            ownership.ownedBySelf(0, 1, 2, 3);

            var manager = managerWithRegistry(raceyRegistry);

            manager.reconcile();

            verify(raceyRegistry, times(1)).allDeclarations();
            assertThat(runtime.subscribedPartitions()).describedAs("the first pass's one snapshot holds no collision — consuming is the correct decision for what it read")
                      .containsExactlyInAnyOrder(0, 1, 2, 3);

            manager.reconcile();

            assertThat(runtime.subscribedPartitions()).describedAs("the next pass sees the collision and retracts — never an arbitrary winner")
                      .isEmpty();
        }

        /// The other three tests all declare both sides before the first `reconcile()` — this is the
        /// LATE-arriving case: the first artifact is already actively consuming when the second,
        /// colliding declaration appears. `reconcile()` re-derives `collidingGroups` from scratch on
        /// EVERY call and re-evaluates every registered declaration against it, so the fix must not be
        /// read as "a collision blocks attachment" only — it must also retract an attachment made before
        /// the collision existed. Fail-closed is a fresh re-check each round, not a one-time gate at
        /// first sight.
        @Test
        void reconcile_unsubscribesTheFirstArtifact_whenACollidingDeclarationArrivesLate() {
            declare(ARTIFACT, "java.lang.String", false);
            deploySliceLocally();
            ownership.ownedBySelf(0, 1, 2, 3);
            var manager = manager();

            manager.reconcile();
            assertThat(runtime.subscribedPartitions()).describedAs("before the second artifact declares, the sole declarant consumes normally")
                      .containsExactlyInAnyOrder(0, 1, 2, 3);
            assertThat(manager.statuses()).describedAs("before the collision, the sole declarant carries no diagnostic")
                      .hasSize(1)
                      .allSatisfy(status -> assertThat(status.diagnostic()).isEqualTo(Option.none()));

            declare(OTHER_ARTIFACT, "java.lang.String", false);
            when(invocationHandler.localSlice(OTHER_ARTIFACT)).thenReturn(Option.some(new StubBridge(Option.none())));
            manager.reconcile();
            assertThat(runtime.subscribedPartitions()).describedAs("the late collision must retract the already-active side too — a stale active subscription surviving would be a silent gap in the #545 fail-closed guarantee")
                      .isEmpty();
            assertThat(manager.statuses()).describedAs("the transition must also surface the diagnostic on BOTH sides — the first artifact's prior clean status must not survive the retraction unexplained")
                      .hasSize(2)
                      .allSatisfy(status -> assertThat(status.diagnostic().or("")).contains(ARTIFACT.base().asString())
                                                     .contains(OTHER_ARTIFACT.base().asString())
                                                     .contains(STREAM)
                                                     .contains(GROUP));
        }

        /// Review of #844, finding 2: `unsubscribeAllFor` used to filter `active` by consumer group
        /// alone, so a collision on ONE stream could sweep an unrelated, non-colliding subscription on
        /// a DIFFERENT stream that merely happens to reuse the same group string. `RecordingRuntime` is
        /// keyed by (stream, partition) precisely so this is observable at all: `invoices` never
        /// collides (its `ConsumerGroupKey` is `("invoices", GROUP)`, distinct from `("orders", GROUP)`).
        ///
        /// Two reconcile() calls, not one: `unsubscribeAllFor` fires INSIDE `desiredFor`'s evaluation,
        /// before `subscribeIfAbsent` ever runs, so the sweep needs `invoices` ALREADY active from a
        /// prior round to have anything to hit.
        ///
        /// Final membership is NOT the pinning assertion — a mutation probe proved this the hard way.
        /// `attach()` uses `active.putIfAbsent`, so even a same-pass detach of `invoices` self-heals
        /// silently: `invoices`'s declaration is still non-colliding and still in `desired`, so
        /// `subscribeIfAbsent` re-subscribes it before `reconcile()` returns, and `subscribedPartitions`
        /// looks identical either way. What actually differs is `subscribeCalls`: a group-only filter
        /// detaches and then transparently RE-subscribes `invoices` — a spurious `unsubscribe`/
        /// `subscribe` round trip a real runtime would feel as a graceful cursor flush plus a fresh
        /// resume, wasted work with a resume-window gap, not permanent data loss. The stream-scoped
        /// filter never touches `invoices` at all: zero new subscribe calls.
        @Test
        void reconcile_leavesAnUnrelatedStreamAlone_whenItsGroupNameCollidesOnlyOnAnotherStream() {
            var otherStream = "invoices";
            var otherConfigSection = "streams.invoices";
            var otherStreamArtifact = Artifact.artifact("org.example:invoices:1.0.0").unwrap();

            var otherKey = StreamRegistrationKey.streamRegistrationKey(otherStream, otherConfigSection, otherStreamArtifact, METHOD);
            var otherValue = StreamRegistrationValue.streamRegistrationValue(SELF, GROUP, false, "java.lang.String");
            registry.onStreamRegistrationPut(new ValuePut<>(new KVCommand.Put<>(otherKey, otherValue), Option.none()));
            when(invocationHandler.localSlice(otherStreamArtifact)).thenReturn(Option.some(new StubBridge(Option.none())));

            declare(ARTIFACT, "java.lang.String", false);
            deploySliceLocally();
            ownership.ownedBySelf(0, 1, 2, 3);
            var manager = manager();

            manager.reconcile();
            assertThat(runtime.subscribedPartitions(STREAM)).describedAs("orders' sole declarant consumes normally before the collision")
                      .containsExactlyInAnyOrder(0, 1, 2, 3);
            assertThat(runtime.subscribedPartitions(otherStream)).describedAs("invoices' sole declarant, reusing the same group NAME on a different stream, also consumes normally and is now ACTIVE")
                      .containsExactlyInAnyOrder(0, 1, 2, 3);
            var subscribeCallsBeforeCollision = runtime.subscribeCalls;

            declare(OTHER_ARTIFACT, "java.lang.String", false);
            when(invocationHandler.localSlice(OTHER_ARTIFACT)).thenReturn(Option.some(new StubBridge(Option.none())));
            manager.reconcile();

            assertThat(runtime.subscribedPartitions(STREAM)).describedAs("both colliding artifacts on `orders` must be retracted")
                      .isEmpty();
            assertThat(runtime.subscribedPartitions(otherStream)).describedAs("membership alone doesn't distinguish the fix from the bug — attach()'s putIfAbsent self-heals a same-pass detach")
                      .containsExactlyInAnyOrder(0, 1, 2, 3);
            assertThat(runtime.subscribeCalls).describedAs("the actual symptom: a group-only filter detaches `invoices` mid-pass and then transparently re-subscribes it since it is still desired — a spurious unsubscribe+resubscribe a real runtime would feel as a needless cursor flush and resume. A stream-scoped filter never touches `invoices`: zero new subscribe calls since before the collision")
                      .isEqualTo(subscribeCallsBeforeCollision);
        }
    }

    /// #1267: one declaration snapshot per reconcile pass, and passes that never overlap.
    ///
    /// The timer tick and the registration-change listener both call `reconcile()`, on different
    /// threads. Unserialized, a pass that read the declarations BEFORE a registration landed can finish
    /// AFTER the listener's pass attached that registration's partitions, and its `dropStale` then
    /// detaches what the newer pass just attached — leaving a desired consumer detached until the next
    /// tick. The latch parks the older pass inside that window; it widens the window, it does not create
    /// it.
    @Nested
    class ReconcilePasses {
        private static final long PARK_TIMEOUT_SECONDS = 10;
        private static final int BURST_SIZE = 5;

        @Test
        void reconcile_readsTopicDeclarationsExactlyOnce_perPass() {
            var topicGroups = new CountingTopicGroups();

            declareStringConsumer();
            deploySliceLocally();
            ownership.ownedBySelf(0, 1, 2, 3);
            var manager = managerWithTopicGroups(topicGroups);

            manager.reconcile();

            assertThat(runtime.subscribedPartitions()).containsExactlyInAnyOrder(0, 1, 2, 3);
            assertThat(topicGroups.calls.get()).describedAs("one declaration snapshot per pass — not one more per desired subscription")
                                               .isEqualTo(1);
        }

        @Test
        void reconcile_keepsAttachedPartitions_whenAnOlderConcurrentPassFinishesLast() throws InterruptedException {
            var topicGroups = new ParkingTopicGroups();

            deploySliceLocally();
            ownership.ownedBySelf(0, 1, 2, 3);
            var manager = managerWithTopicGroups(topicGroups);
            var timerPass = Thread.ofPlatform().start(manager::reconcile);

            assertThat(topicGroups.parked.await(PARK_TIMEOUT_SECONDS, TimeUnit.SECONDS)).describedAs("the timer pass reached the declaration read with the OLD registry snapshot")
                                                                                   .isTrue();
            var listenerPass = Thread.ofPlatform().start(ReconcilePasses.this::declareOnListenerThread);

            awaitFinishedOrBlocked(listenerPass);
            topicGroups.release.countDown();
            timerPass.join(PARK_TIMEOUT_SECONDS * 1_000);
            listenerPass.join(PARK_TIMEOUT_SECONDS * 1_000);

            assertThat(timerPass.isAlive() || listenerPass.isAlive()).describedAs("both passes completed").isFalse();
            assertThat(runtime.subscribedPartitions()).describedAs("the newer registration's partitions stay attached once both passes are done")
                                                      .containsExactlyInAnyOrder(0, 1, 2, 3);
            assertThat(manager.activeSubscriptionCount()).isEqualTo(4);
        }

        /// Coalescing (#1267): every trigger that arrives while a pass is in flight is covered by ONE
        /// follow-up pass, not one pass per trigger. Counted by declaration reads — one per pass.
        @Test
        void reconcile_runsExactlyOneFollowUpPass_forABurstOfTriggersDuringABusyPass() throws InterruptedException {
            var topicGroups = new ParkingTopicGroups();
            var manager = managerWithTopicGroups(topicGroups);
            var busyPass = Thread.ofPlatform().start(manager::reconcile);

            assertThat(topicGroups.parked.await(PARK_TIMEOUT_SECONDS, TimeUnit.SECONDS)).describedAs("the busy pass reached its declaration read")
                                                                                   .isTrue();
            var burst = IntStream.range(0, BURST_SIZE)
                                 .mapToObj(_ -> Thread.ofPlatform().start(manager::reconcile))
                                 .toList();

            for (var trigger : burst) {
                awaitFinishedOrBlocked(trigger);
            }
            topicGroups.release.countDown();
            busyPass.join(PARK_TIMEOUT_SECONDS * 1_000);
            for (var trigger : burst) {
                trigger.join(PARK_TIMEOUT_SECONDS * 1_000);
            }

            assertThat(topicGroups.calls.get()).describedAs("the busy pass plus ONE follow-up pass for all %d triggers", BURST_SIZE)
                                               .isEqualTo(2);
        }

        /// `stop()` waits for an in-flight pass, so a pass parked past its snapshot cannot attach after
        /// `stop()` returns: the stop's detach sweep runs after that pass has finished.
        @Test
        void stop_leavesNothingAttached_whenAPassInFlightFinishesAfterStopWasCalled() throws InterruptedException {
            var topicGroups = new ParkingTopicGroups();

            declareStringConsumer();
            deploySliceLocally();
            ownership.ownedBySelf(0, 1, 2, 3);
            var manager = managerWithTopicGroups(topicGroups);
            var inFlightPass = Thread.ofPlatform().start(manager::reconcile);

            assertThat(topicGroups.parked.await(PARK_TIMEOUT_SECONDS, TimeUnit.SECONDS)).describedAs("the pass holds its snapshot and has not attached yet")
                                                                                   .isTrue();
            var stopping = Thread.ofPlatform().start(manager::stop);

            awaitFinishedOrBlocked(stopping);
            topicGroups.release.countDown();
            inFlightPass.join(PARK_TIMEOUT_SECONDS * 1_000);
            stopping.join(PARK_TIMEOUT_SECONDS * 1_000);

            assertThat(inFlightPass.isAlive() || stopping.isAlive()).describedAs("the pass and the stop completed").isFalse();
            assertThat(runtime.subscribedPartitions()).describedAs("nothing may stay attached once stop() has returned")
                                                      .isEmpty();
            assertThat(manager.activeSubscriptionCount()).isZero();
        }

        /// The lock alone is not enough: a trigger after `stop()` — a queued caller, or a tick that
        /// outlives it — must not run a pass that re-attaches.
        @Test
        void reconcile_attachesNothing_afterStop() {
            declareStringConsumer();
            deploySliceLocally();
            ownership.ownedBySelf(0, 1, 2, 3);
            var manager = managerWithTopicGroups(new CountingTopicGroups());

            manager.reconcile();
            manager.stop();
            manager.reconcile();

            assertThat(runtime.subscribedPartitions()).describedAs("a stopped manager must not re-attach").isEmpty();
            assertThat(manager.activeSubscriptionCount()).isZero();
        }

        /// `stopped` must be set BEFORE the sweep, not after it. A pass triggered while the sweep holds
        /// the lock — here re-entrantly from inside the sweep, which is the one interleaving a test can
        /// force without a scheduler — must find the manager already stopped; with the flag set after
        /// the sweep it runs, re-attaches what the sweep had just detached, and survives `stop()`.
        @Test
        void stop_leavesNothingAttached_whenAPassIsTriggeredDuringTheDetachSweep() {
            declareStringConsumer();
            deploySliceLocally();
            ownership.ownedBySelf(0, 1, 2, 3);
            var manager = managerWithTopicGroups(new CountingTopicGroups());
            var triggered = new AtomicBoolean();

            manager.reconcile();
            runtime.afterUnsubscribe = () -> triggerOnce(manager, triggered);
            manager.stop();

            assertThat(triggered.get()).describedAs("a pass was triggered from inside the sweep").isTrue();
            assertThat(runtime.subscribedPartitions()).describedAs("a pass triggered during stop() must not re-attach")
                                                      .isEmpty();
            assertThat(manager.activeSubscriptionCount()).isZero();
        }

        private static void triggerOnce(StreamConsumerManager manager, AtomicBoolean triggered) {
            if (triggered.compareAndSet(false, true)) {
                manager.reconcile();
            }
        }

        private void declareOnListenerThread() {
            declareStringConsumer();
        }

        /// The listener pass either ran to completion (unserialized) or is waiting for the parked pass
        /// (serialized); only then is the parked pass released, so the older pass finishes LAST.
        private static void awaitFinishedOrBlocked(Thread thread) throws InterruptedException {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PARK_TIMEOUT_SECONDS);

            while (thread.isAlive() && !isWaiting(thread) && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
        }

        private static boolean isWaiting(Thread thread) {
            return switch (thread.getState()) {
                case BLOCKED, WAITING, TIMED_WAITING -> true;
                default -> false;
            };
        }

        private StreamConsumerManager managerWithTopicGroups(TopicGroupDeclarationSource topicGroups) {
            return StreamConsumerManager.streamConsumerManager(registry,
                                                               runtime,
                                                               invoker,
                                                               invocationHandler,
                                                               FrameworkCodecs.frameworkCodecs(),
                                                               ownership,
                                                               placement,
                                                               SELF,
                                                               topicGroups,
                                                               authority(true));
        }
    }

    /// Counts declaration reads; synthesizes no topic declarations.
    private static final class CountingTopicGroups implements TopicGroupDeclarationSource {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public List<StreamConsumerRegistry.ConsumerDeclaration> declarations() {
            calls.incrementAndGet();

            return List.of();
        }
    }

    /// Parks the FIRST declaration read until released. `allDeclarations()` reads the registry before
    /// the topic source, so the parked pass holds the registry snapshot taken before the park.
    private static final class ParkingTopicGroups implements TopicGroupDeclarationSource {
        private static final long PARK_SECONDS = 10;

        private final CountDownLatch parked = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public List<StreamConsumerRegistry.ConsumerDeclaration> declarations() {
            if (calls.getAndIncrement() == 0) {
                parked.countDown();
                awaitRelease();
            }

            return List.of();
        }

        private void awaitRelease() {
            try {
                release.await(PARK_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Nested
    class Lifecycle {
        @Test
        void stop_unsubscribesEverySubscription() {
            declareStringConsumer();
            deploySliceLocally();
            ownership.ownedBySelf(0, 1, 2, 3);
            var manager = manager();

            manager.reconcile();
            assertThat(manager.activeSubscriptionCount()).isEqualTo(4);
            manager.stop();
            assertThat(runtime.subscribedPartitions()).describedAs("a stopped node must leave nothing attached — #499 zombie lesson")
                      .isEmpty();
            assertThat(manager.activeSubscriptionCount()).isZero();
        }

        /// #1266 and rev1272 F7 follow-up: every non-delivering state reaches the per-partition status
        /// the declarative-consumers route renders, each from its OWN snapshot field — set one at a time
        /// so a swapped or dropped mapping shows.
        @Test
        void statuses_carryEachNonDeliveringState_fromItsOwnSnapshotField() {
            declareStringConsumer();
            deploySliceLocally();
            ownership.ownedBySelf(0);
            ownership.withPartitionCount(1);
            var manager = manager();

            manager.reconcile();
            runtime.states(true, false, false);
            assertThat(manager.statuses()).singleElement()
                      .satisfies(status -> assertThat(status.assignedPartitions()).singleElement()
                                                                                  .satisfies(cursor -> {
                                                                                                 assertThat(cursor.deadLetterInFlight()).isTrue();
                                                                                                 assertThat(cursor.retryInFlight()).isFalse();
                                                                                                 assertThat(cursor.awaitingCursorFetch()).isFalse();
                                                                                             }));
            runtime.states(false, true, false);
            assertThat(manager.statuses()).singleElement()
                      .satisfies(status -> assertThat(status.assignedPartitions()).singleElement()
                                                                                  .satisfies(cursor -> {
                                                                                                 assertThat(cursor.deadLetterInFlight()).isFalse();
                                                                                                 assertThat(cursor.retryInFlight()).isTrue();
                                                                                                 assertThat(cursor.awaitingCursorFetch()).isFalse();
                                                                                             }));
            runtime.states(false, false, true);
            assertThat(manager.statuses()).singleElement()
                      .satisfies(status -> assertThat(status.assignedPartitions()).singleElement()
                                                                                  .satisfies(cursor -> {
                                                                                                 assertThat(cursor.deadLetterInFlight()).isFalse();
                                                                                                 assertThat(cursor.retryInFlight()).isFalse();
                                                                                                 assertThat(cursor.awaitingCursorFetch()).isTrue();
                                                                                             }));
        }

        @Test
        void statuses_areEmpty_whenNothingDeclared() {
            assertThat(manager().statuses()).isEmpty();
        }
    }

    /// #1271: consumer-group partition assignment must be FENCED. Two nodes whose local views of the
    /// partition's owner disagree — each believing it is the assignee — must not both attach: only the
    /// node named by the COMMITTED assignment record may deliver. Before the fix each node decided from
    /// its own view alone, so both attached, both delivered, and both wrote the cursor.
    @Nested
    class FencedAssignment {
        @Test
        void reconcile_attachesAtMostOneNode_whenTwoNodesViewsOfTheOwnerDisagree() {
            declareStringConsumer();
            deploySliceEverywhere();
            var selfView = new MutableOwnership();
            var peerView = new MutableOwnership();

            selfView.ownedBy(SELF, 0);
            peerView.ownedBy(PEER, 0);
            var selfRuntime = new RecordingRuntime();
            var peerRuntime = new RecordingRuntime();

            // One cluster, one leader: SELF's node leads and commits the assignment; PEER follows. Both
            // compute themselves as the consumer of partition 0 from their own views.
            managerFor(SELF, selfRuntime, selfView, true).reconcile();
            managerFor(PEER, peerRuntime, peerView, false).reconcile();

            var attached = Stream.of(selfRuntime, peerRuntime)
                                 .filter(nodeRuntime -> nodeRuntime.subscribedPartitions()
                                                                   .contains(0))
                                 .count();

            assertThat(attached).describedAs("divergent owner views must not produce two consumers of one (group, partition)")
                                .isLessThanOrEqualTo(1L);
        }

        /// The follower whose OWN view names itself still attaches nothing: its view is not the authority.
        @Test
        void reconcile_attachesOnlyWhereTheCommittedRecordNamesThisNode_evenAgainstItsOwnView() {
            declareStringConsumer();
            deploySliceEverywhere();
            var peerView = new MutableOwnership();

            peerView.ownedBy(PEER, 0, 1, 2, 3);
            commitAssignment(0, SELF, EPOCH_1);
            commitAssignment(1, PEER, EPOCH_1);
            var peerRuntime = new RecordingRuntime();

            managerFor(PEER, peerRuntime, peerView, false).reconcile();

            assertThat(peerRuntime.subscribedPartitions()).describedAs("committed: 0→SELF, 1→PEER, 2 and 3 → none")
                                                          .containsExactly(1);
        }

        /// rev1335 M12: `attach` re-reads the committed record instead of trusting the desired set computed a
        /// moment earlier in the same pass. On a first pass partition 0's record is read exactly twice — once
        /// into the desired set, once at attach — so a reader that names this node on its first read and PEER
        /// from the second on is a reassignment landing between the two, and it must attach nothing.
        @Test
        void attach_reReadsTheCommittedRecord_andAttachesNothing_whenItMovedSinceTheDesiredSetWasComputed() {
            declareStringConsumer();
            deploySliceEverywhere();
            var readsOfPartition0 = new AtomicInteger();
            ConsumerAssignmentWriter.CommittedAssignments movingAway = (_, partition, _) -> partition == 0 && readsOfPartition0.getAndIncrement() == 0
                                                                                           ? Option.some(assignmentRecord(SELF, EPOCH_1))
                                                                                           : Option.some(assignmentRecord(PEER, EPOCH_2));

            managerReading(movingAway).reconcile();

            assertThat(readsOfPartition0.get()).describedAs("precondition: the desired set's read and the attach re-read")
                                               .isEqualTo(2);
            assertThat(runtime.subscribedPartitions()).describedAs("by the time attach re-read it, the record named PEER")
                                                      .isEmpty();
        }

        @Test
        void reconcile_attachesNothing_whenNoAssignmentIsCommitted() {
            declareStringConsumer();
            deploySliceEverywhere();
            ownership.ownedBySelf(0, 1, 2, 3);

            managerFor(SELF, runtime, ownership, false).reconcile();

            assertThat(runtime.subscribedPartitions()).describedAs("admitting on absence is exactly the unfenced behaviour")
                                                      .isEmpty();
        }

        @Test
        void attach_carriesTheCommittedEpoch_andTheFenceTracksTheCommittedRecord() {
            declareStringConsumer();
            deploySliceEverywhere();
            commitAssignment(0, SELF, EPOCH_1);

            managerFor(SELF, runtime, ownership, false).reconcile();
            var fence = runtime.fenceOf(0);

            assertThat(fence.map(ConsumerFence::epoch)).isEqualTo(Option.some(EPOCH_1));
            assertThat(fence.map(ConsumerFence::admitted)).isEqualTo(Option.some(true));
            commitAssignment(0, PEER, EPOCH_2);
            assertThat(fence.map(ConsumerFence::admitted)).describedAs("delivery pauses at the next pass once the mirror shows the move")
                                                          .isEqualTo(Option.some(false));
        }

        @Test
        void reconcile_abandonsWithoutFlush_whenTheAssignmentMovesAway() {
            declareStringConsumer();
            deploySliceEverywhere();
            commitAssignment(0, SELF, EPOCH_1);
            var manager = managerFor(SELF, runtime, ownership, false);

            manager.reconcile();
            commitAssignment(0, PEER, EPOCH_2);
            manager.reconcile();

            assertThat(runtime.subscribedPartitions()).isEmpty();
            assertThat(runtime.abandoned).describedAs("the loser detaches without the final flush").containsExactly(0);
            assertThat(runtime.gracefullyUnsubscribed).isEmpty();
        }

        @Test
        void reconcile_detachesWithFlush_whenStillTheAssignee() {
            declareStringConsumer();
            deploySliceEverywhere();
            commitAssignment(0, SELF, EPOCH_1);
            var manager = managerFor(SELF, runtime, ownership, false);

            manager.reconcile();
            undeclare();
            manager.reconcile();

            assertThat(runtime.gracefullyUnsubscribed).describedAs("still the assignee: the final flush is the cursor's last word")
                                                      .containsExactly(0);
            assertThat(runtime.abandoned).isEmpty();
        }

        /// A→B→A between two reconciles: the subscription from the first tenure is abandoned and replaced
        /// by one under the current epoch — its cursor state predates B's tenure.
        @Test
        void reconcile_reattachesUnderTheCurrentEpoch_afterAnAwayAndBackMove() {
            declareStringConsumer();
            deploySliceEverywhere();
            commitAssignment(0, SELF, EPOCH_1);
            var manager = managerFor(SELF, runtime, ownership, false);

            manager.reconcile();
            commitAssignment(0, PEER, EPOCH_2);
            commitAssignment(0, SELF, EPOCH_3);
            manager.reconcile();

            assertThat(runtime.abandoned).containsExactly(0);
            assertThat(runtime.fenceOf(0).map(ConsumerFence::epoch)).isEqualTo(Option.some(EPOCH_3));
        }

        @Test
        void abandonAll_detachesEverySubscriptionWithoutFlush() {
            declareStringConsumer();
            deploySliceEverywhere();
            commitAssignment(0, SELF, EPOCH_1);
            commitAssignment(1, SELF, EPOCH_1);
            var manager = managerFor(SELF, runtime, ownership, false);

            manager.reconcile();
            manager.abandonAll();

            assertThat(manager.activeSubscriptionCount()).isZero();
            assertThat(runtime.abandoned).containsExactlyInAnyOrder(0, 1);
            assertThat(runtime.gracefullyUnsubscribed).isEmpty();
        }

        /// The quorum-loss listener stops delivery BEFORE the drain chain runs — the drain ends in a halt
        /// that may be seconds away.
        @Test
        void abandoningOnQuorumLoss_abandonsConsumersBeforeTheDrainChain() {
            declareStringConsumer();
            deploySliceEverywhere();
            commitAssignment(0, SELF, EPOCH_1);
            var manager = managerFor(SELF, runtime, ownership, false);
            var seenByChain = new ArrayList<Integer>();

            manager.reconcile();
            StreamConsumerManager.<String>abandoningOnQuorumLoss(_ -> seenByChain.add(manager.activeSubscriptionCount()),
                                                                 manager)
                                 .accept("quorum-lost");

            assertThat(seenByChain).describedAs("the drain chain runs, and it runs after the consumers are gone")
                                   .containsExactly(0);
            assertThat(runtime.abandoned).containsExactly(0);
        }
    }

    /// Ownership stub. Defaults to a resolved three-node cluster in which nobody owns anything, which
    /// forces every test to state the ownership it depends on.
    private static final class MutableOwnership implements PartitionOwnership {
        private final Map<Integer, NodeId> owners = new ConcurrentHashMap<>();
        private volatile boolean streamKnown = true;
        private volatile int partitions = PARTITION_COUNT;
        private volatile List<NodeId> members = CLUSTER;

        void withPartitionCount(int count) {
            partitions = count;
        }

        void ownedBySelf(int... partitions) {
            ownedBy(SELF, partitions);
        }

        void ownedBy(NodeId node, int... partitions) {
            owners.values().removeIf(node::equals);
            for (var partition : partitions) {
                owners.put(partition, node);
            }
        }

        void forgetStream() {
            streamKnown = false;
        }

        void forgetMembership() {
            members = List.of();
        }

        @Override
        public Option<Integer> partitionCount(String streamName) {
            return streamKnown
                   ? Option.some(partitions)
                   : Option.none();
        }

        @Override
        public Option<NodeId> ownerOf(String streamName, int partition) {
            return Option.option(owners.get(partition));
        }

        @Override
        public List<NodeId> liveMembers() {
            return members;
        }
    }

    /// Placement stub: where the artifact is deployed cluster-wide, with its per-node state. Defaults
    /// to nowhere, so a test that forgets to deploy sees the honest "nothing can consume this" answer
    /// rather than a silent pass.
    private static final class MutablePlacement implements SlicePlacement {
        private final Map<NodeId, SliceState> states = new ConcurrentHashMap<>();

        void activeOn(NodeId... active) {
            states.clear();
            for (var node : active) {
                states.put(node, SliceState.ACTIVE);
            }
        }

        /// Deployed but not yet ACTIVE — the window every normal deploy passes through.
        void activatingOn(NodeId... pending) {
            states.clear();
            for (var node : pending) {
                states.put(node, SliceState.ACTIVATING);
            }
        }

        @Override
        public Map<NodeId, SliceState> placement(Artifact artifact) {
            return Map.copyOf(states);
        }
    }

    /// Records subscribe/unsubscribe instead of running a delivery loop, so the assignment decision is
    /// observable without a partition manager. Keyed by (stream, partition) rather than partition
    /// alone — #545 review: a partition number is not unique across streams, and the over-detach bug
    /// in `unsubscribeAllFor` (group matched, stream did not) can only be pinned by a double that keeps
    /// two streams' subscriptions apart. Every existing test uses one stream, so `subscribedPartitions()`
    /// (no-arg) stays behaviorally identical to the old partition-only map.
    private static final class RecordingRuntime implements StreamConsumerRuntime {
        private record StreamPartition(String streamName, int partition) {}

        private final Map<StreamPartition, String> subscriptions = new ConcurrentHashMap<>();
        private final Map<StreamPartition, ConsumerFence> fences = new ConcurrentHashMap<>();
        private final List<Integer> abandoned = new CopyOnWriteArrayList<>();
        private final List<Integer> gracefullyUnsubscribed = new CopyOnWriteArrayList<>();
        private int subscribeCalls;
        private volatile boolean deadLetterHold;
        private volatile boolean retryHold;
        private volatile boolean awaitingCursorFetch;
        // Runs on the unsubscribing thread after each unsubscribe — lets a test trigger a pass from INSIDE
        // the stop sweep, while that thread holds the pass lock. Inert by default.
        private volatile Runnable afterUnsubscribe = () -> {};

        void states(boolean deadLetter, boolean retry, boolean awaitingFetch) {
            deadLetterHold = deadLetter;
            retryHold = retry;
            awaitingCursorFetch = awaitingFetch;
        }

        Option<ConsumerFence> fenceOf(int partition) {
            return Option.option(fences.get(new StreamPartition(STREAM, partition)));
        }

        @Override
        public Result<Unit> subscribe(String streamName,
                                      int partition,
                                      ConsumerConfig config,
                                      ConsumerCallback callback,
                                      IdlePolicy idlePolicy,
                                      ConsumerFence fence) {
            fences.put(new StreamPartition(streamName, partition), fence);

            return subscribe(streamName, partition, config, callback, idlePolicy);
        }

        @Override
        public Result<Unit> abandon(String streamName, int partition, String consumerGroup) {
            abandoned.add(partition);
            subscriptions.remove(new StreamPartition(streamName, partition));

            return Result.unitResult();
        }

        List<Integer> subscribedPartitions() {
            return subscriptions.keySet().stream().map(StreamPartition::partition).distinct().toList();
        }

        List<Integer> subscribedPartitions(String streamName) {
            return subscriptions.keySet()
                                .stream()
                                .filter(key -> key.streamName().equals(streamName))
                                .map(StreamPartition::partition)
                                .toList();
        }

        @Override
        public Result<Unit> subscribe(String streamName,
                                      int partition,
                                      ConsumerConfig config,
                                      ConsumerCallback callback) {
            return subscribe(streamName, partition, config, callback, IdlePolicy.REAP_WHEN_IDLE);
        }

        @Override
        public Result<Unit> subscribe(String streamName,
                                      int partition,
                                      ConsumerConfig config,
                                      ConsumerCallback callback,
                                      IdlePolicy idlePolicy) {
            subscribeCalls++;
            subscriptions.put(new StreamPartition(streamName, partition), config.groupId());

            return Result.unitResult();
        }

        @Override
        public Result<Unit> unsubscribe(String streamName, int partition, String consumerGroup) {
            gracefullyUnsubscribed.add(partition);
            subscriptions.remove(new StreamPartition(streamName, partition));
            afterUnsubscribe.run();

            return Result.unitResult();
        }

        @Override
        public Option<Long> cursorPosition(String streamName, int partition, String consumerGroup) {
            return Option.none();
        }

        @Override
        public Option<TransactionalCursorCommit> transactionalCursorCommit() {
            return Option.none();
        }

        @Override
        public DeadLetterHandler deadLetterHandler() {
            return DeadLetterHandler.deadLetterHandler();
        }

        @Override
        public List<SubscriptionSnapshot> subscriptions() {
            return subscriptions.entrySet()
                                .stream()
                                .map(entry -> new SubscriptionSnapshot(entry.getKey().streamName(),
                                                                       entry.getKey().partition(),
                                                                       entry.getValue(),
                                                                       0L,
                                                                       false,
                                                                       IdlePolicy.KEEP_UNTIL_UNSUBSCRIBED,
                                                                       Option.none(),
                                                                       deadLetterHold,
                                                                       retryHold,
                                                                       awaitingCursorFetch))
                                .toList();
        }

        @Override
        public long cursorCommitFailureCount() {
            return 0;
        }

        @Override
        public void close() {}
    }

    /// Bridge stub whose classLoader resolves the declared event type, so the #526 publishability
    /// probe runs against a real class lookup. `sliceCodec` mirrors what a deployed slice carries:
    /// present means the slice has its own codec registry, absent means the probe falls back to the
    /// node codec.
    /// #386 option-(a) wiring: durable-topic groups ride THIS manager — synthesized declarations,
    /// the same assignment machinery, and an envelope-unwrap in delivery. These tests pin the three
    /// seams and ONLY the seams; every declarative-consumer test above is the untouched regression
    /// fence proving placement/assignment/failover logic did not move.
    @Nested
    class TopicGroupDispatch {
        private static final String TOPIC_ADDRESS = "org.example:order-events:1.0.0";
        private static final String TOPIC_STREAM = "topic:" + TOPIC_ADDRESS;
        private static final String TOPIC_GROUP = "org.example:orders#" + METHOD.name();
        private static final MethodName ON_PLACED = MethodName.methodName("onPlaced").unwrap();
        private static final MethodName ON_PLACED_WITH_CONTEXT = MethodName.methodName("onPlacedWithContext").unwrap();

        private org.pragmatica.aether.endpoint.TopicSubscriptionRegistry topicRegistry;
        private CapturingRuntime capturingRuntime;
        private SliceCodec topicAwareCodec;

        @BeforeEach
        void setUpTopics() {
            topicRegistry = org.pragmatica.aether.endpoint.TopicSubscriptionRegistry.topicSubscriptionRegistry();
            capturingRuntime = new CapturingRuntime();
            topicAwareCodec = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(),
                                                    org.pragmatica.aether.stream.topic.TopicCodecsStream.CODECS);
        }

        private void subscribeTopic(Artifact artifact) {
            subscribeTopic(artifact, METHOD);
        }

        private void subscribeTopic(Artifact artifact, MethodName method) {
            var address = org.pragmatica.aether.slice.resource.ResourceAddress.resourceAddress(TOPIC_ADDRESS).unwrap();
            var key = org.pragmatica.aether.slice.kvstore.AetherKey.TopicSubscriptionKey.topicSubscriptionKey(address,
                                                                                                              artifact,
                                                                                                              method);
            var value = org.pragmatica.aether.slice.kvstore.AetherValue.TopicSubscriptionValue.topicSubscriptionValue(SELF);

            topicRegistry.onSubscriptionPut(new ValuePut<>(new KVCommand.Put<>(key, value), Option.none()));
        }

        private StreamConsumerManager topicManager() {
            return StreamConsumerManager.streamConsumerManager(registry,
                                                               capturingRuntime,
                                                               invoker,
                                                               invocationHandler,
                                                               topicAwareCodec,
                                                               ownership,
                                                               placement,
                                                               SELF,
                                                               TopicGroupDeclarationSource.topicGroupDeclarationSource(topicRegistry,
                                                                                                                       name -> ownership.partitionCount(name)
                                                                                                                                        .isPresent()),
                                                               authority(true));
        }

        private void deployDecodingSliceLocally() {
            var sliceCodec = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), List.of(APP_EVENT_CODEC));

            placement.activeOn(SELF);
            when(invocationHandler.localSlice(ARTIFACT)).thenReturn(Option.some(new DecodingBridge(sliceCodec)));
        }

        @Test
        void reconcile_attachesTopicGroup_withVersionStableGroupAndDurableConfig() {
            subscribeTopic(ARTIFACT);
            deployDecodingSliceLocally();
            ownership.ownedBySelf(0, 1, 2, 3);
            topicManager().reconcile();
            assertThat(capturingRuntime.streams()).containsOnly(TOPIC_STREAM);
            assertThat(capturingRuntime.groups()).containsOnly(TOPIC_GROUP);
            assertThat(capturingRuntime.configs()).allSatisfy(config -> {
                assertThat(config.maxRetries()).isEqualTo(5);
                assertThat(config.checkpointInterval().millis()).isEqualTo(500L);
            });
        }

        @Test
        void reconcile_ignoresSubscription_whenTopicStreamDoesNotExist() {
            subscribeTopic(ARTIFACT);
            deployDecodingSliceLocally();
            ownership.ownedBySelf(0, 1, 2, 3);
            ownership.forgetStream();
            topicManager().reconcile();
            assertThat(capturingRuntime.streams()).describedAs("an ephemeral topic never gets a topic:* stream — no declaration, no loop")
                      .isEmpty();
        }

        @Test
        void reconcile_collapsesBlueGreenVersions_toOneGroupLoop() {
            var upgraded = Artifact.artifact("org.example:orders:1.1.0").unwrap();

            subscribeTopic(ARTIFACT);
            subscribeTopic(upgraded);
            deployDecodingSliceLocally();
            when(invocationHandler.localSlice(upgraded)).thenReturn(Option.none());
            ownership.ownedBySelf(0, 1, 2, 3);
            topicManager().reconcile();
            assertThat(capturingRuntime.subscribeCallsPerKey().values()).describedAs("two artifact VERSIONS share the version-stable group; the key-level dedup admits one loop each")
                      .allSatisfy(calls -> assertThat(calls).isEqualTo(1));
            assertThat(capturingRuntime.groups()).containsOnly(TOPIC_GROUP);
        }

        /// The unwrap seam end to end at the delivery boundary: the captured callback receives a
        /// node-codec-encoded [TopicEventEnvelope]; the slice must be handed the APPLICATION payload —
        /// never the envelope — together with the envelope's delivery context (#1295: before it, the
        /// node decoded the payload and invoked with the bare event, so no context ever existed). The
        /// subscribing slice's own bridge decodes the payload bytes.
        @Test
        void delivery_unwrapsEnvelope_andInvokesSliceWithApplicationPayloadAndContext() {
            subscribeTopic(ARTIFACT);
            deployDecodingSliceLocally();
            ownership.ownedBySelf(0);
            ownership.withPartitionCount(1);
            when(invoker.invokeLocalWithContext(any(), any(), any(), any())).thenAnswer(_ -> Promise.unitPromise());
            topicManager().reconcile();
            var sliceCodec = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), List.of(APP_EVENT_CODEC));
            var appPayload = sliceCodec.encode(new AppEvent("order-42"));
            var envelope = new TopicEventEnvelope("msg-1", 1234L, appPayload);

            capturingRuntime.callbackFor(TOPIC_STREAM, 0)
                            .onEvent(3L,
                                     topicAwareCodec.encode(envelope),
                                     1234L)
                            .await()
                            .onFailure(cause -> fail(cause.message()));
            var payload = org.mockito.ArgumentCaptor.forClass(byte[].class);
            var context = org.mockito.ArgumentCaptor.forClass(MessageContext.class);

            org.mockito.Mockito.verify(invoker)
                               .invokeLocalWithContext(org.mockito.ArgumentMatchers.eq(ARTIFACT),
                                                       org.mockito.ArgumentMatchers.eq(METHOD),
                                                       payload.capture(),
                                                       context.capture());
            assertThat(payload.getValue()).isEqualTo(appPayload);
            assertThat(context.getValue()).isEqualTo(MessageContext.messageContext("msg-1", TOPIC_ADDRESS, 0, 3L));
        }

        /// #1295, end to end at the node boundary: a REAL [DurableTopicPublisher]
        /// stamps the envelope's messageId; the manager's dispatch delivers it through a REAL
        /// [DefaultSliceBridge] holding a 1-arg subscriber and a 2-arg subscriber
        /// in the exact adapter shape the slice processor generates, both on the SAME topic. The 2-arg
        /// subscriber must observe the PUBLISHER's messageId with the topic, partition and offset of the
        /// delivery; the 1-arg subscriber must receive the bare event, as before. Before #1295 the 2-arg
        /// subscriber failed every delivery with a ClassCastException and never saw anything.
        @Test
        void delivery_givesTwoArgSubscriberThePublishersMessageId_andOneArgSubscriberTheBareEvent() {
            var sliceCodec = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), List.of(APP_EVENT_CODEC));
            var bare = new AtomicReference<Object>();
            var contextual = new AtomicReference<ContextualEvent>();
            var subscriber = DefaultSliceBridge.defaultSliceBridge(ARTIFACT,
                                                                                                () -> List.of(bareSubscriber(bare),
                                                                                                              contextualSubscriber(contextual)),
                                                                                                sliceCodec);

            subscribeTopic(ARTIFACT, ON_PLACED);
            subscribeTopic(ARTIFACT, ON_PLACED_WITH_CONTEXT);
            deployDecodingSliceLocally();
            ownership.ownedBySelf(0);
            ownership.withPartitionCount(1);
            when(invoker.invokeLocalWithContext(any(), any(), any(), any())).thenAnswer(call -> subscriber.invokeWithContext(call.<MethodName> getArgument(1)
                                                                                                                                  .name(),
                                                                                                                              call.getArgument(2),
                                                                                                                              call.getArgument(3))
                                                                                                                          .mapToUnit());
            topicManager().reconcile();

            var published = publishThroughTheRealPublisher(sliceCodec, new AppEvent("order-42"));

            deliver(ON_PLACED, published, 5L);
            deliver(ON_PLACED_WITH_CONTEXT, published, 5L);

            assertThat(bare.get()).isEqualTo(new AppEvent("order-42"));
            assertThat(contextual.get()).isEqualTo(ContextualEvent.contextualEvent(new AppEvent("order-42"),
                                                                                                                      MessageContext.messageContext(published.messageId(),
                                                                                                                                                                                      TOPIC_ADDRESS,
                                                                                                                                                                                      0,
                                                                                                                                                                                      5L)));
        }

        private void deliver(MethodName method,
                             TopicEventEnvelope envelope,
                             long offset) {
            capturingRuntime.callbackFor(TOPIC_STREAM,
                                         0,
                                         DurableGroupIdentity.groupId(ARTIFACT, method))
                            .onEvent(offset, topicAwareCodec.encode(envelope), 1234L)
                            .await()
                            .onFailure(cause -> fail(method.name() + " delivery failed: "
                                                                                      + cause.message()));
        }

        private static TopicEventEnvelope publishThroughTheRealPublisher(SliceCodec sliceCodec,
                                                                                                            AppEvent event) {
            var captured = new AtomicReference<TopicEventEnvelope>();

            new DurableTopicPublisher<AppEvent>(sliceCodec, capturingPublisher(captured))
                .publish(event)
                .await();

            return captured.get();
        }

        /// #1342 made `publishBatch` abstract; only the single form is exercised here, so the batch form
        /// answers each event as published and is never reached.
        private static StreamPublisher<TopicEventEnvelope> capturingPublisher(AtomicReference<TopicEventEnvelope> captured) {
            return new StreamPublisher<>() {
                @Override
                public Promise<Unit> publish(TopicEventEnvelope envelope) {
                    return capture(captured, envelope);
                }

                @Override
                public Promise<List<PublishOutcome>> publishBatch(List<TopicEventEnvelope> events) {
                    return Promise.success(IntStream.range(0, events.size())
                                                    .<PublishOutcome> mapToObj(PublishOutcome.Published::new)
                                                    .toList());
                }
            };
        }

        private static Promise<Unit> capture(AtomicReference<TopicEventEnvelope> captured,
                                             TopicEventEnvelope envelope) {
            captured.set(envelope);
            return Promise.unitPromise();
        }

        private static SliceMethod<Unit, AppEvent> bareSubscriber(AtomicReference<Object> seen) {
            return new SliceMethod<>(ON_PLACED,
                                                                 event -> record(seen, event),
                                                                 new TypeToken<Unit>() {},
                                                                 new TypeToken<AppEvent>() {});
        }

        /// The exact adapter `FactoryClassGenerator` emits for `onPlacedWithContext(AppEvent, MessageContext)`.
        private static SliceMethod<Unit, ContextualEvent> contextualSubscriber(AtomicReference<ContextualEvent> seen) {
            return new SliceMethod<>(ON_PLACED_WITH_CONTEXT,
                                                                 contextual -> record(seen,
                                                                                      ContextualEvent.contextualEvent((AppEvent) contextual.event(),
                                                                                                                                                        contextual.context())),
                                                                 new TypeToken<Unit>() {},
                                                                 new TypeToken<ContextualEvent>() {});
        }

        private static <T> Promise<Unit> record(AtomicReference<T> seen, T value) {
            seen.set(value);
            return Promise.unitPromise();
        }

        /// #1238: the runtime runs ONE serial delivery loop per (group, partition), so a handler that
        /// never resolves must not hold that partition forever — the declarative path bounds each
        /// invocation, and the timeout surfaces as a delivery failure for the error strategy to handle.
        /// The bound is shortened through the constructor seam; production uses
        /// [StreamConsumerManager.ManagerState#HANDLER_TIMEOUT].
        @Test
        void delivery_failsWithTimeout_whenTheSliceHandlerNeverResolves() throws InterruptedException {
            subscribeTopic(ARTIFACT);
            deployDecodingSliceLocally();
            ownership.ownedBySelf(0);
            ownership.withPartitionCount(1);
            // The durable-topic path invokes through invokeLocalWithContext (#1295); the never-resolving
            // promise is the hung handler this pin bounds.
            when(invoker.invokeLocalWithContext(any(), any(), any(), any())).thenAnswer(_ -> Promise.promise());
            new StreamConsumerManager.ManagerState(registry,
                                                   capturingRuntime,
                                                   invoker,
                                                   invocationHandler,
                                                   topicAwareCodec,
                                                   ownership,
                                                   placement,
                                                   SELF,
                                                   TopicGroupDeclarationSource.topicGroupDeclarationSource(topicRegistry,
                                                                                                           name -> ownership.partitionCount(name)
                                                                                                                            .isPresent()),
                                                   org.pragmatica.lang.io.TimeSpan.timeSpan(200).millis(),
                                                   authority(true)).reconcile();
            var sliceCodec = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), List.of(APP_EVENT_CODEC));
            var envelope = new org.pragmatica.aether.stream.topic.TopicEventEnvelope("msg-1",
                                                                                     1234L,
                                                                                     sliceCodec.encode(new AppEvent("order-42")));
            var settled = new java.util.concurrent.CountDownLatch(1);
            var outcome = new java.util.concurrent.atomic.AtomicReference<Result<Unit>>();

            capturingRuntime.callbackFor(TOPIC_STREAM, 0)
                            .onEvent(0L,
                                     topicAwareCodec.encode(envelope),
                                     1234L)
                            .onResult(result -> {
                                          outcome.set(result);
                                          settled.countDown();
                                      });
            assertThat(settled.await(2, java.util.concurrent.TimeUnit.SECONDS)).describedAs("a hung handler must end its delivery, not hold the partition's loop forever")
                      .isTrue();
            assertThat(outcome.get().isFailure()).describedAs("a timed-out invocation is a delivery failure")
                      .isTrue();
        }

        /// rev1285d F2: the declarative `[streams.X]` twin of the pin above. That pin moved to
        /// `invokeLocalWithContext` with #1295, which left `invokeConsumer`'s own `.timeout(handlerTimeout)`
        /// on the `invokeLocal` path unpinned — removing it kept all 1,533 node tests green. Same seam,
        /// same 200ms bound, the never-resolving promise stubbed on `invokeLocal` instead.
        @Test
        void delivery_failsWithTimeout_whenTheDeclarativeSliceHandlerNeverResolves() throws InterruptedException {
            declare(APP_EVENT_TYPE, false);
            deployDecodingSliceLocally();
            ownership.ownedBySelf(0);
            ownership.withPartitionCount(1);
            when(invoker.invokeLocal(any(), any(), any(), any())).thenAnswer(_ -> Promise.promise());
            new StreamConsumerManager.ManagerState(registry,
                                                   capturingRuntime,
                                                   invoker,
                                                   invocationHandler,
                                                   topicAwareCodec,
                                                   ownership,
                                                   placement,
                                                   SELF,
                                                   TopicGroupDeclarationSource.none(),
                                                   org.pragmatica.lang.io.TimeSpan.timeSpan(200).millis(),
                                                   authority(true)).reconcile();
            var sliceCodec = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), List.of(APP_EVENT_CODEC));
            var settled = new java.util.concurrent.CountDownLatch(1);
            var outcome = new java.util.concurrent.atomic.AtomicReference<Result<Unit>>();

            assertThat(capturingRuntime.callbackFor(STREAM, 0)).describedAs("control: the declarative consumer attached to orders[0]")
                      .isNotNull();
            capturingRuntime.callbackFor(STREAM, 0)
                            .onEvent(0L, sliceCodec.encode(new AppEvent("order-42")), 1234L)
                            .onResult(result -> {
                                          outcome.set(result);
                                          settled.countDown();
                                      });
            assertThat(settled.await(2, java.util.concurrent.TimeUnit.SECONDS)).describedAs("a hung declarative handler must end its delivery, not hold the partition's loop forever")
                      .isTrue();
            assertThat(outcome.get().isFailure()).describedAs("a timed-out declarative invocation is a delivery failure")
                      .isTrue();
            verify(invoker).invokeLocal(any(), any(), any(), any());
        }

        private record DecodingBridge(SliceCodec codec) implements SliceBridge {
            @Override
            public Option<SliceCodec> sliceCodec() {
                return Option.some(codec);
            }

            @Override
            public Promise<Object> decode(byte[] bytes) {
                return Result.lift(() -> codec.<Object> decode(bytes)).async();
            }

            @Override
            public Promise<byte[]> invoke(String methodName, byte[] input) {
                return Promise.success(input);
            }

            @Override
            public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override
            public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override
            public ClassLoader classLoader() {
                return StreamConsumerManagerTest.class.getClassLoader();
            }

            @Override
            public List<String> methodNames() {
                return List.of(METHOD.name());
            }

            @Override
            public Option<ObservabilityStrategyCell> observabilityCell(String methodName) {
                return Option.none();
            }
        }

        private static final class CapturingRuntime implements StreamConsumerRuntime {
            private final Map<String, ConsumerCallback> callbacks = new ConcurrentHashMap<>();
            private final Map<String, ConsumerCallback> callbacksByGroup = new ConcurrentHashMap<>();
            private final Map<String, ConsumerConfig> byKey = new ConcurrentHashMap<>();
            private final Map<String, Integer> subscribeCalls = new ConcurrentHashMap<>();

            ConsumerCallback callbackFor(String streamName, int partition) {
                return callbacks.get(streamName + "[" + partition + "]");
            }

            /// Two topic groups share one `stream[partition]` key; this resolves a group's own callback.
            ConsumerCallback callbackFor(String streamName, int partition, String groupId) {
                return callbacksByGroup.get(streamName + "[" + partition + "]#" + groupId);
            }

            List<String> streams() {
                return byKey.keySet()
                            .stream()
                            .map(key -> key.substring(0,
                                                      key.indexOf('[')))
                            .distinct()
                            .toList();
            }

            List<String> groups() {
                return byKey.values()
                            .stream()
                            .map(ConsumerConfig::groupId)
                            .distinct()
                            .toList();
            }

            List<ConsumerConfig> configs() {
                return List.copyOf(byKey.values());
            }

            Map<String, Integer> subscribeCallsPerKey() {
                return Map.copyOf(subscribeCalls);
            }

            @Override
            public Result<Unit> subscribe(String streamName,
                                          int partition,
                                          ConsumerConfig config,
                                          ConsumerCallback callback) {
                return subscribe(streamName, partition, config, callback, IdlePolicy.REAP_WHEN_IDLE);
            }

            @Override
            public Result<Unit> subscribe(String streamName,
                                          int partition,
                                          ConsumerConfig config,
                                          ConsumerCallback callback,
                                          IdlePolicy idlePolicy) {
                var key = streamName + "[" + partition + "]";

                subscribeCalls.merge(key, 1, Integer::sum);
                callbacks.put(key, callback);
                callbacksByGroup.put(key + "#" + config.groupId(), callback);
                byKey.put(key, config);

                return Result.unitResult();
            }

            @Override
            public Result<Unit> unsubscribe(String streamName, int partition, String consumerGroup) {
                var key = streamName + "[" + partition + "]";

                callbacks.remove(key);
                byKey.remove(key);

                return Result.unitResult();
            }

            @Override
            public Option<Long> cursorPosition(String streamName, int partition, String consumerGroup) {
                return Option.none();
            }

            @Override
            public Option<TransactionalCursorCommit> transactionalCursorCommit() {
                return Option.none();
            }

            @Override
            public DeadLetterHandler deadLetterHandler() {
                return DeadLetterHandler.deadLetterHandler();
            }

            @Override
            public List<SubscriptionSnapshot> subscriptions() {
                return List.of();
            }

            @Override
            public long cursorCommitFailureCount() {
                return 0;
            }

            @Override
            public void close() {}
        }
    }

    private record StubBridge(Option<SliceCodec> codec) implements SliceBridge {
        @Override
        public Option<SliceCodec> sliceCodec() {
            return codec;
        }

        @Override
        public Promise<byte[]> invoke(String methodName, byte[] input) {
            return Promise.success(input);
        }

        @Override
        public Promise<Unit> start() {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @Override
        public ClassLoader classLoader() {
            return StreamConsumerManagerTest.class.getClassLoader();
        }

        @Override
        public List<String> methodNames() {
            return List.of(METHOD.name());
        }

        @Override
        public Option<ObservabilityStrategyCell> observabilityCell(String methodName) {
            return Option.none();
        }
    }
}
