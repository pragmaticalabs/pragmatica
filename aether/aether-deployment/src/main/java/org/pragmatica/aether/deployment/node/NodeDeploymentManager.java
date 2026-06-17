// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node;

import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentContext;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentEvents.NodeArtifactPutReceived;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentEvents.NodeArtifactRemoveReceived;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentEvents.NodeRoutesPutReceived;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentState;
import org.pragmatica.aether.http.HttpRoutePublisher;
import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.slice.ConfigFacade;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.statemachine.Fsm;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public interface NodeDeploymentManager {
    record SliceDeployment(SliceNodeKey key, SliceState state, long timestamp) {
        public static SliceDeployment sliceDeployment(SliceNodeKey key, SliceState state, long timestamp) {
            return new SliceDeployment(key, state, timestamp);
        }
    }

    record SuspendedSlice(SliceNodeKey key, SliceDeployment deployment) {
        public static SuspendedSlice suspendedSlice(SliceNodeKey key, SliceDeployment deployment) {
            return new SuspendedSlice(key, deployment);
        }
    }

    @Contract
    @MessageReceiver
    void onQuorumStateChange(ClusterStateNotification quorumStateNotification);

    @Contract
    @MessageReceiver
    void onMembershipDecision(MembershipDecision decision);

    @Contract
    @MessageReceiver
    void onNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut);

    @Contract
    @MessageReceiver
    void onNodeArtifactRemove(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove);

    @Contract
    @MessageReceiver
    void onNodeRoutesPut(ValuePut<NodeRoutesKey, NodeRoutesValue> valuePut);

    @Contract
    void setShutdownCallback(Runnable callback);

    @Contract
    void setSelfReadySignal(Runnable signal);

    boolean isActive();

    /// Level-heal for a silently-dropped `ClusterStateNotification.ACTIVE` edge. The ACTIVE edge is
    /// emitted once and CAS-latched by the emitter; on a cold-start `restart_all_nodes` it can be
    /// dropped while the message-router delegate is quiesced/being-rebuilt, leaving this node's FSM
    /// stuck in `Dormant` — so `Active.onEntry` (self-ready signal → `subsystemsReady`) never runs
    /// and the node reports `SYNCING` forever despite live consensus-active. A periodic driver
    /// (gated on live consensus-active) calls this; it re-dispatches `QuorumEstablished` ONLY while
    /// the FSM is still `Dormant`, so it is a guarded no-op once Active/Leaving/Stopped (the FSM
    /// ignores `QuorumEstablished` in those states anyway — this guard keeps it from churning and
    /// scopes the heal log to the rare real recovery). Idempotent on every healthy node thereafter.
    @Contract
    void reconcileActivation();

    ConfigFacade NO_OP_CONFIG = new NoOpDeploymentConfigFacade();

    record NoOpDeploymentConfigFacade() implements ConfigFacade {
        private static final Cause NO_CONFIG = Causes.cause("Config service not available");

        @Override
        public Result<String> requireString(String section, String key) {
            return NO_CONFIG.result();
        }

        @Override
        public Result<Integer> requireInt(String section, String key) {
            return NO_CONFIG.result();
        }

        @Override
        public Result<Long> requireLong(String section, String key) {
            return NO_CONFIG.result();
        }

        @Override
        public Result<Double> requireDouble(String section, String key) {
            return NO_CONFIG.result();
        }

        @Override
        public Result<Boolean> requireBoolean(String section, String key) {
            return NO_CONFIG.result();
        }

        @Override
        public Result<List<String>> requireStringList(String section, String key) {
            return NO_CONFIG.result();
        }

        @Override
        public Option<String> getString(String section, String key) {
            return Option.none();
        }

        @Override
        public Option<Integer> getInt(String section, String key) {
            return Option.none();
        }

        @Override
        public Option<Long> getLong(String section, String key) {
            return Option.none();
        }

        @Override
        public Option<Double> getDouble(String section, String key) {
            return Option.none();
        }

        @Override
        public Option<Boolean> getBoolean(String section, String key) {
            return Option.none();
        }
    }

    @SuppressWarnings("JBCT-UTIL-02")
    static ConfigFacade configServiceToFacade(ConfigService svc) {
        return new ConfigServiceConfigFacade(svc);
    }

    record ConfigServiceConfigFacade(ConfigService delegate) implements ConfigFacade {
        private static final Cause MISSING_KEY = Causes.cause("Required config key not found");

        @Override
        public Result<String> requireString(String section, String key) {
            return delegate.getString(section + "." + key)
                           .toResult(MISSING_KEY);
        }

        @Override
        public Result<Integer> requireInt(String section, String key) {
            return delegate.getInt(section + "." + key)
                           .toResult(MISSING_KEY);
        }

        @Override
        public Result<Long> requireLong(String section, String key) {
            return delegate.getString(section + "." + key)
                           .map(Long::parseLong)
                           .toResult(MISSING_KEY);
        }

        @Override
        public Result<Double> requireDouble(String section, String key) {
            return delegate.getString(section + "." + key)
                           .map(Double::parseDouble)
                           .toResult(MISSING_KEY);
        }

        @Override
        public Result<Boolean> requireBoolean(String section, String key) {
            return delegate.getBoolean(section + "." + key)
                           .toResult(MISSING_KEY);
        }

        private static final Cause STRING_LIST_NOT_SUPPORTED = Causes.cause("String list config not supported via legacy ConfigService adapter");

        @Override
        public Result<List<String>> requireStringList(String section, String key) {
            return STRING_LIST_NOT_SUPPORTED.result();
        }

        @Override
        public Option<String> getString(String section, String key) {
            return delegate.getString(section + "." + key);
        }

        @Override
        public Option<Integer> getInt(String section, String key) {
            return delegate.getInt(section + "." + key);
        }

        @Override
        public Option<Long> getLong(String section, String key) {
            return delegate.getString(section + "." + key)
                           .map(Long::parseLong);
        }

        @Override
        public Option<Double> getDouble(String section, String key) {
            return delegate.getString(section + "." + key)
                           .map(Double::parseDouble);
        }

        @Override
        public Option<Boolean> getBoolean(String section, String key) {
            return delegate.getBoolean(section + "." + key);
        }
    }

    TimeSpan DEFAULT_ACTIVATION_CHAIN_TIMEOUT = TimeSpan.timeSpan(120_000).millis();
    TimeSpan DEFAULT_TRANSITION_RETRY_DELAY = TimeSpan.timeSpan(2000).millis();

    static NodeDeploymentManager nodeDeploymentManager(NodeId self,
                                                       MessageRouter router,
                                                       SliceStore sliceStore,
                                                       ClusterNode<KVCommand<AetherKey>> cluster,
                                                       KVStore<AetherKey, AetherValue> kvStore,
                                                       InvocationHandler invocationHandler) {
        return nodeDeploymentManager(self,
                                     new NodeAddress("", 0),
                                     router,
                                     sliceStore,
                                     cluster,
                                     kvStore,
                                     invocationHandler,
                                     SliceActionConfig.sliceActionConfig(),
                                     SliceCodec.sliceCodec(List.of()),
                                     Option.none(),
                                     Option.none(),
                                     DEFAULT_ACTIVATION_CHAIN_TIMEOUT,
                                     DEFAULT_TRANSITION_RETRY_DELAY);
    }

    static NodeDeploymentManager nodeDeploymentManager(NodeId self,
                                                       NodeAddress selfAddress,
                                                       MessageRouter router,
                                                       SliceStore sliceStore,
                                                       ClusterNode<KVCommand<AetherKey>> cluster,
                                                       KVStore<AetherKey, AetherValue> kvStore,
                                                       InvocationHandler invocationHandler,
                                                       SliceActionConfig configuration,
                                                       SliceCodec nodeCodec,
                                                       Option<HttpRoutePublisher> httpRoutePublisher,
                                                       Option<SliceInvokerFacade> sliceInvokerFacade) {
        return nodeDeploymentManager(self,
                                     selfAddress,
                                     router,
                                     sliceStore,
                                     cluster,
                                     kvStore,
                                     invocationHandler,
                                     configuration,
                                     nodeCodec,
                                     httpRoutePublisher,
                                     sliceInvokerFacade,
                                     DEFAULT_ACTIVATION_CHAIN_TIMEOUT,
                                     DEFAULT_TRANSITION_RETRY_DELAY);
    }

    static NodeDeploymentManager nodeDeploymentManager(NodeId self,
                                                       NodeAddress selfAddress,
                                                       MessageRouter router,
                                                       SliceStore sliceStore,
                                                       ClusterNode<KVCommand<AetherKey>> cluster,
                                                       KVStore<AetherKey, AetherValue> kvStore,
                                                       InvocationHandler invocationHandler,
                                                       SliceActionConfig configuration,
                                                       SliceCodec nodeCodec,
                                                       Option<HttpRoutePublisher> httpRoutePublisher,
                                                       Option<SliceInvokerFacade> sliceInvokerFacade,
                                                       TimeSpan activationChainTimeout,
                                                       TimeSpan transitionRetryDelay) {
        return nodeDeploymentManager(self,
                                     selfAddress,
                                     router,
                                     sliceStore,
                                     cluster,
                                     kvStore,
                                     invocationHandler,
                                     configuration,
                                     nodeCodec,
                                     httpRoutePublisher,
                                     sliceInvokerFacade,
                                     activationChainTimeout,
                                     transitionRetryDelay,
                                     Option::none);
    }

    static NodeDeploymentManager nodeDeploymentManager(NodeId self,
                                                       NodeAddress selfAddress,
                                                       MessageRouter router,
                                                       SliceStore sliceStore,
                                                       ClusterNode<KVCommand<AetherKey>> cluster,
                                                       KVStore<AetherKey, AetherValue> kvStore,
                                                       InvocationHandler invocationHandler,
                                                       SliceActionConfig configuration,
                                                       SliceCodec nodeCodec,
                                                       Option<HttpRoutePublisher> httpRoutePublisher,
                                                       Option<SliceInvokerFacade> sliceInvokerFacade,
                                                       TimeSpan activationChainTimeout,
                                                       TimeSpan transitionRetryDelay,
                                                       Supplier<Option<Epoch>> currentEpochSupplier) {
        var ctx = buildContext(self,
                               selfAddress,
                               router,
                               sliceStore,
                               cluster,
                               kvStore,
                               invocationHandler,
                               configuration,
                               nodeCodec,
                               httpRoutePublisher,
                               sliceInvokerFacade,
                               activationChainTimeout,
                               transitionRetryDelay,
                               currentEpochSupplier);

        return new DeploymentManagerAdapter(ctx);
    }

    static NodeDeploymentManager nodeDeploymentManagerFromSnapshot(NodeId self,
                                                                   NodeAddress selfAddress,
                                                                   MessageRouter router,
                                                                   SliceStore sliceStore,
                                                                   ClusterNode<KVCommand<AetherKey>> cluster,
                                                                   KVStore<AetherKey, AetherValue> kvStore,
                                                                   InvocationHandler invocationHandler,
                                                                   SliceActionConfig configuration,
                                                                   SliceCodec nodeCodec,
                                                                   Option<HttpRoutePublisher> httpRoutePublisher,
                                                                   Option<SliceInvokerFacade> sliceInvokerFacade,
                                                                   TimeSpan activationChainTimeout,
                                                                   TimeSpan transitionRetryDelay,
                                                                   Supplier<Epoch> observedEpochSupplier) {
        return nodeDeploymentManager(self,
                                     selfAddress,
                                     router,
                                     sliceStore,
                                     cluster,
                                     kvStore,
                                     invocationHandler,
                                     configuration,
                                     nodeCodec,
                                     httpRoutePublisher,
                                     sliceInvokerFacade,
                                     activationChainTimeout,
                                     transitionRetryDelay,
                                     () -> Option.some(observedEpochSupplier.get()));
    }

    private static NodeDeploymentContext buildContext(NodeId self,
                                                      NodeAddress selfAddress,
                                                      MessageRouter router,
                                                      SliceStore sliceStore,
                                                      ClusterNode<KVCommand<AetherKey>> cluster,
                                                      KVStore<AetherKey, AetherValue> kvStore,
                                                      InvocationHandler invocationHandler,
                                                      SliceActionConfig configuration,
                                                      SliceCodec nodeCodec,
                                                      Option<HttpRoutePublisher> httpRoutePublisher,
                                                      Option<SliceInvokerFacade> sliceInvokerFacade,
                                                      TimeSpan activationChainTimeout,
                                                      TimeSpan transitionRetryDelay,
                                                      Supplier<Option<Epoch>> currentEpochSupplier) {
        var ctxHolder = new AtomicReference<NodeDeploymentContext>();
        Function<Fsm<NodeDeploymentState, ClusterFsmEvent>, NodeDeploymentState> initialStateFactory = fsm -> buildContextAndDormant(fsm,
                                                                                                                                     ctxHolder,
                                                                                                                                     self,
                                                                                                                                     selfAddress,
                                                                                                                                     router,
                                                                                                                                     sliceStore,
                                                                                                                                     cluster,
                                                                                                                                     kvStore,
                                                                                                                                     invocationHandler,
                                                                                                                                     configuration,
                                                                                                                                     nodeCodec,
                                                                                                                                     httpRoutePublisher,
                                                                                                                                     sliceInvokerFacade,
                                                                                                                                     activationChainTimeout,
                                                                                                                                     transitionRetryDelay,
                                                                                                                                     currentEpochSupplier);
        var _fsm = Fsm.fsm("node-deployment", self.id(), initialStateFactory);

        return ctxHolder.get();
    }

    private static NodeDeploymentState buildContextAndDormant(Fsm<NodeDeploymentState, ClusterFsmEvent> fsm,
                                                              AtomicReference<NodeDeploymentContext> ctxHolder,
                                                              NodeId self,
                                                              NodeAddress selfAddress,
                                                              MessageRouter router,
                                                              SliceStore sliceStore,
                                                              ClusterNode<KVCommand<AetherKey>> cluster,
                                                              KVStore<AetherKey, AetherValue> kvStore,
                                                              InvocationHandler invocationHandler,
                                                              SliceActionConfig configuration,
                                                              SliceCodec nodeCodec,
                                                              Option<HttpRoutePublisher> httpRoutePublisher,
                                                              Option<SliceInvokerFacade> sliceInvokerFacade,
                                                              TimeSpan activationChainTimeout,
                                                              TimeSpan transitionRetryDelay,
                                                              Supplier<Option<Epoch>> currentEpochSupplier) {
        var ctx = new NodeDeploymentContext(fsm,
                                            self,
                                            selfAddress,
                                            sliceStore,
                                            configuration,
                                            nodeCodec,
                                            cluster,
                                            kvStore,
                                            invocationHandler,
                                            router,
                                            httpRoutePublisher,
                                            sliceInvokerFacade,
                                            activationChainTimeout,
                                            transitionRetryDelay,
                                            currentEpochSupplier);

        ctxHolder.set(ctx);

        return ctx.dormant();
    }

    final class DeploymentManagerAdapter implements NodeDeploymentManager {
        private static final Logger log = LoggerFactory.getLogger(DeploymentManagerAdapter.class);

        private final NodeDeploymentContext ctx;
        private final AtomicReference<Option<Runnable>> selfReadySignal = new AtomicReference<>(Option.none());

        DeploymentManagerAdapter(NodeDeploymentContext ctx) {
            this.ctx = ctx;
            ctx.setActiveOnEntryCallback(this::onActiveEntry);
        }

        @Contract
        @Override
        public void setSelfReadySignal(Runnable signal) {
            selfReadySignal.set(Option.some(signal));
        }

        @Contract
        private void onActiveEntry() {
            var signal = selfReadySignal.get();

            if (signal.isEmpty()) {
                log.warn("Node {} active-entry without self-ready signal — node-lifecycle hook must be wired",
                         ctx.self().id());

                return;
            }

            log.info("Node {} signalling self-ready to node-lifecycle hook",
                     ctx.self().id());
            signal.unwrap().run();
        }

        @Contract
        @Override
        public void onQuorumStateChange(ClusterStateNotification quorumStateNotification) {
            if (!quorumStateNotification.advanceSequence(ctx.quorumSequence())) {
                log.info("Node {} ignoring stale ClusterStateNotification: {}",
                         ctx.self().id(),
                         quorumStateNotification);

                return;
            }

            log.info("Node {} received ClusterStateNotification: {}",
                     ctx.self().id(),
                     quorumStateNotification);
            switch (quorumStateNotification.state()) {
                case ACTIVE -> dispatchQuorumEstablished();
                case PASSIVE -> ctx.dispatch(new ClusterFsmEvent.QuorumDisappeared());
            }
        }

        private void dispatchQuorumEstablished() {
            ctx.dispatch(new ClusterFsmEvent.QuorumEstablished());
        }

        @Contract
        @Override
        public void onNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut) {
            ctx.dispatch(new NodeArtifactPutReceived(valuePut));
        }

        @Contract
        @Override
        public void onNodeArtifactRemove(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove) {
            ctx.dispatch(new NodeArtifactRemoveReceived(valueRemove));
        }

        @Contract
        @Override
        public void onNodeRoutesPut(ValuePut<NodeRoutesKey, NodeRoutesValue> valuePut) {
            ctx.dispatch(new NodeRoutesPutReceived(valuePut));
        }

        @Override
        public boolean isActive() {
            return ctx.isActive();
        }

        @Contract
        @Override
        public void reconcileActivation() {
            if (!ctx.isDormant()) {
                return;
            }

            log.info("Node {} still Dormant under live consensus-active — re-dispatching QuorumEstablished "
                    + "(ACTIVE edge was dropped); healing activation",
                     ctx.self().id());
            dispatchQuorumEstablished();
        }

        /// RC1 Step 2: replaces the retired `onNodeLifecyclePut`. The self-shutdown
        /// trigger fires when `TopologyObserver` projects a SHUTTING_DOWN lifecycle
        /// transition for this node — see `MembershipDecision.NodeShuttingDown`.
        @Contract
        @Override
        public void onMembershipDecision(MembershipDecision decision) {
            if (decision instanceof MembershipDecision.NodeShuttingDown nodeShuttingDown && nodeShuttingDown.nodeId().equals(ctx.self())) {
                log.warn("Node {} received SHUTTING_DOWN lifecycle decision — initiating shutdown",
                         ctx.self().id());
                ctx.shutdownCallback().onPresent(Runnable::run);
            }
        }

        @Contract
        @Override
        public void setShutdownCallback(Runnable callback) {
            ctx.setShutdownCallback(callback);
        }
    }
}
