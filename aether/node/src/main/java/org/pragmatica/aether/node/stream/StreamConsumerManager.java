// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.stream;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.node.stream.StreamConsumerRegistry.ConsumerDeclaration;
import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.stream.StreamConsumerRuntime;
import org.pragmatica.aether.stream.StreamConsumerRuntime.ConsumerCallback;
import org.pragmatica.aether.stream.StreamConsumerRuntime.IdlePolicy;
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
/// This node consumes partition `P` of stream `S` for a declaration iff BOTH hold:
///   1. this node is the HRW **owner** of `(S, P)`, and
///   2. the declaring artifact is deployed locally.
///
/// Owner-gating (rather than "wherever the ring is materialized") is required for correctness:
/// REPLICA-role nodes also materialize local rings, so consuming wherever data is local would deliver
/// every event once per replica. Owner-gating also keeps every read local and inherits failover from
/// the existing placement machinery for free.
///
/// There is no role-change callback available to a third party — the `onBecameReplica` and
/// `onReconcilePassComplete` seams are single-consumer and already taken — so ownership is polled by
/// [#reconcile], driven from the node's periodic task list.
///
/// ## Guarantee
///
/// **At-least-once delivery per partition, conditional on the partition's owner having the slice
/// deployed.** Duplicates arise from redelivery after a handler failure under `RETRY`, from the
/// ownership-handover window (both the old and new owner may deliver within one reconcile tick), and
/// from resuming at the last checkpoint rather than the last delivered offset. This is NOT
/// effectively-once: there is no fencing token on delivery. When a partition's owner does not have
/// the slice deployed, delivery for that partition is ZERO, not delayed — that case is reported
/// loudly and appears in [#statuses] as `unconsumedOwnedPartitions`.
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
        }

        return new inactive();
    }

    /// Partition ownership as this node currently sees it. A seam so the gating decision is testable
    /// without a cluster.
    interface PartitionOwnership {
        /// Declared partition count for the stream, or empty when this node does not know the stream
        /// yet (a transient startup state, not an error).
        Option<Integer> partitionCount(String streamName);
        /// True when THIS node is the HRW owner of the partition.
        boolean ownedLocally(String streamName, int partition);
    }

    record PartitionCursor(int partition, long cursor, boolean stalled) {}

    /// Operator-visible state of one declared consumer on this node. Pure snapshot.
    record ConsumerStatus(String streamName,
                          String configSection,
                          String artifact,
                          String methodName,
                          String consumerGroup,
                          boolean batchMode,
                          String eventType,
                          boolean sliceDeployedLocally,
                          boolean eventTypePublishable,
                          List<PartitionCursor> assignedPartitions,
                          List<Integer> unconsumedOwnedPartitions,
                          Option<String> diagnostic) {}

    static StreamConsumerManager streamConsumerManager(StreamConsumerRegistry registry,
                                                       StreamConsumerRuntime runtime,
                                                       SliceInvoker invoker,
                                                       InvocationHandler invocationHandler,
                                                       SliceCodec nodeCodec,
                                                       PartitionOwnership ownership) {
        var manager = new ManagerState(registry, runtime, invoker, invocationHandler, nodeCodec, ownership);

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
        private final Map<SubscriptionKey, ConsumerDeclaration> active = new ConcurrentHashMap<>();
        private final Map<String, Diagnosis> diagnoses = new ConcurrentHashMap<>();

        ManagerState(StreamConsumerRegistry registry,
                     StreamConsumerRuntime runtime,
                     SliceInvoker invoker,
                     InvocationHandler invocationHandler,
                     SliceCodec nodeCodec,
                     PartitionOwnership ownership) {
            this.registry = registry;
            this.runtime = runtime;
            this.invoker = invoker;
            this.invocationHandler = invocationHandler;
            this.nodeCodec = nodeCodec;
            this.ownership = ownership;
        }

        private void onDeclarationChange(Object key, Option<ConsumerDeclaration> declaration) {
            reconcile();
        }

        @Contract
        @Override
        public void reconcile() {
            var desired = registry.allDeclarations()
                                  .stream()
                                  .flatMap(declaration -> desiredFor(declaration).stream())
                                  .toList();

            desired.forEach(this::subscribeIfAbsent);
            dropStale(desired);
        }

        /// Subscriptions this node SHOULD hold for one declaration, plus the loud reporting for the
        /// two ways a declaration can end up consuming nothing.
        private List<SubscriptionKey> desiredFor(ConsumerDeclaration declaration) {
            var owned = ownedPartitions(declaration.streamName());
            var bridge = invocationHandler.localSlice(declaration.artifact());

            bridge.onPresent(loaded -> reportDeployed(declaration, owned, loaded))
                  .onEmpty(() -> reportUndeployed(declaration, owned));

            return bridge.isPresent()
                   ? subscriptionKeys(declaration, owned)
                   : List.of();
        }

        private static List<SubscriptionKey> subscriptionKeys(ConsumerDeclaration declaration, List<Integer> owned) {
            return owned.stream()
                        .map(partition -> new SubscriptionKey(declaration.streamName(),
                                                              partition,
                                                              declaration.consumerGroup()))
                        .toList();
        }

        private List<Integer> ownedPartitions(String streamName) {
            return ownership.partitionCount(streamName)
                            .map(count -> partitionsOwnedOf(streamName, count))
                            .or(List.of());
        }

        private List<Integer> partitionsOwnedOf(String streamName, int count) {
            return IntStream.range(0, count)
                            .filter(partition -> ownership.ownedLocally(streamName, partition))
                            .boxed()
                            .toList();
        }

        private void reportUndeployed(ConsumerDeclaration declaration, List<Integer> owned) {
            unsubscribeAllFor(declaration);
            if (owned.isEmpty()) {
                recordDiagnosis(declaration, Diagnosis.notDeployedNotOwner(declaration));

                return;
            }

            recordDiagnosis(declaration, Diagnosis.ownedButUndeployed(declaration, owned));
        }

        private void reportDeployed(ConsumerDeclaration declaration, List<Integer> owned, SliceBridge bridge) {
            recordDiagnosis(declaration, Diagnosis.deployed(declaration, owned, publishable(declaration, bridge)));
        }

        /// Exact test of the #526 publish-side gap: `StreamAccess`/`StreamPublisher` are provisioned
        /// with the node-wide codec, so an event type absent from it cannot be PUBLISHED at all and
        /// this consumer will never see an event no matter how healthy it looks.
        private boolean publishable(ConsumerDeclaration declaration, SliceBridge bridge) {
            return Result.lift(() -> Class.forName(declaration.eventType(),
                                                   false,
                                                   bridge.classLoader()))
                         .map(this::knownToNodeCodec)
                         .or(false);
        }

        private boolean knownToNodeCodec(Class<?> eventClass) {
            return Result.lift(() -> nodeCodec.lookupByClass(eventClass)).isSuccess();
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

        private Option<ConsumerDeclaration> declarationFor(SubscriptionKey key) {
            return registry.allDeclarations()
                           .stream()
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
                              ConsumerConfig.consumerConfig(declaration.consumerGroup()),
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

        private Promise<Unit> deliver(ConsumerDeclaration declaration, SliceBridge bridge, byte[] payload) {
            return bridge.decode(payload)
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
                                                                                    snapshot.stalled()),
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
                                      diagnosis.map(Diagnosis::eventTypePublishable).or(false),
                                      assigned,
                                      diagnosis.map(Diagnosis::unconsumedOwnedPartitions).or(List.of()),
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

    /// Why a declaration is (or is not) consuming on this node. Compared by value so the loud log
    /// fires on TRANSITIONS only — a 5-second reconcile tick must not spam a warning forever.
    record Diagnosis(Artifact artifact,
                     String methodName,
                     String streamName,
                     boolean deployedLocally,
                     boolean eventTypePublishable,
                     String eventType,
                     List<Integer> ownedPartitions,
                     List<Integer> unconsumedOwnedPartitions,
                     Option<String> message) {
        private static final Logger log = LoggerFactory.getLogger(StreamConsumerManager.class);

        static Diagnosis deployed(ConsumerDeclaration declaration, List<Integer> owned, boolean publishable) {
            return new Diagnosis(declaration.artifact(),
                                 declaration.methodName().name(),
                                 declaration.streamName(),
                                 true,
                                 publishable,
                                 declaration.eventType(),
                                 owned,
                                 List.of(),
                                 publishable
                                 ? Option.none()
                                 : Option.some("event type " + declaration.eventType()
                                              + " is not registered in the node codec, so it cannot be PUBLISHED to the stream at all"
                                              + " — this consumer will receive nothing until #526 (slice-scoped stream serializer) lands"));
        }

        static Diagnosis ownedButUndeployed(ConsumerDeclaration declaration, List<Integer> owned) {
            return new Diagnosis(declaration.artifact(),
                                 declaration.methodName().name(),
                                 declaration.streamName(),
                                 false,
                                 false,
                                 declaration.eventType(),
                                 owned,
                                 owned,
                                 Option.some("this node owns partitions " + owned
                                            + " of stream " + declaration.streamName()
                                            + " and a consumer is declared for them, but slice " + declaration.artifact()
                                                                                                              .asString()
                                            + " is not deployed here — those partitions are NOT being consumed by anyone"));
        }

        static Diagnosis notDeployedNotOwner(ConsumerDeclaration declaration) {
            return new Diagnosis(declaration.artifact(),
                                 declaration.methodName().name(),
                                 declaration.streamName(),
                                 false,
                                 false,
                                 declaration.eventType(),
                                 List.of(),
                                 List.of(),
                                 Option.none());
        }

        @Contract
        void log() {
            if (!unconsumedOwnedPartitions.isEmpty()) {
                message.onPresent(text -> log.error("Declarative stream consumer GAP: {}", text));

                return;
            }

            if (!eventTypePublishable && deployedLocally) {
                message.onPresent(text -> log.warn("Declarative stream consumer {}.{}: {}", artifact, methodName, text));

                return;
            }

            log.debug("Declarative stream consumer {}.{} on {}: owned partitions {}",
                      artifact,
                      methodName,
                      streamName,
                      ownedPartitions);
        }
    }
}
