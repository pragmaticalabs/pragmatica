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
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.statemachine.Fsm;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


@SuppressWarnings("JBCT-RET-01")
// MessageReceiver callbacks — void required by messaging framework
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

    @MessageReceiver void onQuorumStateChange(QuorumStateNotification quorumStateNotification);
    @MessageReceiver void onNodeLifecyclePut(ValuePut<NodeLifecycleKey, NodeLifecycleValue> valuePut);
    @MessageReceiver void onNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut);
    @MessageReceiver void onNodeArtifactRemove(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove);
    @MessageReceiver void onNodeLifecycleRemove(ValueRemove<NodeLifecycleKey, NodeLifecycleValue> valueRemove);
    @MessageReceiver void onNodeRoutesPut(ValuePut<NodeRoutesKey, NodeRoutesValue> valuePut);
    void setShutdownCallback(Runnable callback);
    boolean isActive();

    ConfigFacade NO_OP_CONFIG = new NoOpDeploymentConfigFacade();

    record NoOpDeploymentConfigFacade() implements ConfigFacade {
        private static final Cause NO_CONFIG = Causes.cause("Config service not available");

        @Override public Result<String> requireString(String section, String key) {
            return NO_CONFIG.result();
        }

        @Override public Result<Integer> requireInt(String section, String key) {
            return NO_CONFIG.result();
        }

        @Override public Result<Long> requireLong(String section, String key) {
            return NO_CONFIG.result();
        }

        @Override public Result<Double> requireDouble(String section, String key) {
            return NO_CONFIG.result();
        }

        @Override public Result<Boolean> requireBoolean(String section, String key) {
            return NO_CONFIG.result();
        }

        @Override public Result<List<String>> requireStringList(String section, String key) {
            return NO_CONFIG.result();
        }

        @Override public Option<String> getString(String section, String key) {
            return Option.none();
        }

        @Override public Option<Integer> getInt(String section, String key) {
            return Option.none();
        }

        @Override public Option<Long> getLong(String section, String key) {
            return Option.none();
        }

        @Override public Option<Double> getDouble(String section, String key) {
            return Option.none();
        }

        @Override public Option<Boolean> getBoolean(String section, String key) {
            return Option.none();
        }
    }

    @SuppressWarnings("JBCT-UTIL-02") static ConfigFacade configServiceToFacade(ConfigService svc) {
        return new ConfigServiceConfigFacade(svc);
    }

    record ConfigServiceConfigFacade(ConfigService delegate) implements ConfigFacade {
        private static final Cause MISSING_KEY = Causes.cause("Required config key not found");

        @Override public Result<String> requireString(String section, String key) {
            return delegate.getString(section + "." + key).toResult(MISSING_KEY);
        }

        @Override public Result<Integer> requireInt(String section, String key) {
            return delegate.getInt(section + "." + key).toResult(MISSING_KEY);
        }

        @Override public Result<Long> requireLong(String section, String key) {
            return delegate.getString(section + "." + key).map(Long::parseLong)
                                     .toResult(MISSING_KEY);
        }

        @Override public Result<Double> requireDouble(String section, String key) {
            return delegate.getString(section + "." + key).map(Double::parseDouble)
                                     .toResult(MISSING_KEY);
        }

        @Override public Result<Boolean> requireBoolean(String section, String key) {
            return delegate.getBoolean(section + "." + key).toResult(MISSING_KEY);
        }

        private static final Cause STRING_LIST_NOT_SUPPORTED = Causes.cause("String list config not supported via legacy ConfigService adapter");

        @Override public Result<List<String>> requireStringList(String section, String key) {
            return STRING_LIST_NOT_SUPPORTED.result();
        }

        @Override public Option<String> getString(String section, String key) {
            return delegate.getString(section + "." + key);
        }

        @Override public Option<Integer> getInt(String section, String key) {
            return delegate.getInt(section + "." + key);
        }

        @Override public Option<Long> getLong(String section, String key) {
            return delegate.getString(section + "." + key).map(Long::parseLong);
        }

        @Override public Option<Double> getDouble(String section, String key) {
            return delegate.getString(section + "." + key).map(Double::parseDouble);
        }

        @Override public Option<Boolean> getBoolean(String section, String key) {
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
                               transitionRetryDelay);
        return new DeploymentManagerAdapter(ctx);
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
                                                      TimeSpan transitionRetryDelay) {
        var ctxHolder = new AtomicReference<NodeDeploymentContext>();
        Function<Fsm<NodeDeploymentState, ClusterFsmEvent>, NodeDeploymentState> initialStateFactory =
                fsm -> buildContextAndDormant(fsm,
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
                                              transitionRetryDelay);
        Fsm.fsm("node-deployment-" + self.id(), initialStateFactory);
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
                                                              TimeSpan transitionRetryDelay) {
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
                                            transitionRetryDelay);
        ctxHolder.set(ctx);
        return ctx.dormant();
    }

    /// Thin adapter that bridges the legacy `@MessageReceiver` entry points onto the FSM event
    /// channel. Holds nothing but the `NodeDeploymentContext` — all state lives on
    /// [`NodeDeploymentState`].
    final class DeploymentManagerAdapter implements NodeDeploymentManager {
        private static final Logger log = LoggerFactory.getLogger(DeploymentManagerAdapter.class);

        private static final int MAX_LIFECYCLE_RETRIES = 60;

        private final NodeDeploymentContext ctx;

        DeploymentManagerAdapter(NodeDeploymentContext ctx) {
            this.ctx = ctx;
        }

        @Override public void onQuorumStateChange(QuorumStateNotification quorumStateNotification) {
            if (!quorumStateNotification.advanceSequence(ctx.quorumSequence())) {
                log.info("Node {} ignoring stale QuorumStateNotification: {}",
                         ctx.self().id(),
                         quorumStateNotification);
                return;
            }
            log.info("Node {} received QuorumStateNotification: {}",
                     ctx.self().id(),
                     quorumStateNotification);
            switch (quorumStateNotification.state()) {
                case ESTABLISHED -> dispatchQuorumEstablished();
                case DISAPPEARED -> ctx.dispatch(new ClusterFsmEvent.QuorumDisappeared());
            }
        }

        private void dispatchQuorumEstablished() {
            ctx.dispatch(new ClusterFsmEvent.QuorumEstablished());
            if (ctx.fsm().current() instanceof NodeDeploymentState.Active) {registerLifecycleOnDuty();}
        }

        @Override public void onNodeArtifactPut(ValuePut<NodeArtifactKey, NodeArtifactValue> valuePut) {
            ctx.dispatch(new NodeArtifactPutReceived(valuePut));
        }

        @Override public void onNodeArtifactRemove(ValueRemove<NodeArtifactKey, NodeArtifactValue> valueRemove) {
            ctx.dispatch(new NodeArtifactRemoveReceived(valueRemove));
        }

        @Override public void onNodeRoutesPut(ValuePut<NodeRoutesKey, NodeRoutesValue> valuePut) {
            ctx.dispatch(new NodeRoutesPutReceived(valuePut));
        }

        @Override public boolean isActive() {
            return ctx.isActive();
        }

        @Override public void onNodeLifecyclePut(ValuePut<NodeLifecycleKey, NodeLifecycleValue> valuePut) {
            var key = valuePut.cause().key();
            var value = valuePut.cause().value();
            if (key.nodeId().equals(ctx.self()) && value.state() == NodeLifecycleState.SHUTTING_DOWN) {
                log.warn("Node {} received SHUTTING_DOWN lifecycle state — initiating shutdown",
                         ctx.self().id());
                ctx.shutdownCallback().onPresent(Runnable::run);
            }
        }

        @Override public void onNodeLifecycleRemove(ValueRemove<NodeLifecycleKey, NodeLifecycleValue> valueRemove) {
            var key = valueRemove.cause().key();
            if (key.nodeId().equals(ctx.self()) && isActive()) {
                log.warn("Node {} lifecycle key removed unexpectedly — re-registering ON_DUTY",
                         ctx.self().id());
                registerLifecycleOnDuty();
            }
        }

        @Override public void setShutdownCallback(Runnable callback) {
            ctx.setShutdownCallback(callback);
        }

        private void registerLifecycleOnDuty() {
            var lifecycleKey = NodeLifecycleKey.nodeLifecycleKey(ctx.self());
            ctx.kvStore().get(lifecycleKey)
                      .flatMap(v -> v instanceof NodeLifecycleValue lv
                                   ? Option.some(lv)
                                   : Option.empty())
                      .filter(v -> v.state() == NodeLifecycleState.DECOMMISSIONED)
                      .onEmpty(() -> writeLifecycleOnDuty(lifecycleKey, 1));
        }

        private void writeLifecycleOnDuty(NodeLifecycleKey lifecycleKey, int attempt) {
            var value = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY,
                                                              ctx.selfAddress().host(),
                                                              ctx.selfAddress().port(),
                                                              detectProvisioningSource());
            ctx.cluster().apply(List.of(new KVCommand.Put<>(lifecycleKey, value)))
                      .onSuccess(_ -> log.info("Node {} registered lifecycle state: ON_DUTY (source={})",
                                               ctx.self().id(),
                                               value.provisioningSource()))
                      .onFailure(cause -> retryLifecycleOnDuty(lifecycleKey, attempt, cause));
        }

        private static AetherValue.ProvisioningSource detectProvisioningSource() {
            var raw = Option.option(System.getenv("AETHER_PROVISIONED_BY"))
                                        .filter(v -> !v.isBlank())
                                        .map(String::trim)
                                        .map(String::toLowerCase);
            return raw.map(DeploymentManagerAdapter::provisioningSourceFrom).or(AetherValue.ProvisioningSource.MANUAL);
        }

        private static AetherValue.ProvisioningSource provisioningSourceFrom(String raw) {
            return switch (raw) {
                case "ctm" -> AetherValue.ProvisioningSource.CTM;
                case "manual" -> AetherValue.ProvisioningSource.MANUAL;
                default -> AetherValue.ProvisioningSource.UNKNOWN;
            };
        }

        @Contract private void retryLifecycleOnDuty(NodeLifecycleKey lifecycleKey, int attempt, Cause cause) {
            if (attempt >= MAX_LIFECYCLE_RETRIES) {
                log.error("Node {} failed to register lifecycle ON_DUTY after {} attempts: {}",
                          ctx.self().id(),
                          attempt,
                          cause.message());
                return;
            }
            if (!isActive()) {
                log.debug("Node {} skipping ON_DUTY retry — no longer active", ctx.self().id());
                return;
            }
            log.warn("Node {} failed to register lifecycle ON_DUTY (attempt {}/{}): {} — retrying in 2s",
                     ctx.self().id(),
                     attempt,
                     MAX_LIFECYCLE_RETRIES,
                     cause.message());
            SharedScheduler.schedule(() -> writeLifecycleOnDuty(lifecycleKey, attempt + 1), timeSpan(2).seconds());
        }
    }
}
