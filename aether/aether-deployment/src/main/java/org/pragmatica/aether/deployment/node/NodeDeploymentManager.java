// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentContext;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentEvents.ConfigChanged;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentEvents.NodeArtifactPutReceived;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentEvents.NodeArtifactRemoveReceived;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentEvents.NodeRoutesPutReceived;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentEvents.SliceTargetPutReceived;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentEvents.VersionRoutingPutReceived;
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
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.VersionRoutingKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.VersionRoutingValue;
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
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.statemachine.Fsm;

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

    /// #381 — a `ConfigKey` change applied to this node's dynamic config overlay (`DynamicConfigManager`
    /// calls this after the overlay is updated, so a slice reading through its facade sees the new
    /// value). Pushed to the registered slices' `notifyConfigUpdate` only while ACTIVE; every other
    /// state ignores it, and the slices re-read at their next activation anyway.
    @Contract
    void onConfigChanged(String changedKey);

    /// #1068: a start refused for want of a committed target is deferred, and these two observations
    /// are what re-evaluate it.
    @Contract
    @MessageReceiver
    void onSliceTargetPut(ValuePut<SliceTargetKey, SliceTargetValue> valuePut);

    @Contract
    @MessageReceiver
    void onVersionRoutingPut(ValuePut<VersionRoutingKey, VersionRoutingValue> valuePut);

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

        // ConfigService exposes no numeric getters beyond getInt, so the adapter parses the string
        // itself — through core's Result-returning parsers, not Long.parseLong inside a map, which
        // threw NumberFormatException out of a facade whose whole contract is Result (#276 R20).
        // A malformed value is a named failure on require*, distinct from an absent key; on the
        // Option-returning get* it reads as absent, the same as ConfigurationProvider's own
        // getLong/getDouble behave for the slice-api facade.
        @Override
        public Result<Long> requireLong(String section, String key) {
            return delegate.getString(section + "." + key)
                           .toResult(MISSING_KEY)
                           .flatMap(value -> Number.parseLong(value).mapError(_ -> NOT_A_LONG.apply(section + "." + key,
                                                                                                    value)));
        }

        @Override
        public Result<Double> requireDouble(String section, String key) {
            return delegate.getString(section + "." + key)
                           .toResult(MISSING_KEY)
                           .flatMap(value -> Number.parseDouble(value).mapError(_ -> NOT_A_DOUBLE.apply(section
                                                                                                       + "." + key,
                                                                                                        value)));
        }

        // The parse failure is mapped at the Result boundary to a cause that NAMES the key and the
        // value; Number.parseX's own cause is a Causes.fromThrowable, whose message is the whole
        // stack trace with the key nowhere in it (review of #1092, SF-2).
        private static final Fn2<Cause, String, String> NOT_A_LONG = Causes.forTwoValues("Config key %s is not a long: \"%s\"");

        private static final Fn2<Cause, String, String> NOT_A_DOUBLE = Causes.forTwoValues("Config key %s is not a double: \"%s\"");

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
                           .flatMap(value -> Number.parseLong(value).option());
        }

        @Override
        public Option<Double> getDouble(String section, String key) {
            return delegate.getString(section + "." + key)
                           .flatMap(value -> Number.parseDouble(value).option());
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

        @Contract
        @Override
        public void onConfigChanged(String changedKey) {
            ctx.dispatch(new ConfigChanged(changedKey));
        }

        @Contract
        @Override
        public void onSliceTargetPut(ValuePut<SliceTargetKey, SliceTargetValue> valuePut) {
            ctx.dispatch(new SliceTargetPutReceived(valuePut));
        }

        @Contract
        @Override
        public void onVersionRoutingPut(ValuePut<VersionRoutingKey, VersionRoutingValue> valuePut) {
            ctx.dispatch(new VersionRoutingPutReceived(valuePut));
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
            if (decision instanceof MembershipDecision.NodeShuttingDown nodeShuttingDown && nodeShuttingDown.nodeId()
                                                                                                            .equals(ctx.self())) {
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
