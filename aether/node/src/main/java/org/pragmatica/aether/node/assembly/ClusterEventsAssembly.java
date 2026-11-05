// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.pragmatica.aether.api.AlertManager;
import org.pragmatica.aether.api.ClusterEvent;
import org.pragmatica.aether.api.ClusterEventAggregator;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager;
import org.pragmatica.aether.deployment.cluster.ClusterReplication;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.cluster.DrainReason;
import org.pragmatica.aether.deployment.membership.ntt.DrainProcedure;
import org.pragmatica.aether.invoke.InvocationTraceStore;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.aether.node.SystemStreamRegistrar;
import org.pragmatica.aether.slice.*;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.stream.FrameworkStreamConsumer;
import org.pragmatica.aether.slice.stream.FrameworkStreamPublisher;
import org.pragmatica.aether.slice.stream.StreamNamespacesService;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamReadForwardMetrics;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicaSetController;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.*;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVNotificationRouter;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.rabia.VoterConfiguration;
import org.pragmatica.dht.DeparturePushObserver;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import static org.pragmatica.aether.node.AetherNode.CLUSTER_EVENT_REDELIVERY_INTERVAL;
import static org.pragmatica.aether.node.AetherNode.LOG;
import static org.pragmatica.aether.node.AetherNode.OPERATOR_WARNING_EVICTION_INTERVAL;
import static org.pragmatica.aether.node.AetherNode.resolveAlertConfig;


/// The cluster-events unit of the node assembly: the event aggregator with the operator warning sink and the sinks that
/// feed it, the drain and departure announcements, the cluster-events stream publisher and consumer, and the system
/// stream registrar. Moved out of `AetherNode` without change; the root calls the phases in the order it always ran them.
public sealed interface ClusterEventsAssembly {
    /// What `eventAggregator` reads from the assembly built so far.
    record EventAggregatorInputs(AetherNodeConfig config,
                                 KVStore<AetherKey, AetherValue> kvStore,
                                 HlcClock hlcClock,
                                 ClusterDeploymentManager clusterDeploymentManager,
                                 BooleanSupplier isLeaderSupplier,
                                 ClusterTopologyManager clusterTopologyManager,
                                 Consumer<Supplier<ScheduledFuture<?>>> defer) {}

    /// What `eventAggregator` hands back for the rest of the assembly.
    record EventAggregatorOutputs(HlcClock clusterEventsHlcClock,
                                  AtomicReference<FrameworkStreamPublisher<ClusterEvent>> clusterEventsPublisherRef,
                                  AtomicReference<FrameworkStreamConsumer<ClusterEvent>> clusterEventsConsumerRef,
                                  AtomicReference<ReplicaSetController> clusterEventsControllerRef,
                                  String clusterEventsStreamName,
                                  BooleanSupplier clusterEventsOwnerCheck,
                                  ClusterEventAggregator eventAggregator,
                                  OperatorWarningSink operatorWarningSink) {}

