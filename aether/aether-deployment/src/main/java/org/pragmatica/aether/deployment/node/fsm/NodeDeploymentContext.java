// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.fsm;

import org.pragmatica.aether.deployment.config.ConfigNotificationManager;
import org.pragmatica.aether.deployment.node.NodeDeploymentManager.SuspendedSlice;
import org.pragmatica.aether.deployment.node.RoutingEpochAckTracker;
import org.pragmatica.aether.http.HttpRoutePublisher;
import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.statemachine.Fsm;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/// Shared context for the NodeDeploymentManager FSM. Holds:
///
/// - Configuration and collaborators that are long-lived across the Dormant ↔ Active cycle
///   (`self`, `selfAddress`, `sliceStore`, `cluster`, `kvStore`, `invocationHandler`, `router`,
///   `configuration`, `nodeCodec`, `httpRoutePublisher`, `sliceInvokerFacade`,
///   `activationChainTimeout`, `transitionRetryDelay`).
/// - The bound [`Fsm`] reference (injected via constructor-driven initial-state factory).
/// - Per-FSM singletons for the data-free states (`dormant`, `stopped`).
/// - `quorumSequence` — `AtomicLong` anchor used to dedup stale `QuorumStateNotification`s
///   before translating them into [`ClusterFsmEvent`]s.
/// - `shutdownCallback` — runnable invoked when the KV-Store transitions this node's lifecycle
///   to `SHUTTING_DOWN`.
///
/// Per-activation mutable state (slice deployment map, routing-epoch ack tracker, config
/// notification manager) lives on the [`NodeDeploymentState.Active`] record — it is created
/// fresh on every Dormant → Active transition.
public final class NodeDeploymentContext {
    private final Fsm<NodeDeploymentState, ClusterFsmEvent> fsm;
    private final NodeId self;
    private final NodeAddress selfAddress;
    private final SliceStore sliceStore;
    private final SliceActionConfig configuration;
    private final SliceCodec nodeCodec;
    private final ClusterNode<KVCommand<AetherKey>> cluster;
    private final KVStore<AetherKey, AetherValue> kvStore;
    private final InvocationHandler invocationHandler;
    private final MessageRouter router;
    private final Option<HttpRoutePublisher> httpRoutePublisher;
    private final Option<SliceInvokerFacade> sliceInvokerFacade;
    private final TimeSpan activationChainTimeout;
    private final TimeSpan transitionRetryDelay;
    private final AtomicLong quorumSequence;
    private final AtomicReference<Runnable> shutdownCallback;
    private final NodeDeploymentState dormant;
    private final NodeDeploymentState stopped;

    public NodeDeploymentContext(Fsm<NodeDeploymentState, ClusterFsmEvent> fsm,
                                 NodeId self,
                                 NodeAddress selfAddress,
                                 SliceStore sliceStore,
                                 SliceActionConfig configuration,
                                 SliceCodec nodeCodec,
                                 ClusterNode<KVCommand<AetherKey>> cluster,
                                 KVStore<AetherKey, AetherValue> kvStore,
                                 InvocationHandler invocationHandler,
                                 MessageRouter router,
                                 Option<HttpRoutePublisher> httpRoutePublisher,
                                 Option<SliceInvokerFacade> sliceInvokerFacade,
                                 TimeSpan activationChainTimeout,
                                 TimeSpan transitionRetryDelay) {
        this.fsm = fsm;
        this.self = self;
        this.selfAddress = selfAddress;
        this.sliceStore = sliceStore;
        this.configuration = configuration;
        this.nodeCodec = nodeCodec;
        this.cluster = cluster;
        this.kvStore = kvStore;
        this.invocationHandler = invocationHandler;
        this.router = router;
        this.httpRoutePublisher = httpRoutePublisher;
        this.sliceInvokerFacade = sliceInvokerFacade;
        this.activationChainTimeout = activationChainTimeout;
        this.transitionRetryDelay = transitionRetryDelay;
        this.quorumSequence = new AtomicLong(0);
        this.shutdownCallback = new AtomicReference<>();
        this.dormant = new NodeDeploymentState.Dormant(this, List.of());
        this.stopped = new NodeDeploymentState.Stopped(this);
    }

    // --- FSM access ---

    public Fsm<NodeDeploymentState, ClusterFsmEvent> fsm() {
        return fsm;
    }

    @Contract public void dispatch(ClusterFsmEvent event) {
        fsm.dispatch(event);
    }

    public NodeDeploymentState dormant() {
        return dormant;
    }

    public NodeDeploymentState stopped() {
        return stopped;
    }

    /// Build a fresh [`NodeDeploymentState.Dormant`] carrying the given suspended slices. Used by
    /// `Active → Dormant` on `QuorumDisappeared`: the suspended list is preserved so the next
    /// `QuorumEstablished` transition can reactivate them on entry.
    public NodeDeploymentState newDormantWithSuspended(List<SuspendedSlice> suspended) {
        if (suspended.isEmpty()) {return dormant;}
        return new NodeDeploymentState.Dormant(this, suspended);
    }

    /// Build a fresh [`NodeDeploymentState.Active`] with a new deployments map, config notification
    /// manager, and routing-epoch ack tracker. `pendingReactivation` is the list of suspended
    /// slices carried over from the previous `Dormant`; it is processed in `Active.onEntry`.
    public NodeDeploymentState.Active newActive(List<SuspendedSlice> pendingReactivation) {
        return new NodeDeploymentState.Active(this,
                                              new ConcurrentHashMap<>(),
                                              ConfigNotificationManager.configNotificationManager(),
                                              RoutingEpochAckTracker.routingEpochAckTracker(),
                                              pendingReactivation);
    }

    // --- Config accessors ---

    public NodeId self() {
        return self;
    }

    public NodeAddress selfAddress() {
        return selfAddress;
    }

    public SliceStore sliceStore() {
        return sliceStore;
    }

    public SliceActionConfig configuration() {
        return configuration;
    }

    public SliceCodec nodeCodec() {
        return nodeCodec;
    }

    public ClusterNode<KVCommand<AetherKey>> cluster() {
        return cluster;
    }

    public KVStore<AetherKey, AetherValue> kvStore() {
        return kvStore;
    }

    public InvocationHandler invocationHandler() {
        return invocationHandler;
    }

    public MessageRouter router() {
        return router;
    }

    public Option<HttpRoutePublisher> httpRoutePublisher() {
        return httpRoutePublisher;
    }

    public Option<SliceInvokerFacade> sliceInvokerFacade() {
        return sliceInvokerFacade;
    }

    public TimeSpan activationChainTimeout() {
        return activationChainTimeout;
    }

    public TimeSpan transitionRetryDelay() {
        return transitionRetryDelay;
    }

    public AtomicLong quorumSequence() {
        return quorumSequence;
    }

    public Option<Runnable> shutdownCallback() {
        return Option.option(shutdownCallback.get());
    }

    @Contract public void setShutdownCallback(Runnable callback) {
        shutdownCallback.set(callback);
    }

    /// True iff the FSM's current state is [`NodeDeploymentState.Active`]. Exposed for the
    /// `NodeDeploymentManager#isActive` adapter method and for lifecycle re-registration logic.
    public boolean isActive() {
        return fsm.current() instanceof NodeDeploymentState.Active;
    }
}
