// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.stream;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.node.stream.StreamConsumerRegistry.ConsumerDeclaration;
import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.stream.StreamConsumerRuntime;
import org.pragmatica.aether.stream.StreamConsumerRuntime.ConsumerCallback;
import org.pragmatica.aether.stream.StreamConsumerRuntime.IdlePolicy;
import org.pragmatica.aether.stream.replication.ReplicaPlacement;
import org.pragmatica.aether.stream.topic.DurableGroupIdentity;
import org.pragmatica.aether.stream.topic.DurableTopicNames;
import org.pragmatica.aether.stream.topic.TopicEventEnvelope;
import org.pragmatica.aether.stream.replication.ReplicaPlacement.Placement;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.serialization.SliceCodec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Drives declarative `[streams.X]` consumers (#488): turns the cluster-wide registration written by
/// `NodeDeploymentState` into an actual delivery loop on this node.
///
/// ## Which node consumes which partition
///
/// Exactly ONE node is assigned per `(stream, partition, consumer group)`. Given the candidate set
/// `C` — the nodes where the declaring artifact is `ACTIVE`, intersected with the live member view —
/// this node consumes partition `P` of stream `S` iff it is the assignee, which is:
///   1. the HRW **owner** of `(S, P)` when the owner is in `C`, else
///   2. the HRW pick over `C` itself, via the same [ReplicaPlacement#place] function stream ownership
///      uses, ranking `C` instead of cluster membership.
///
/// Rule 1 alone was the #488 model, and it is why a default deployment delivered NOTHING (#535):
/// delivery required `partition owner ∩ slice-bearing` to be non-empty, and at default replication
/// (slice on 3 of 5 nodes) that intersection is usually EMPTY. Rule 2 is the repair, and it is a
/// strict EXTENSION — where rule 1 already picked a node, the assignment is unchanged, so the
/// co-located case keeps local reads and push delivery exactly as live-validated.
///
/// An assignee that is not the owner does not hold the partition's ring. It reads THROUGH the owner:
/// the node wires `StreamConsumerRuntime.PartitionReader` to the routed reader, whose `GOVERNOR`
/// preference reads local and forwards to the HRW owner on `PARTITION_NOT_LOCAL`. Consuming wherever
/// the ring happens to be materialized would NOT work in its place — REPLICA-role nodes materialize
/// rings too, so that would deliver every event once per replica.
///
/// There is no role-change callback available to a third party — the `onBecameReplica` and
/// `onReconcilePassComplete` seams are single-consumer and already taken — so both inputs are polled
/// by [#reconcile], driven from the node's periodic task list.
///
/// ## Guarantee
///
/// **At-least-once delivery per partition, conditional on the slice being ACTIVE on at least one live
/// node.** Duplicates arise from redelivery after a handler failure under `RETRY`; from the
/// reconcile-tick window during an ownership or placement change, in which the old and new assignee
/// may both deliver; and from resuming at the last checkpoint (≤1000 events or ≤30s of progress)
/// rather than the last delivered offset after an UNGRACEFUL move — a graceful detach flushes the
/// exact cursor. Replay after an ungraceful move is bounded by that checkpoint cadence. This is NOT
/// effectively-once: there is no fencing token on delivery, and two transiently-divergent assignment
/// views can both deliver and both write the cursor, last write winning.
///
/// Delivery is ZERO only when the slice is ACTIVE on no live node — nothing can run the handler. That
/// case is reported loudly and appears in [#statuses] as `unassignedPartitions`.
public interface StreamConsumerManager {
    /// Re-evaluate what this node should consume and apply the difference. Idempotent; safe to call
    /// from the periodic tick and from a registration change.
    @Contract
    void reconcile();

    /// Unsubscribe everything (flushing cursors) — called from node stop, before the partition
    /// manager closes.
    @Contract
    void stop();

    List<ConsumerStatus> statuses();
    int activeSubscriptionCount();

    /// #654: node-wide count of cursor commits (final flush at detach, or periodic checkpoint) that
    /// failed or did not settle within their shutdown bound. Delegates to the underlying
    /// [org.pragmatica.aether.stream.StreamConsumerRuntime#cursorCommitFailureCount].
    long cursorCommitFailureCount();

    /// Inert manager for `ManageableNode` proxies that have no stream runtime behind them. Reports
    /// no consumers; it never fabricates any.
    static StreamConsumerManager inactive() {
        record inactive() implements StreamConsumerManager {
            @Contract
            @Override
            public void reconcile() {}

            @Contract
            @Override
            public void stop() {}

            @Override
            public List<ConsumerStatus> statuses() {
                return List.of();
            }

            @Override
            public int activeSubscriptionCount() {
                return 0;
            }

            @Override
            public long cursorCommitFailureCount() {
                return 0;
            }
        }

        return new inactive();
    }

    /// Partition ownership as this node currently sees it. A seam so the assignment decision is
    /// testable without a cluster.
    interface PartitionOwnership {
        /// Declared partition count for the stream, or empty when this node does not know the stream
        /// yet (a transient startup state, not an error).
        Option<Integer> partitionCount(String streamName);
        /// The HRW owner of the partition, or empty during the bootstrap window before placement is
        /// known.
        Option<NodeId> ownerOf(String streamName, int partition);
        /// The live member view. DELIBERATELY the same view stream ownership itself is computed from,
        /// so the ownership half and the placement half of an assignment cannot disagree about which
        /// nodes are alive. Empty means membership has not resolved yet — a transient startup state in
        /// which no assignment is made and no gap is reported.
        List<NodeId> liveMembers();
    }

    /// Where a slice is deployed, cluster-wide, as this node currently sees it (#535).
    ///
    /// Mirrored locally from committed `NodeArtifactKey` state, so answering needs no consensus round
    /// and no leader — every node computes the same assignment from the same mirrored inputs, and a
    /// departed node drops out of the candidate set without anything having to evict it.
    ///
    /// Returns the full per-node STATE rather than just the usable nodes, because "no node can consume
    /// this" and "no node can consume this YET" are different facts and only the second is normal. A
    /// deployment in progress would otherwise raise the same alarm as a slice that is genuinely
    /// nowhere.
    @FunctionalInterface
    interface SlicePlacement {
        Map<NodeId, SliceState> placement(Artifact artifact);
    }

    /// `lastCursorCommitFailure` (#654): detail of this partition's most recent cursor commit
    /// failure while the consumer stays attached; [Option#none] once it commits successfully again,
    /// or if none has failed. Sourced from
    /// [org.pragmatica.aether.stream.StreamConsumerRuntime.SubscriptionSnapshot#lastCursorCommitFailure].
    record PartitionCursor(int partition, long cursor, boolean stalled, Option<String> lastCursorCommitFailure) {}

    /// Who consumes one partition, and who owns it. Both are computed locally and identically on every
    /// node, so a single call to any node answers "who consumes partition 3, and does it read locally?"
    /// — the answer is `consumerNode`, and reads are forwarded whenever it differs from `ownerNode`.
    /// Either is empty during the bootstrap window, or `consumerNode` when nothing can consume.
    record PartitionAssignment(int partition, Option<NodeId> consumerNode, Option<NodeId> ownerNode) {}

    /// Operator-visible state of one declared consumer on this node. Pure snapshot.
    ///
    /// `eventTypePublishable` is [Option#none] when this node cannot know — the probe needs the slice's
    /// own codec registry, which only a node hosting the slice has. Reporting `false` there would be a
    /// fabricated value, not a degenerate one.
    record ConsumerStatus(String streamName,
                          String configSection,
                          String artifact,
                          String methodName,
                          String consumerGroup,
                          boolean batchMode,
                          String eventType,
                          boolean sliceDeployedLocally,
                          Option<Boolean> eventTypePublishable,
                          List<PartitionCursor> assignedPartitions,
                          List<Integer> unassignedPartitions,
                          List<PartitionAssignment> partitionAssignments,
                          Option<String> diagnostic) {}

    static StreamConsumerManager streamConsumerManager(StreamConsumerRegistry registry,
                                                       StreamConsumerRuntime runtime,
                                                       SliceInvoker invoker,
                                                       InvocationHandler invocationHandler,
                                                       SliceCodec nodeCodec,
                                                       PartitionOwnership ownership,
                                                       SlicePlacement placement,
                                                       NodeId self) {
        return streamConsumerManager(registry,
                                     runtime,
                                     invoker,
                                     invocationHandler,
                                     nodeCodec,
                                     ownership,
                                     placement,
                                     self,
                                     TopicGroupDeclarationSource.none());
    }

    /// #386 registration entry point (option-(a) ruling): durable-topic groups join the reconcile
    /// as synthesized declarations from `topicGroups`. An empty source reproduces the 8-arg
    /// behavior exactly — placement, assignment, and failover logic are untouched by construction.
    static StreamConsumerManager streamConsumerManager(StreamConsumerRegistry registry,
                                                       StreamConsumerRuntime runtime,
                                                       SliceInvoker invoker,
                                                       InvocationHandler invocationHandler,
                                                       SliceCodec nodeCodec,
                                                       PartitionOwnership ownership,
                                                       SlicePlacement placement,
                                                       NodeId self,
                                                       TopicGroupDeclarationSource topicGroups) {
        var manager = new ManagerState(registry,
                                       runtime,
                                       invoker,
                                       invocationHandler,
                                       nodeCodec,
                                       ownership,
                                       placement,
                                       self,
                                       topicGroups);

        registry.setChangeListener(manager::onDeclarationChange);

        return manager;
    }

    final class ManagerState implements StreamConsumerManager {
        private static final Logger log = LoggerFactory.getLogger(StreamConsumerManager.class);

        private static final TypeToken<Unit> UNIT_TYPE_TOKEN = new TypeToken<>() {};

        private final StreamConsumerRegistry registry;
        private final StreamConsumerRuntime runtime;
        private final SliceInvoker invoker;
        private final InvocationHandler invocationHandler;
        private final SliceCodec nodeCodec;
        private final PartitionOwnership ownership;
        private final SlicePlacement placement;
        private final NodeId self;
        private final TopicGroupDeclarationSource topicGroups;
        private final Map<SubscriptionKey, ConsumerDeclaration> active = new ConcurrentHashMap<>();
        private final Map<String, Diagnosis> diagnoses = new ConcurrentHashMap<>();

        ManagerState(StreamConsumerRegistry registry,
                     StreamConsumerRuntime runtime,
                     SliceInvoker invoker,
                     InvocationHandler invocationHandler,
                     SliceCodec nodeCodec,
                     PartitionOwnership ownership,
                     SlicePlacement placement,
                     NodeId self,
                     TopicGroupDeclarationSource topicGroups) {
            this.registry = registry;
            this.runtime = runtime;
            this.invoker = invoker;
            this.invocationHandler = invocationHandler;
            this.nodeCodec = nodeCodec;
            this.ownership = ownership;
            this.placement = placement;
            this.self = self;
            this.topicGroups = topicGroups;
        }

        private void onDeclarationChange(Object key, Option<ConsumerDeclaration> declaration) {
            reconcile();
        }

        @Contract
        @Override
        public void reconcile() {
            var desired = allDeclarations().stream().flatMap(declaration -> desiredFor(declaration).stream()).toList();

            desired.forEach(this::subscribeIfAbsent);
            dropStale(desired);
        }

        /// Subscriptions this node SHOULD hold for one declaration, plus the loud reporting for the
        /// ways a declaration can end up consuming nothing.
        private List<SubscriptionKey> desiredFor(ConsumerDeclaration declaration) {
            var assignments = assignmentsFor(declaration);
            var bridge = invocationHandler.localSlice(declaration.artifact());

            recordDiagnosis(declaration, diagnose(declaration, assignments, bridge));

            return bridge.isPresent()
                   ? subscriptionKeys(declaration, minePartitions(assignments))
                   : unsubscribeAndDropAll(declaration);
        }

        /// The full partition→consumer map for a declaration, computed from the two mirrored inputs.
        /// Empty when the stream's config or the member view has not reached this node yet — both are
        /// transient startup states in which no assignment is made and no gap is claimed.
        private List<PartitionAssignment> assignmentsFor(ConsumerDeclaration declaration) {
            var candidates = candidateNodes(declaration);

            return ownership.partitionCount(declaration.streamName())
                            .filter(_ -> !ownership.liveMembers()
                                                   .isEmpty())
                            .map(count -> assignEachPartition(declaration, count, candidates))
                            .or(List.of());
        }

        private List<PartitionAssignment> assignEachPartition(ConsumerDeclaration declaration,
                                                              int count,
                                                              List<NodeId> candidates) {
            return IntStream.range(0, count)
                            .mapToObj(partition -> assignPartition(declaration, partition, candidates))
                            .toList();
        }

        /// The assignee for one partition: the HRW owner when it can run the slice, else the HRW pick
        /// over the nodes that can. Ranking the candidate set with the SAME function stream ownership
        /// uses keeps the choice deterministic and identical on every node.
        private PartitionAssignment assignPartition(ConsumerDeclaration declaration,
                                                    int partition,
                                                    List<NodeId> candidates) {
            var owner = ownership.ownerOf(declaration.streamName(), partition);
            var consumer = owner.filter(candidates::contains)
                                .orElse(() -> ReplicaPlacement.place(declaration.streamName(),
                                                                     partition,
                                                                     candidates,
                                                                     1)
                                                              .map(Placement::owner));

            return new PartitionAssignment(partition, consumer, owner);
        }

        /// Nodes that can actually run this consumer: the artifact is ACTIVE there AND the node is in
        /// the live member view. `ACTIVE` and not merely "deployed" — a node still LOADING cannot
        /// invoke, and assigning to it would consume nothing while looking healthy.
        private List<NodeId> candidateNodes(ConsumerDeclaration declaration) {
            var live = Set.copyOf(ownership.liveMembers());

            return placement.placement(declaration.artifact())
                            .entrySet()
                            .stream()
                            .filter(entry -> entry.getValue() == SliceState.ACTIVE)
                            .map(Map.Entry::getKey)
                            .filter(live::contains)
                            .toList();
        }

        /// True when the slice is deployed SOMEWHERE but has not reached `ACTIVE` on any node yet — a
        /// deployment in flight, not a gap. Distinguishing the two is what keeps a normal deploy from
        /// emitting the same alarm as a slice that is genuinely nowhere.
        private boolean deploymentPending(ConsumerDeclaration declaration) {
            return ! placement.placement(declaration.artifact())
                              .isEmpty();
        }

        private List<Integer> minePartitions(List<PartitionAssignment> assignments) {
            return assignments.stream()
                              .filter(assignment -> assignment.consumerNode()
                                                              .map(self::equals)
                                                              .or(false))
                              .map(PartitionAssignment::partition)
                              .toList();
        }

        private static List<Integer> unassignedPartitions(List<PartitionAssignment> assignments) {
            return assignments.stream()
                              .filter(assignment -> assignment.consumerNode()
                                                              .isEmpty())
                              .map(PartitionAssignment::partition)
                              .toList();
        }

        /// Partitions this node consumes but does NOT own — its reads are forwarded to the owner.
        ///
        /// Requires the owner to be KNOWN. An unresolved owner is the bootstrap window, not a
        /// forwarding condition: reporting it as forwarded would raise an operator-visible claim about
        /// where reads are going before anything knows where that is.
        private List<Integer> forwardedPartitions(List<PartitionAssignment> assignments) {
            return assignments.stream()
                              .filter(assignment -> assignment.consumerNode()
                                                              .map(self::equals)
                                                              .or(false))
                              .filter(assignment -> assignment.ownerNode()
                                                              .map(owner -> !owner.equals(self))
                                                              .or(false))
                              .map(PartitionAssignment::partition)
                              .toList();
        }

        private List<SubscriptionKey> unsubscribeAndDropAll(ConsumerDeclaration declaration) {
            unsubscribeAllFor(declaration);

            return List.of();
        }

        private Diagnosis diagnose(ConsumerDeclaration declaration,
                                   List<PartitionAssignment> assignments,
                                   Option<SliceBridge> bridge) {
            return Diagnosis.diagnosis(declaration,
                                       assignments,
                                       unassignedPartitions(assignments),
                                       forwardedPartitions(assignments),
                                       bridge.map(loaded -> publishable(declaration, loaded)),
                                       deploymentPending(declaration));
        }

        private static List<SubscriptionKey> subscriptionKeys(ConsumerDeclaration declaration, List<Integer> mine) {
            return mine.stream()
                       .map(partition -> new SubscriptionKey(declaration.streamName(),
                                                             partition,
                                                             declaration.consumerGroup()))
                       .toList();
        }

        /// Can this event type actually be PUBLISHED to the stream? Answered against the codec the
        /// slice's own stream resources use — its slice codec, which layers the application's
        /// declared types over the node codec (#526). Before slice-scoped codecs this had to consult
        /// the node codec, which knew framework types only and therefore reported every
        /// application-defined event as unpublishable. A bridge carrying no codec of its own falls
        /// back to the node codec, which is then genuinely the codec in play.
        private boolean publishable(ConsumerDeclaration declaration, SliceBridge bridge) {
            return Result.lift(() -> Class.forName(declaration.eventType(),
                                                   false,
                                                   bridge.classLoader()))
                         .map(eventClass -> knownToCodec(bridge, eventClass))
                         .or(false);
        }

        private boolean knownToCodec(SliceBridge bridge, Class<?> eventClass) {
            return Result.lift(() -> bridge.sliceCodec()
                                           .or(nodeCodec)
                                           .lookupByClass(eventClass)).isSuccess();
        }

        private void recordDiagnosis(ConsumerDeclaration declaration, Diagnosis diagnosis) {
            var identity = declarationIdentity(declaration);
            var previous = Option.option(diagnoses.put(identity, diagnosis));

            if (!previous.map(diagnosis::equals).or(false)) {
                diagnosis.log();
            }
        }

        private void subscribeIfAbsent(SubscriptionKey key) {
            declarationFor(key).onPresent(declaration -> attach(key, declaration));
        }

        /// The ONE declaration-resolution point (#386 seam): declarative registrations UNION the
        /// synthesized durable-topic declarations. Every consumer of "what declarations exist" —
        /// the reconcile's desired set and the key->declaration re-resolution below — reads this,
        /// so a source declaration can never be computed into a subscription key and then dropped
        /// on re-resolution.
        private List<ConsumerDeclaration> allDeclarations() {
            return Stream.concat(registry.allDeclarations().stream(),
                                 topicGroups.declarations().stream())
                         .toList();
        }

        private Option<ConsumerDeclaration> declarationFor(SubscriptionKey key) {
            return allDeclarations().stream()
                                  .filter(declaration -> declaration.streamName()
                                                                    .equals(key.streamName()) && declaration.consumerGroup()
                                                                                                            .equals(key.consumerGroup()))
                                  .findFirst()
                                  .map(Option::some)
                                  .orElseGet(Option::none);
        }

        private void attach(SubscriptionKey key, ConsumerDeclaration declaration) {
            if (active.putIfAbsent(key, declaration) != null) {
                return;
            }

            invocationHandler.localSlice(declaration.artifact())
                             .onPresent(bridge -> doSubscribe(key, declaration, bridge))
                             .onEmpty(() -> active.remove(key));
        }

        private void doSubscribe(SubscriptionKey key, ConsumerDeclaration declaration, SliceBridge bridge) {
            runtime.subscribe(key.streamName(),
                              key.partition(),
                              consumerConfigFor(key, declaration),
                              callbackFor(declaration, bridge),
                              IdlePolicy.KEEP_UNTIL_UNSUBSCRIBED)
                   .onSuccess(_ -> logAttached(key, declaration))
                   .onFailure(cause -> failAttach(key, cause));
        }

        private static void logAttached(SubscriptionKey key, ConsumerDeclaration declaration) {
            log.info("Declarative stream consumer attached: {}[{}] -> {}.{} (group={}, batch={})",
                     key.streamName(),
                     key.partition(),
                     declaration.artifact(),
                     declaration.methodName().name(),
                     declaration.consumerGroup(),
                     declaration.batchMode());
            if (declaration.batchMode()) {
                log.info("Batch consumer {}.{} receives SINGLETON batches in this release — the declared List<T> parameter is delivered one event at a time; true batching is not yet implemented",
                         declaration.artifact(),
                         declaration.methodName().name());
            }
        }

        private void failAttach(SubscriptionKey key, Cause cause) {
            active.remove(key);
            log.error("Declarative stream consumer FAILED to attach: {}[{}] group={}: {}",
                      key.streamName(),
                      key.partition(),
                      key.consumerGroup(),
                      cause.message());
        }

        private ConsumerCallback callbackFor(ConsumerDeclaration declaration, SliceBridge bridge) {
            return (_, payload, _) -> deliver(declaration, bridge, payload);
        }

        /// Durable-topic groups take the spec-cadence config (durable-pubsub-spec §6/§7 — 5
        /// attempts, 500ms checkpoint); declarative consumers keep the defaults they always had.
        private static ConsumerConfig consumerConfigFor(SubscriptionKey key, ConsumerDeclaration declaration) {
            return DurableTopicNames.isTopicStream(key.streamName())
                   ? DurableGroupIdentity.consumerConfig(declaration.consumerGroup())
                   : ConsumerConfig.consumerConfig(declaration.consumerGroup());
        }

        private Promise<Unit> deliver(ConsumerDeclaration declaration, SliceBridge bridge, byte[] payload) {
            return DurableTopicNames.isTopicStream(declaration.streamName())
                   ? deliverTopicEvent(declaration, bridge, payload)
                   : bridge.decode(payload)
                           .flatMap(event -> invokeConsumer(declaration, event));
        }

        /// Durable-topic events arrive wrapped (durable-pubsub-spec §8): the [TopicEventEnvelope]
        /// is a NODE-codec system type (tag-pinned), decoded here; the PAYLOAD inside it was
        /// encoded by the PUBLISHING slice's codec and is decoded with the SUBSCRIBING slice's
        /// bridge, the same cross-slice type contract every RPC message already rides. The
        /// handler's promise is the ack — nothing else about delivery differs from a declarative
        /// consumer, which is the point of the option-(a) reuse.
        private Promise<Unit> deliverTopicEvent(ConsumerDeclaration declaration, SliceBridge bridge, byte[] rawEvent) {
            return Result.lift(() -> nodeCodec.<TopicEventEnvelope> decode(rawEvent))
                         .async()
                         .flatMap(envelope -> bridge.decode(envelope.payload()))
                         .flatMap(event -> invokeConsumer(declaration, event));
        }

        private Promise<Unit> invokeConsumer(ConsumerDeclaration declaration, Object event) {
            return invoker.invokeLocal(declaration.artifact(),
                                       declaration.methodName(),
                                       payloadFor(declaration, event),
                                       UNIT_TYPE_TOKEN)
                          .mapToUnit();
        }

        /// A batch-declared method takes `List<T>`; it is handed a singleton list. That satisfies the
        /// declared contract (a batch consumer must accept any batch size) without pretending true
        /// batching exists — see the attach-time log line.
        private static Object payloadFor(ConsumerDeclaration declaration, Object event) {
            return declaration.batchMode()
                   ? List.of(event)
                   : event;
        }

        private void dropStale(List<SubscriptionKey> desired) {
            active.keySet().stream().filter(key -> !desired.contains(key)).toList().forEach(this::detach);
        }

        private void unsubscribeAllFor(ConsumerDeclaration declaration) {
            active.keySet()
                  .stream()
                  .filter(key -> key.consumerGroup()
                                    .equals(declaration.consumerGroup()))
                  .toList()
                  .forEach(this::detach);
        }

        private void detach(SubscriptionKey key) {
            active.remove(key);
            runtime.unsubscribe(key.streamName(),
                                key.partition(),
                                key.consumerGroup())
                   .onSuccess(_ -> log.info("Declarative stream consumer detached: {}[{}] group={}",
                                            key.streamName(),
                                            key.partition(),
                                            key.consumerGroup()))
                   .onFailure(cause -> log.debug("Detach of {}[{}] group={} reported: {}",
                                                 key.streamName(),
                                                 key.partition(),
                                                 key.consumerGroup(),
                                                 cause.message()));
        }

        @Contract
        @Override
        public void stop() {
            active.keySet().stream().toList().forEach(this::detach);
            diagnoses.clear();
            log.info("Declarative stream consumer manager stopped");
        }

        @Override
        public int activeSubscriptionCount() {
            return active.size();
        }

        @Override
        public long cursorCommitFailureCount() {
            return runtime.cursorCommitFailureCount();
        }

        @Override
        public List<ConsumerStatus> statuses() {
            var cursors = cursorsByKey();

            return registry.allDeclarations()
                           .stream()
                           .map(declaration -> statusOf(declaration, cursors))
                           .toList();
        }

        private Map<SubscriptionKey, PartitionCursor> cursorsByKey() {
            return runtime.subscriptions()
                          .stream()
                          .collect(Collectors.toMap(snapshot -> new SubscriptionKey(snapshot.streamName(),
                                                                                    snapshot.partition(),
                                                                                    snapshot.consumerGroup()),
                                                    snapshot -> new PartitionCursor(snapshot.partition(),
                                                                                    snapshot.cursor(),
                                                                                    snapshot.stalled(),
                                                                                    snapshot.lastCursorCommitFailure()),
                                                    (first, _) -> first));
        }

        private ConsumerStatus statusOf(ConsumerDeclaration declaration,
                                        Map<SubscriptionKey, PartitionCursor> cursors) {
            var diagnosis = Option.option(diagnoses.get(declarationIdentity(declaration)));
            var assigned = assignedCursors(declaration, cursors);

            return new ConsumerStatus(declaration.streamName(),
                                      declaration.configSection(),
                                      declaration.artifact().asString(),
                                      declaration.methodName().name(),
                                      declaration.consumerGroup(),
                                      declaration.batchMode(),
                                      declaration.eventType(),
                                      diagnosis.map(Diagnosis::deployedLocally).or(false),
                                      diagnosis.flatMap(Diagnosis::eventTypePublishable),
                                      assigned,
                                      diagnosis.map(Diagnosis::unassignedPartitions).or(List.of()),
                                      diagnosis.map(Diagnosis::assignments).or(List.of()),
                                      diagnosis.flatMap(Diagnosis::message));
        }

        private List<PartitionCursor> assignedCursors(ConsumerDeclaration declaration,
                                                      Map<SubscriptionKey, PartitionCursor> cursors) {
            return active.keySet()
                         .stream()
                         .filter(key -> key.consumerGroup()
                                           .equals(declaration.consumerGroup()))
                         .map(cursors::get)
                         .filter(Objects::nonNull)
                         .sorted(Comparator.comparingInt(PartitionCursor::partition))
                         .toList();
        }

        private static String declarationIdentity(ConsumerDeclaration declaration) {
            return declaration.streamName()
                 + "/" + declaration.artifact()
                                    .asString()
                 + "/" + declaration.methodName()
                                    .name();
        }
    }

    record SubscriptionKey(String streamName, int partition, String consumerGroup) {}

    /// Why a declaration is (or is not) consuming, as this node computes it. Compared by value so the
    /// loud log fires on TRANSITIONS only — a 5-second reconcile tick must not spam a warning forever.
    ///
    /// `eventTypePublishable` is [Option#none] when the slice is not local: the probe needs the slice's
    /// own codec registry, so a node without the slice genuinely cannot know, and saying `false` there
    /// would invent a value.
    record Diagnosis(Artifact artifact,
                     String methodName,
                     String streamName,
                     boolean deployedLocally,
                     Option<Boolean> eventTypePublishable,
                     String eventType,
                     List<PartitionAssignment> assignments,
                     List<Integer> unassignedPartitions,
                     List<Integer> forwardedPartitions,
                     boolean deploymentPending,
                     Option<String> message) {
        private static final Logger log = LoggerFactory.getLogger(StreamConsumerManager.class);

        static Diagnosis diagnosis(ConsumerDeclaration declaration,
                                   List<PartitionAssignment> assignments,
                                   List<Integer> unassigned,
                                   List<Integer> forwarded,
                                   Option<Boolean> publishable,
                                   boolean deploymentPending) {
            return new Diagnosis(declaration.artifact(),
                                 declaration.methodName().name(),
                                 declaration.streamName(),
                                 publishable.isPresent(),
                                 publishable,
                                 declaration.eventType(),
                                 assignments,
                                 unassigned,
                                 forwarded,
                                 deploymentPending,
                                 message(declaration, unassigned, publishable, deploymentPending));
        }

        /// The FAULT channel: populated only for a condition an operator must act on. Routine
        /// forwarding is deliberately NOT here — it is normal operation under the #535 assignment rule,
        /// and it is already visible structurally in `partitionAssignments`, where `consumerNode`
        /// differs from `ownerNode`. Mixing it in would destroy the property that makes this field
        /// useful: a non-empty `diagnostic` means something is wrong.
        private static Option<String> message(ConsumerDeclaration declaration,
                                              List<Integer> unassigned,
                                              Option<Boolean> publishable,
                                              boolean deploymentPending) {
            if (!unassigned.isEmpty()) {
                return Option.some(unassignedText(declaration, unassigned, deploymentPending));
            }

            if (publishable.map(known -> !known).or(false)) {
                return Option.some("event type " + declaration.eventType()
                                  + " has no codec in the slice's own codec registry, so it cannot be PUBLISHED to the stream at all"
                                  + " — this consumer will receive nothing until the slice registers a codec for it");
            }

            return Option.none();
        }

        /// A deployment in flight and a slice that is nowhere both produce an EMPTY candidate set, so
        /// the text has to separate them: the first is what every normal deploy looks like for a few
        /// seconds, the second is the one an operator must act on.
        private static String unassignedText(ConsumerDeclaration declaration,
                                             List<Integer> unassigned,
                                             boolean deploymentPending) {
            return deploymentPending
                   ? "partitions " + unassigned
                    + " of stream " + declaration.streamName()
                    + " are not being consumed YET — slice " + declaration.artifact()
                                                                          .asString()
                    + " is deployed but has not reached ACTIVE on any live node"
                   : "partitions " + unassigned
                    + " of stream " + declaration.streamName()
                    + " have a declared consumer in slice " + declaration.artifact()
                                                                         .asString()
                    + ", but that slice is ACTIVE on no live node — those partitions are NOT being"
                    + " consumed by anyone";
        }

        @Contract
        void log() {
            if (!unassignedPartitions.isEmpty()) {
                logUnassigned();

                return;
            }

            if (eventTypePublishable.map(known -> !known).or(false)) {
                message.onPresent(text -> log.warn("Declarative stream consumer {}.{}: {}", artifact, methodName, text));

                return;
            }

            if (!forwardedPartitions.isEmpty()) {
                log.info("Declarative stream consumer {}.{}: consuming partitions {} of stream {} whose owner is another node — reads for them are forwarded to the owner",
                         artifact,
                         methodName,
                         forwardedPartitions,
                         streamName);

                return;
            }

            log.debug("Declarative stream consumer {}.{} on {}: assignments {}",
                      artifact,
                      methodName,
                      streamName,
                      assignments);
        }

        /// ERROR only for a REAL gap. A deployment still activating is the normal path — raising the
        /// same alarm for it would put an ERROR in the log of every successful deploy and teach
        /// operators to ignore the one that matters.
        @Contract
        private void logUnassigned() {
            if (deploymentPending) {
                message.onPresent(text -> log.info("Declarative stream consumer {}.{}: {}", artifact, methodName, text));

                return;
            }

            message.onPresent(text -> log.error("Declarative stream consumer GAP: {}", text));
        }
    }
}