    /// The event aggregator, the operator warning sink every component reports through, the unconfirmed-mark source and the first deferred tick.
    static EventAggregatorOutputs eventAggregator(EventAggregatorInputs in) {
        var config = in.config();
        var kvStore = in.kvStore();
        var hlcClock = in.hlcClock();
        var clusterDeploymentManager = in.clusterDeploymentManager();
        var isLeaderSupplier = in.isLeaderSupplier();
        var clusterTopologyManager = in.clusterTopologyManager();
        var defer = in.defer();
        // Stream-namespaces rebuild (B5b) — cluster events over system:cluster-events:1.0.0.
        //
        // Producer handlers build a sealed `ClusterEvent` record and `emit(...)` it into the
        // framework events stream; reads go through the stream consumer. This replaces the rc1
        // transitional stack (ClusterEventLogPublisher rate-capped KV writer + ClusterEventLogSweeper
        // GC + in-process RingBuffer materialised view). The events stream is now a real
        // partition-managed, replicated stream (provisioned via SystemStreamFactories below);
        // retention is enforced by the stream's production RetentionPolicy (count/byte/age caps).
        //
        // Publisher + consumer are bound deferred via AtomicReferences: the stream stack is
        // constructed further down (after streamPartitionManager), so during the bootstrap window
        // the aggregator falls back to log-only emit / empty reads (spec §13.3). Stream lifecycle
        // events (STREAM_REGISTERED / STREAM_DELETED) are NOT emitted yet — lifecycle-event emission
        // is deferred to RC2 (Wave-5B); nothing produces them today. When it lands, the
        // self-referential STREAM_REGISTERED loop for system:cluster-events must be gated by
        // StreamLifecycleEventPolicy.shouldEmit, the guard already in place for that work.
        var clusterEventsHlcClock = hlcClock;
        var clusterEventsPublisherRef = new java.util.concurrent.atomic.AtomicReference<org.pragmatica.aether.slice.stream.FrameworkStreamPublisher<ClusterEvent>>();
        var clusterEventsConsumerRef = new java.util.concurrent.atomic.AtomicReference<org.pragmatica.aether.slice.stream.FrameworkStreamConsumer<ClusterEvent>>();
        // B5b owner-gate: emit only when THIS node owns (system:cluster-events:1.0.0, partition 0).
        // The ReplicaSetController is constructed further down (after the stream stack), so the
        // owner-check derefs it lazily through this ref. Until the controller is bound — and until the
        // topology observer reports members — the check returns false and emits are dropped+logged
        // (bootstrap window). Once members are visible, isOwner is computed directly from the live HRW
        // placement (no registry/reconcile dependency), so a steady-state single-node cluster is the
        // owner and emits.
        var clusterEventsControllerRef = new java.util.concurrent.atomic.AtomicReference<ReplicaSetController>();
        var clusterEventsStreamName = org.pragmatica.aether.slice.stream.SystemStreams.CLUSTER_EVENTS.asString();
        java.util.function.BooleanSupplier clusterEventsOwnerCheck = () -> Option.option(clusterEventsControllerRef.get())
                                                                                 .map(replicaController -> replicaController.isOwner(clusterEventsStreamName,
                                                                                                                                     0))
                                                                                 .or(false);
        // Leader-gate for consensus-committed membership-DEPARTURE emits (NODE_FAILED / NODE_LEFT):
        // the authoritative emitter of a committed membership decision is the cluster leader, which is
        // never the just-removed node for its own committed decision and is unaffected by the
        // partition-0 HRW churn the owner-gate suffers during the deferred membership reconcile window.
        // Reuses the node's existing leadership signal (clusterNode.leaderManager()::isLeader, captured
        // as isLeaderSupplier above).
        java.util.function.BooleanSupplier clusterEventsLeaderCheck = isLeaderSupplier;
        var eventAggregator = ClusterEventAggregator.clusterEventAggregator(clusterEventsPublisherRef::get,
                                                                            clusterEventsConsumerRef::get,
                                                                            clusterEventsOwnerCheck,
                                                                            config.self(),
                                                                            clusterEventsHlcClock,
                                                                            clusterTopologyManager.observer()::clusterSize,
                                                                            kvStore::isReplaying,
                                                                            clusterEventsLeaderCheck,
                                                                            () -> clusterEventsControllerRef.get() != null
                                                                            // #957: ownership is RESOLVABLE once the controller ref is
                                                                            // bound. Until then `clusterEventsOwnerCheck` returns false
                                                                            // via its `.or(false)` fallback on EVERY node, so nobody
                                                                            // publishes and the event is lost rather than merely
                                                                            // suppressed. This supplier is what lets the aggregator tell
                                                                            // that hole apart from ordinary non-ownership.
                                                                           );
        // #1574 / #1617: ONE operator-warning sink per node, bound to THIS node's aggregator (never another
        // Ember node's). It hands each warning off to a bounded queue, so a raise from SWIM, replication or the
        // core-absence fence never waits on the event log. Idle throttle keys are evicted once per minute.
        var operatorWarningSink = OperatorWarningSink.handingOffTo(eventAggregator::onOperatorWarning);
        // #2062: an instance whose termination the CTM cannot confirm after its bounded retries is an operator event.
        clusterTopologyManager.setOperatorWarningSink(operatorWarningSink);
        // #1109: the leader's committed-target gate reports a LOAD it has refused past the bound to THIS node's event log.
        clusterDeploymentManager.setOperatorWarningSink(operatorWarningSink);
        // #2062: the marks of retired nodes whose termination is unconfirmed live in the replicated store, so a new leader inherits them.
        clusterTopologyManager.setUnconfirmedMarks(() -> {
            var marks = new HashMap<NodeId, AetherValue.UnconfirmedTerminationValue>();

            kvStore.forEach(AetherKey.UnconfirmedTerminationKey.class,
                            AetherValue.UnconfirmedTerminationValue.class,
                            (key, mark) -> marks.put(key.nodeId(), mark));

            return Map.copyOf(marks);
        });
        defer.accept(() -> SharedScheduler.scheduleAtFixedRate(eventAggregator::evictIdleThrottleWindows,
                                                               OPERATOR_WARNING_EVICTION_INTERVAL,
                                                               OPERATOR_WARNING_EVICTION_INTERVAL));

        return new EventAggregatorOutputs(clusterEventsHlcClock,
                                          clusterEventsPublisherRef,
                                          clusterEventsConsumerRef,
                                          clusterEventsControllerRef,
                                          clusterEventsStreamName,
                                          clusterEventsOwnerCheck,
                                          eventAggregator,
                                          operatorWarningSink);
    }

