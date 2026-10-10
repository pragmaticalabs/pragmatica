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

/// #822 round 2 — see [#resolveStreamName_documentedStreamSectionWithScalarRetention_resolvesTheStreamName].
class NodeDeploymentStateStreamNameResolutionTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:slice-a:1.0.0").unwrap();
    
    private FsmTestHarness<NodeDeploymentState, ClusterFsmEvent> harness;

    @BeforeEach
    void setUp() {
        ConfigService.setInstance(realBinderOver(DOCUMENTED_STREAM_TOML));

        var router = MessageRouter.mutable();
        KVStore<AetherKey, AetherValue> kvStore = new KVStore<>(router, stubSerializer(), stubDeserializer());
        ClusterNode<KVCommand<AetherKey>> cluster = stubClusterNode(SELF);
        SliceStore sliceStore = stubSliceStore();
        var ctxHolder = new AtomicReference<NodeDeploymentContext>();
        Function<Fsm<NodeDeploymentState, ClusterFsmEvent>, NodeDeploymentState> factory =
                fsm -> buildContext(fsm, ctxHolder, router, kvStore, cluster, sliceStore);
        harness = FsmTestHarness.harness("ndm-stream-name-resolution-test-" + SELF.id(), factory);
        harness.dispatch(new QuorumEstablished());
    }

    @AfterEach
    void tearDown() {
        // `sliceConfigService` falls back to this process-global singleton whenever the stub
        // SliceStore reports no slice composite (as it always does here) — must not leak into
        // other test classes sharing the JVM.
        ConfigService.clear();
    }


    /// The `[streams.*]` section of `aether/tests/blueprints/test-persistence/.../resources.toml`, verbatim: the
    /// documented shorthand `retention = "time"` is a SCALAR key at the path where `StreamConfig` declares a
    /// `RetentionPolicy` record component.
    private static final String DOCUMENTED_STREAM_TOML = """
            [streams.test-events]
            partitions = 4
            retention = "time"
            retention-value = "5m"
            max-event-size = "64KB"
            """;

    /// #822 round 2 — `resolveStreamName` binds `StreamConfig` through the generic binder. `hasSection` used to
    /// match the scalar key `retention` itself as the section `streams.test-events.retention`, so the binder tried
    /// to bind `RetentionPolicy` there. That failed with `SectionNotFound` (read as "absent", so the record's
    /// default applied) until the binder started reporting a missing required field; it then surfaced as
    /// `MissingField streams.test-events.retention.max_count`, the consumer was "could NOT be registered", and the
    /// slice activated anyway with a declarative consumer that received nothing.
    @Test
    void resolveStreamName_documentedStreamSectionWithScalarRetention_resolvesTheStreamName() {
        var resolved = invokeResolveStreamName(activeState(), "streams.test-events");

        assertThat(resolved.isSuccess()).as("a documented stream section must resolve, got: %s", resolved).isTrue();
        resolved.onSuccess(name -> assertThat(name).isEqualTo("test-events"));
    }

    private NodeDeploymentState.Active activeState() {
        assertThat(harness.state()).isInstanceOf(NodeDeploymentState.Active.class);

        return (NodeDeploymentState.Active) harness.state();
    }

    private static ConfigService realBinderOver(String toml) {
        var source = TomlConfigSource.tomlConfigSource(toml).unwrap();

        return ProviderBasedConfigService.providerBasedConfigService(ConfigurationProvider.builder().withSource(source).build());
    }

    /// One-time reflection bridge onto the `private` `Active#resolveStreamName`, the same rationale as the sibling
    /// suites: it is the production call that turns a manifest section into a stream name.
    @SuppressWarnings("unchecked")
    private static Result<String> invokeResolveStreamName(NodeDeploymentState.Active active, String section) {
        try {
            Method m = NodeDeploymentState.Active.class.getDeclaredMethod("resolveStreamName", Artifact.class, String.class);
            m.setAccessible(true);

            return (Result<String>) m.invoke(active, ARTIFACT, section);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to invoke resolveStreamName: " + e.getMessage(), e);
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
