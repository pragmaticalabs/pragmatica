// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.fsm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.resource.ScheduleConfig;
import org.pragmatica.aether.slice.ExecutionMode;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.ProviderBasedConfigService;
import org.pragmatica.config.source.TomlConfigSource;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.WarningLevel;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.QuorumEstablished;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1935 — see [#unregistrableConsumer_raisesAnOperatorWarning_andTheOtherConsumerStillRegisters].
class NodeDeploymentStateStreamConsumerRegistrationTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:slice-a:1.0.0").unwrap();
    
    private FsmTestHarness<NodeDeploymentState, ClusterFsmEvent> harness;
    private AtomicReference<NodeDeploymentContext> ctxHolder;
    private final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        ConfigService.setInstance(realBinderOver(STREAMS_TOML));

        var router = MessageRouter.mutable();
        KVStore<AetherKey, AetherValue> kvStore = new KVStore<>(router, stubSerializer(), stubDeserializer());
        ClusterNode<KVCommand<AetherKey>> cluster = stubClusterNode(SELF);
        SliceStore sliceStore = stubSliceStore();
        ctxHolder = new AtomicReference<>();
        Function<Fsm<NodeDeploymentState, ClusterFsmEvent>, NodeDeploymentState> factory =
                fsm -> buildContext(fsm, ctxHolder, router, kvStore, cluster, sliceStore);
        harness = FsmTestHarness.harness("ndm-stream-consumer-registration-test-" + SELF.id(), factory);
        ctxHolder.get().setOperatorWarningSink(OperatorWarningSink.handingOffTo(warnings::add));
        harness.dispatch(new QuorumEstablished());
    }

    @AfterEach
    void tearDown() {
        // `sliceConfigService` falls back to this process-global singleton whenever the stub
        // SliceStore reports no slice composite (as it always does here) — must not leak into
        // other test classes sharing the JVM.
        ConfigService.clear();
    }


    /// `streams.orders` declares a stream; `streams.ghost` is declared by NO section, so its consumer cannot register.
    private static final String STREAMS_TOML = """
            [streams.orders]
            partitions = 4
            """;

    /// #1935 — a declared consumer that cannot be registered receives nothing while the slice activates anyway. The
    /// log line was the only report. It is now also a CRITICAL operator warning naming the slice, method, section and
    /// cause; the slice still activates, and the consumer that CAN register is unaffected (the choice is documented on
    /// `NodeDeploymentState#raiseConsumerNotRegistered`).
    @Test
    void unregistrableConsumer_raisesAnOperatorWarning_andTheOtherConsumerStillRegisters() throws Exception {
        var registered = invokeReadStreamSubscriptions(activeState(), new StreamConsumerStubSlice());

        assertThat(registered).as("the consumer on a declared section still registers; the slice is not blocked").hasSize(1);
        assertThat(awaitWarnings(1)).hasSize(1);

        var warning = warnings.getFirst();

        assertThat(warning.code()).isEqualTo(OperatorWarningCode.STREAM_CONSUMER_NOT_REGISTERED);
        assertThat(warning.code().level()).isEqualTo(WarningLevel.CRITICAL);
        assertThat(warning.subject()).contains(ARTIFACT.asString()).contains("onGhost").contains("streams.ghost");
        assertThat(warning.message()).contains("could NOT be registered").contains("streams.ghost");
    }

    /// The control: nothing is warned about when every declared consumer registers.
    @Test
    void registrableConsumersOnly_raiseNothing() throws Exception {
        ConfigService.setInstance(realBinderOver(STREAMS_TOML + "\n[streams.ghost]\npartitions = 2\n"));

        var registered = invokeReadStreamSubscriptions(activeState(), new StreamConsumerStubSlice());

        assertThat(registered).hasSize(2);
        assertThat(awaitWarnings(0)).isEmpty();
    }

    private List<OperatorWarning> awaitWarnings(int expected) throws InterruptedException {
        var deadline = System.currentTimeMillis() + 2_000;

        while (warnings.size() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        Thread.sleep(100);

        return List.copyOf(warnings);
    }

    private NodeDeploymentState.Active activeState() {
        assertThat(harness.state()).isInstanceOf(NodeDeploymentState.Active.class);

        return (NodeDeploymentState.Active) harness.state();
    }

    private static ConfigService realBinderOver(String toml) {
        var source = TomlConfigSource.tomlConfigSource(toml).unwrap();

        return ProviderBasedConfigService.providerBasedConfigService(ConfigurationProvider.builder().withSource(source).build());
    }

    /// One-time reflection bridge onto the `private` `Active#readStreamSubscriptionsFromManifest`, the same rationale as
    /// the sibling suites: it is the production loop that registers (or fails to register) each declared consumer.
    @SuppressWarnings("unchecked")
    private static List<?> invokeReadStreamSubscriptions(NodeDeploymentState.Active active, Slice slice) {
        try {
            Method m = NodeDeploymentState.Active.class.getDeclaredMethod("readStreamSubscriptionsFromManifest", Artifact.class, Slice.class);
            m.setAccessible(true);

            return (List<?>) m.invoke(active, ARTIFACT, slice);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to invoke readStreamSubscriptionsFromManifest: " + e.getMessage(), e);
        }
    }

    /// A non-`Slice` marker interface whose only job is to name `META-INF/slice/StreamConsumerMarkerSlice.manifest`.
    private interface StreamConsumerMarkerSlice {}

    private static final class StreamConsumerStubSlice implements Slice, StreamConsumerMarkerSlice {
        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of();
        }
    }

    private NodeDeploymentState buildContext(Fsm<NodeDeploymentState, ClusterFsmEvent> fsm,
                                             AtomicReference<NodeDeploymentContext> ctxHolder,
                                             MessageRouter router,
                                             KVStore<AetherKey, AetherValue> store,
                                             ClusterNode<KVCommand<AetherKey>> cluster,
                                             SliceStore sliceStore) {
        var context = new NodeDeploymentContext(fsm,
                                                SELF,
                                                new NodeAddress("localhost", 9000),
                                                sliceStore,
                                                SliceActionConfig.sliceActionConfig(),
                                                SliceCodec.sliceCodec(List.of()),
                                                cluster,
                                                store,
                                                stubInvocationHandler(),
                                                router,
                                                Option.none(),
                                                Option.none(),
                                                timeSpan(120_000).millis(),
                                                timeSpan(2_000).millis());

        ctxHolder.set(context);

        return context.dormant();
    }

    private static SliceStore stubSliceStore() {
        return new SliceStore() {
            @Override public List<LoadedSlice> loaded() {
                return List.of();
            }

            @Override public Promise<LoadedSlice> loadSlice(Artifact artifact) {
                return org.pragmatica.lang.utils.Causes.cause("stub").promise();
            }

            @Override public Promise<LoadedSlice> activateSlice(Artifact artifact) {
                return org.pragmatica.lang.utils.Causes.cause("stub").promise();
            }

            @Override public Promise<LoadedSlice> deactivateSlice(Artifact artifact) {
                return org.pragmatica.lang.utils.Causes.cause("stub").promise();
            }

            @Override public Promise<Unit> unloadSlice(Artifact artifact) {
                return Promise.unitPromise();
            }

            @Override public Option<org.pragmatica.config.ConfigurationProvider> sliceComposite(Artifact artifact) {
                // No slice composite → `sliceConfigService` falls back to the global
                // `ConfigService.instance()` singleton this test installs in `setUp`.
                return Option.none();
            }
        };
    }

    private static ClusterNode<KVCommand<AetherKey>> stubClusterNode(NodeId self) {
        return new ClusterNode<>() {
            @Override public NodeId self() {
                return self;
            }

            @Override public TopologyManager topologyManager() {
                return stubTopologyManager(self);
            }

            @Override public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
                return Promise.success(Collections.emptyList());
            }
        };
    }

    private static TopologyManager stubTopologyManager(NodeId self) {
        return new TopologyManager() {
            @Override public NodeInfo self() {
                return NodeInfo.nodeInfo(self, new NodeAddress("localhost", 9000));
            }

            @Override public Option<NodeInfo> get(NodeId id) {
                return Option.some(NodeInfo.nodeInfo(id, new NodeAddress("localhost", 9000)));
            }

            @Override public int clusterSize() {
                return 1;
            }

            @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {
                return Option.empty();
            }

            @Override public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override public TimeSpan pingInterval() {
                return timeSpan(5).seconds();
            }

            @Override public TimeSpan helloTimeout() {
                return timeSpan(5).seconds();
            }

            @Override public Option<NodeState> getState(NodeId id) {
                return Option.empty();
            }

            @Override public List<NodeId> topology() {
                return List.of(self);
            }
        };
    }

    private static org.pragmatica.aether.invoke.InvocationHandler stubInvocationHandler() {
        return new org.pragmatica.aether.invoke.InvocationHandler() {
            @Override public void onInvokeRequest(org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest request) {}

            @Override public void registerSlice(Artifact artifact, org.pragmatica.aether.slice.SliceBridge bridge) {}

            @Override public void unregisterSlice(Artifact artifact) {}

            @Override public Option<org.pragmatica.aether.slice.SliceBridge> localSlice(Artifact artifact) {
                return Option.none();
            }

            @Override public Option<org.pragmatica.aether.slice.SliceBridge> findBridgeByClassLoader(ClassLoader classLoader) {
                return Option.none();
            }

            @Override public Option<org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector> metricsCollector() {
                return Option.none();
            }
        };
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