    /// What `eventTick` reads from the assembly built so far.
    record EventTickInputs(ClusterEventAggregator eventAggregator, Consumer<Supplier<ScheduledFuture<?>>> defer) {}

    /// The deferred tick that publishes the aggregator state.
    static Unit eventTick(EventTickInputs in) {
        var eventAggregator = in.eventAggregator();
        var defer = in.defer();
        // #1640: a cluster event whose publish did not land (the partition's owner died with it) waits in the
        // aggregator and is re-sent once a second until it lands or its horizon passes.
        defer.accept(() -> SharedScheduler.scheduleAtFixedRate(eventAggregator::redeliverDue,
                                                               CLUSTER_EVENT_REDELIVERY_INTERVAL,
                                                               CLUSTER_EVENT_REDELIVERY_INTERVAL));

        return Unit.unit();
    }

    /// What `eventSinks` reads from the assembly built so far.
    record EventSinksInputs(AetherNodeConfig config,
                            RabiaNode<KVCommand<AetherKey>> clusterNode,
                            InvocationTraceStore traceStore,
                            AtomicReference<Consumer<DrainReason>> clusterEventDrainEmitterRef,
                            AtomicReference<DeparturePushObserver> departurePushObserverRef,
                            DrainProcedure drainProcedure,
                            AlertManager alertManager,
                            HlcClock clusterEventsHlcClock,
                            ClusterEventAggregator eventAggregator,
                            KVStore<AetherKey, AetherValue> kvStore,
                            String clusterEventsStreamName) {}

