// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.node.stream.StreamConsumerManager.PartitionAssignment;
import org.pragmatica.aether.node.stream.StreamConsumerManager.PartitionOwnership;
import org.pragmatica.aether.node.stream.StreamConsumerManager.SlicePlacement;
import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.ObservabilityStrategyCell;
import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamRegistrationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamRegistrationValue;
import org.pragmatica.aether.stream.DeadLetterHandler;
import org.pragmatica.aether.stream.StreamConsumerRuntime;
import org.pragmatica.aether.stream.consumer.TransactionalCursorCommit;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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

    private StreamConsumerRegistry registry;
    private RecordingRuntime runtime;
    private InvocationHandler invocationHandler;
    private SliceInvoker invoker;
    private MutableOwnership ownership;
    private MutablePlacement placement;

    @BeforeEach
    void setUp() {
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
        return StreamConsumerManager.streamConsumerManager(registry,
                                                           consumerRuntime,
                                                           invoker,
                                                           invocationHandler,
                                                           FrameworkCodecs.frameworkCodecs(),
                                                           ownership,
                                                           placement,
                                                           self);
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

        @Test
        void statuses_areEmpty_whenNothingDeclared() {
            assertThat(manager().statuses()).isEmpty();
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
    /// observable without a partition manager.
    private static final class RecordingRuntime implements StreamConsumerRuntime {
        private final Map<Integer, String> subscriptions = new ConcurrentHashMap<>();
        private int subscribeCalls;

        List<Integer> subscribedPartitions() {
            return List.copyOf(subscriptions.keySet());
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
            subscriptions.put(partition, config.groupId());

            return Result.unitResult();
        }

        @Override
        public Result<Unit> unsubscribe(String streamName, int partition, String consumerGroup) {
            subscriptions.remove(partition);

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
                                .map(entry -> new SubscriptionSnapshot(STREAM,
                                                                       entry.getKey(),
                                                                       entry.getValue(),
                                                                       0L,
                                                                       false,
                                                                       IdlePolicy.KEEP_UNTIL_UNSUBSCRIBED,
                                                                       Option.none()))
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
            var address = org.pragmatica.aether.slice.resource.ResourceAddress.resourceAddress(TOPIC_ADDRESS).unwrap();
            var key = org.pragmatica.aether.slice.kvstore.AetherKey.TopicSubscriptionKey.topicSubscriptionKey(address,
                                                                                                              artifact,
                                                                                                              METHOD);
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
                                                                                                                                        .isPresent()));
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
        /// node-codec-encoded [TopicEventEnvelope]; the slice's invoker must see the DECODED
        /// application payload — never the envelope, never raw bytes.
        @Test
        void delivery_unwrapsEnvelope_andInvokesSliceWithApplicationPayload() {
            subscribeTopic(ARTIFACT);
            deployDecodingSliceLocally();
            ownership.ownedBySelf(0);
            ownership.withPartitionCount(1);
            when(invoker.invokeLocal(any(), any(), any(), any())).thenAnswer(_ -> Promise.unitPromise());
            topicManager().reconcile();
            var appEvent = new AppEvent("order-42");
            var sliceCodec = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), List.of(APP_EVENT_CODEC));
            var envelope = new org.pragmatica.aether.stream.topic.TopicEventEnvelope("msg-1",
                                                                                     1234L,
                                                                                     sliceCodec.encode(appEvent));

            capturingRuntime.callbackFor(TOPIC_STREAM, 0)
                            .onEvent(0L,
                                     topicAwareCodec.encode(envelope),
                                     1234L)
                            .await()
                            .onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));
            var payload = org.mockito.ArgumentCaptor.forClass(Object.class);

            org.mockito.Mockito.verify(invoker)
                               .invokeLocal(org.mockito.ArgumentMatchers.eq(ARTIFACT),
                                            org.mockito.ArgumentMatchers.eq(METHOD),
                                            payload.capture(),
                                            any());
            assertThat(payload.getValue()).isEqualTo(appEvent);
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
            private final Map<String, ConsumerConfig> byKey = new ConcurrentHashMap<>();
            private final Map<String, Integer> subscribeCalls = new ConcurrentHashMap<>();

            ConsumerCallback callbackFor(String streamName, int partition) {
                return callbacks.get(streamName + "[" + partition + "]");
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