    /// Binds the drain, departure, voter and alert sinks and the trace store to the aggregator.
    static Unit eventSinks(EventSinksInputs in) {
        var config = in.config();
        var clusterNode = in.clusterNode();
        var traceStore = in.traceStore();
        var clusterEventDrainEmitterRef = in.clusterEventDrainEmitterRef();
        var departurePushObserverRef = in.departurePushObserverRef();
        var drainProcedure = in.drainProcedure();
        var alertManager = in.alertManager();
        var clusterEventsHlcClock = in.clusterEventsHlcClock();
        var eventAggregator = in.eventAggregator();
        var kvStore = in.kvStore();
        var clusterEventsStreamName = in.clusterEventsStreamName();
        // Item-8 graft: best-effort SelfDrainInitiated emit on drain initiation. The aggregator is
        // forward-declared to DrainProcedure (constructed earlier) via this ref; the emitter lambda
        // resolves it lazily and no-ops until bound. NOT leader-gated — the draining node is the only
        // authoritative source for "I am self-draining".
        // #565: that contract requires `emitLocal`, NOT `emit`. `emit` applies the OWNER gate (a
        // different gate from the leader gate this comment rules out), and a self-fencing node is by
        // definition not the owner of the cluster-events partition — its placement view is the
        // PRE-partition one, whose partition-0 owner is typically among the nodes it just lost. So the
        // one event that explains why a node left was suppressed at DEBUG, invisible at default INFO.
        // `emitLocal`'s own contract cites SelfDrainInitiated as the exemplar for this exact class of
        // per-node fact; this call site was the one that did not follow it.
        clusterEventDrainEmitterRef.set(reason -> eventAggregator.emitLocal(new ClusterEvent.SelfDrainInitiated(clusterEventsHlcClock.now(),
                                                                                                                ClusterEvent.Severity.WARNING,
                                                                                                                "Self-drain initiated on " + config.self()
                                                                                                                                                   .id()
                                                                                                               + " (reason=" + reason
                                                                                                               + ")",
                                                                                                                Map.of("nodeId",
                                                                                                                       config.self()
                                                                                                                             .id(),
                                                                                                                       "reason",
                                                                                                                       String.valueOf(reason)))));
        // #427: resolve the departure-push observer now the aggregator is bound — a budget overrun
        // during graceful departure emits DeparturePushIncomplete (per-node fact, un-gated emitLocal).
        departurePushObserverRef.set((keysAtRisk, sampleKeys) -> eventAggregator.onDeparturePushIncomplete(config.self(),
                                                                                                           keysAtRisk,
                                                                                                           sampleKeys));
        // Operator-driven inject endpoints replicate via the events stream so peer nodes return
        // injected items on cross-node reads. AlertManager emits AlertInjected; InvocationTraceStore
        // emits TraceInjected through a thin sink that keeps the aether-invoke module free of any
        // ClusterEvent dependency.
        eventAggregator.bindSelfCommandedDrain(drainProcedure::isCommandedDrain);
        eventAggregator.bindSelfCoreAbsenceDrain(drainProcedure::isCoreAbsenceDrain);
        eventAggregator.bindSelfIsVoter(() -> isInstalledVoterOrUnknown(clusterNode.voterConfiguration(), config.self()));
        // #1671 (I-35): the facts raised from a committed KV notification are gated on the committed ownership record of the
        // cluster-events partition (read from the store only until this node has seen an ownership notification), and with no
        // record on the committed leader (the same).
        eventAggregator.bindCommittedOwnerSources(() -> committedClusterEventsOwner(kvStore, clusterEventsStreamName),
                                                  () -> committedLeader(kvStore));
        alertManager.bindEventSink(eventAggregator::emit, clusterEventsHlcClock);
        alertManager.bindClusterEventsSource(eventAggregator::events);
        // #957: AlertForwarder was never constructed anywhere in src/main, so no alert left this
        // process by any path. Construct it here, next to the other AlertManager bindings.
        //
        // The argument is load-bearing and the wrong one is SILENT: AlertConfig.alertConfig()
        // compiles and yields a forwarder built from the DEFAULT config, whose webhooks are
        // DISABLED with no URLs -- an operator who configured a webhook would still get nothing,
        // with no error anywhere. resolveAlertConfig(config) carries the operator's own section.
        // The rc4 merge produced NO conflict marker here; both sides compiled.
        alertManager.withAlertForwarder(resolveAlertConfig(config));
        traceStore.bindTraceEventSink((operation, requestId, depth, durationMs, metadata) -> eventAggregator.emit(new ClusterEvent.TraceInjected(clusterEventsHlcClock.now(),
                                                                                                                                                 ClusterEvent.Severity.INFO,
                                                                                                                                                 "Injected trace: " + operation,
                                                                                                                                                 metadata)));
        traceStore.bindClusterEventsSource(() -> eventAggregator.events()
                                                                .map(ClusterEventsAssembly::projectClusterTraceInjections));

        return Unit.unit();
    }

    /// What `eventStreams` reads from the assembly built so far.
    record EventStreamsInputs(AetherNodeConfig config,
                              Serializer serializer,
                              Deserializer deserializer,
                              AtomicReference<FrameworkStreamPublisher<ClusterEvent>> clusterEventsPublisherRef,
                              AtomicReference<FrameworkStreamConsumer<ClusterEvent>> clusterEventsConsumerRef,
                              AtomicReference<ReplicaSetController> clusterEventsControllerRef,
                              String clusterEventsStreamName,
                              ReplicaRegistry streamReplicaRegistry,
                              StreamPartitionManager streamPartitionManager,
                              StreamConfig clusterEventsStreamConfig,
                              StreamReadForwardMetrics streamReadForwardMetrics,
                              StreamForwardClient streamForwardClient) {}

    /// The cluster-events stream publisher and consumer over the stream partition manager.
    static Unit eventStreams(EventStreamsInputs in) {
        var config = in.config();
        var serializer = in.serializer();
        var deserializer = in.deserializer();
        var clusterEventsPublisherRef = in.clusterEventsPublisherRef();
        var clusterEventsConsumerRef = in.clusterEventsConsumerRef();
        var clusterEventsControllerRef = in.clusterEventsControllerRef();
        var clusterEventsStreamName = in.clusterEventsStreamName();
        var streamReplicaRegistry = in.streamReplicaRegistry();
        var streamPartitionManager = in.streamPartitionManager();
        var clusterEventsStreamConfig = in.clusterEventsStreamConfig();
        var streamReadForwardMetrics = in.streamReadForwardMetrics();
        var streamForwardClient = in.streamForwardClient();
        // cluster-events CONSUMER: forward-capable (ANY_REPLICA). `/api/events` is now an ANY-target
        // route (#267) — served from any core node, not leader-bound — so the endpoint stays available
        // during leader churn/election (the prior LEADER binding 503'd exactly when operators most need
        // events). A node that is NOT a cluster-events replica read-forwards to a CAUGHT_UP replica via
        // the StreamForwardClient instead of reading its own empty partition (returning 200 []); a node
        // that IS a caught-up replica reads locally; during the bootstrap window (no caught-up replica
        // visible yet) it fails soft to the local partition. NODE_FAILED/NODE_LEFT are still emitted
        // leader-gated, then write-forwarded to the partition-0 owner (#1230), which replicates them.
        //
        // HISTORY (#94/B5): forward-capable reads were previously reverted to LOCAL because a fresh
        // replica did not actually hold the partition's history (no real backfill) and offsets were
        // unverified, so a forwarded fetch derived from leader-local metadata mismatched the replica and
        // returned 200 []. Wave-1 #260 (receiver verifies fromOffset, rejects gaps) + #261 (backfill
        // fires on becoming a replica; CAUGHT_UP only after history coverage) fixed that root: a
        // CAUGHT_UP replica now genuinely holds [earliest..N], so a forwarded fetch from the retained
        // tail returns the full window. Staleness: a forwarded read reflects the replica's CAUGHT_UP
        // watermark, which may trail the owner by the in-flight replication window (sub-second under
        // steady load) — acceptable for observability and far preferable to a 503.
        // #1230: cluster-events PUBLISHER, forward-capable. SYSTEM RF is the whole cluster, so every node holds
        // a replica ring; without a forward client, emitAsLeader (NODE_FAILED/NODE_LEFT on the leader) and
        // emitLocal (per-node StreamMemoryExceeded) appended on non-owners as undeclared second writers. Now a
        // non-owner emit write-forwards to the partition's HRW owner — the SAME ownerFor the consumer and the
        // B5b owner-gate use — and the owner appends and replicates.
        org.pragmatica.aether.stream.SystemStreamFactories.<ClusterEvent> systemStreamPublisher(org.pragmatica.aether.slice.stream.SystemStreams.CLUSTER_EVENTS,
                                                                                                streamPartitionManager,
                                                                                                serializer,
                                                                                                clusterEventsStreamConfig,
                                                                                                Option.some(streamForwardClient),
                                                                                                config.self(),
                                                                                                Option.some(partition -> Option.option(clusterEventsControllerRef.get()).flatMap(clusterEventsController -> clusterEventsController.ownerFor(clusterEventsStreamName,
                                                                                                                                                                                                                                             partition))))
                                                          .onSuccess(clusterEventsPublisherRef::set)
                                                          .onFailure(cause -> LOG.warn("cluster-events stream publisher wiring failed: {} — events fall back to log-only",
                                                                                       cause.message()));
        org.pragmatica.aether.stream.SystemStreamFactories.<ClusterEvent> systemStreamConsumer(org.pragmatica.aether.slice.stream.SystemStreams.CLUSTER_EVENTS,
                                                                                               streamPartitionManager,
                                                                                               serializer,
                                                                                               deserializer,
                                                                                               clusterEventsStreamConfig,
                                                                                               streamReplicaRegistry,
                                                                                               Option.some(streamForwardClient),
                                                                                               config.self(),
                                                                                               Option.some(() -> Option.option(clusterEventsControllerRef.get()).flatMap(clusterEventsController -> clusterEventsController.ownerFor(clusterEventsStreamName,
                                                                                                                                                                                                                                     0))),
                                                                                               streamReadForwardMetrics)
                                                          .onSuccess(clusterEventsConsumerRef::set)
                                                          .onFailure(cause -> LOG.warn("cluster-events stream consumer wiring failed: {} — events reads return empty",
                                                                                       cause.message()));

        return Unit.unit();
    }

    /// What `systemStreamRegistrar` reads from the assembly built so far.
    record SystemStreamRegistrarInputs(KVStore<AetherKey, AetherValue> kvStore,
                                       AlertManager alertManager,
                                       OperatorWarningSink operatorWarningSink,
                                       ArrayList<MessageRouter.Entry<?>> allEntries,
                                       StreamPartitionManager streamPartitionManager,
                                       StreamConfig clusterEventsStreamConfig,
                                       StreamNamespacesService streamNamespacesService) {}

    /// The self-healing registrar of the system streams and the routes that drive it.
    static Unit systemStreamRegistrar(SystemStreamRegistrarInputs in) {
        var kvStore = in.kvStore();
        var alertManager = in.alertManager();
        var operatorWarningSink = in.operatorWarningSink();
        var allEntries = in.allEntries();
        var streamPartitionManager = in.streamPartitionManager();
        var clusterEventsStreamConfig = in.clusterEventsStreamConfig();
        var streamNamespacesService = in.streamNamespacesService();
        // System streams (system:cluster-events + the namespace catalog backing /api/events|alerts|
        // traces) are registered LEVEL-TRIGGERED by the self-healing SystemStreamRegistrar (Fix #1).
        // The prior fire-once-on-leader-gain handler logged "will retry on next leader change" but never
        // did — a commit timeout in the post-leader-gain window (consensus not yet quorate) left the
        // stream unregistered forever on a stable cluster. The registrar instead retries on bounded
        // backoff until both legs commit (or hit a terminal config error), latches each leg DONE, and
        // disarms on leadership loss. Both legs are idempotent + consensus-committed, so re-arming on
        // each leader-gain is safe and self-heals across re-elections.
        // #1564: the stream is committed with the factors of the committed cluster config's
        // `[replication.cluster_events]` (CF default 1 until the owner decides the acked-but-lost question; RF the
        // desired core count), read when the leader commits it.
        var systemStreamRegistrar = SystemStreamRegistrar.systemStreamRegistrar(() -> ClusterReplication.clusterEventsFactors(kvStore.getTyped(AetherKey.ClusterConfigKey.CURRENT,
                                                                                                                                               AetherValue.ClusterConfigValue.class)).flatMap(factors -> streamPartitionManager.createStream(clusterEventsStreamConfig.withReplication(factors))),
                                                                                streamNamespacesService::bootstrap,
                                                                                cause -> raiseClusterEventsRefusal(operatorWarningSink,
                                                                                                                   alertManager,
                                                                                                                   cause),
                                                                                () -> alertManager.clearInjected(OperatorWarningCode.CLUSTER_EVENTS_REGISTRATION_REFUSED.code()));

        allEntries.add(MessageRouter.Entry.route(LeaderNotification.LeaderChange.class,
                                                 systemStreamRegistrar::onLeaderChange));
        // #1564 B1: a committed cluster config re-arms a registration the replication policy refused.
        allEntries.addAll(KVNotificationRouter.<AetherKey, AetherValue> builder(AetherKey.class)
                                              .onPut(AetherKey.ClusterConfigKey.class,
                                                     _ -> systemStreamRegistrar.onClusterConfigChanged())
                                              .build()
                                              .asRouteEntries());

        return Unit.unit();
    }

    /// #1564 B1: the operator-visible half of a refused `system:cluster-events` registration, a CRITICAL
    /// `cluster-events-registration-refused` operator warning (#1617, R10): an ERROR log, plus a cluster event. The
    /// event is offered to the very stream whose registration was refused, so it lands only once cluster-events
    /// exists. So the refusal is ALSO a CRITICAL alert on the leader's `/api/alerts/active` (v1680 N-r3-1), which does
    /// not depend on the stream. It is LOCAL-only (`injectLocal`, v1735 B1): a replicated copy would outlive the
    /// registrar's recovery sink, which resolves the alert once a corrected config commits the stream.
    @Contract
    private static void raiseClusterEventsRefusal(OperatorWarningSink sink, AlertManager alertManager, Cause cause) {
        OperatorWarnings.raise(LOG,
                               sink,
                               OperatorWarningCode.CLUSTER_EVENTS_REGISTRATION_REFUSED,
                               "system:cluster-events",
                               "system:cluster-events was not registered: {} — correct [replication.cluster_events] and re-apply the cluster config",
                               cause.message());
        alertManager.injectLocal(OperatorWarningCode.CLUSTER_EVENTS_REGISTRATION_REFUSED.code(),
                                 "CRITICAL",
                                 "system:cluster-events was not registered: " + cause.message()
                                + " — correct [replication.cluster_events] and re-apply the cluster config")
                    .onFailure(failure -> LOG.warn("Cluster-events refusal alert injection failed: {}",
                                                   failure.message()));
    }

    private static Option<NodeId> committedClusterEventsOwner(KVStore<AetherKey, AetherValue> kvStore,
                                                              String clusterEventsStreamName) {
        return kvStore.getTyped(AetherKey.StreamPartitionOwnershipKey.streamPartitionOwnershipKey(clusterEventsStreamName,
                                                                                                  0),
                                AetherValue.StreamPartitionOwnershipValue.class)
                      .map(AetherValue.StreamPartitionOwnershipValue::owner);
    }

    private static Option<NodeId> committedLeader(KVStore<AetherKey, AetherValue> kvStore) {
        return kvStore.getTyped(LeaderKey.INSTANCE, LeaderValue.class)
                      .map(LeaderValue::leader);
    }

    /// #2306: a node counts as a voter unless an installed configuration excludes it. No configuration yet (genesis pending)
    /// keeps the strict reading, so a cluster that never formed still reports its quorum loss.
    static boolean isInstalledVoterOrUnknown(Option<VoterConfiguration> installed, NodeId self) {
        return installed.map(configuration -> configuration.contains(self))
                        .or(true);
    }

    /// Project the aggregator's materialised `ClusterEvent` view onto
    /// `InvocationTraceStore.ClusterTraceEvent` records — filters by `TRACE_INJECTED` and
    /// extracts the metadata stamped by `InvocationTraceStore.publishInjectionToClusterLog`.
    /// Lives here (not in aether-invoke) because `ClusterEvent` is owned by aether/node.
    private static List<InvocationTraceStore.ClusterTraceEvent> projectClusterTraceInjections(List<ClusterEvent> events) {
        var list = new ArrayList<InvocationTraceStore.ClusterTraceEvent>();

        for (var event : events) {
            if (! (event instanceof ClusterEvent.TraceInjected)) {
                continue;
            }

            var details = event.details();
            var requestId = details.get("requestId");

            if (requestId == null) {
                continue;
            }

            var operation = details.getOrDefault("operation", "");
            var durationMs = parseLongDetail(details.get("durationMs"), 0L);
            var depth = (int) parseLongDetail(details.get("depth"), 0L);
            var timestamp = parseLongDetail(details.get("timestamp"), 0L);
            var nodeId = event.sourceNode().id();

            list.add(new InvocationTraceStore.ClusterTraceEvent(requestId,
                                                                operation,
                                                                durationMs,
                                                                depth,
                                                                timestamp,
                                                                nodeId));
        }

        return list;
    }

    // RET-06: `raw` is a nullable detail-map value; the null coalesce to a default is parse-boundary handling.
    @SuppressWarnings("JBCT-RET-06")
    private static long parseLongDetail(String raw, long defaultValue) {
        if (raw == null) {
            return defaultValue;
        }

        return org.pragmatica.lang.parse.Number.parseLong(raw)
                                               .or(defaultValue);
    }

    record unused() implements ClusterEventsAssembly {}
}
