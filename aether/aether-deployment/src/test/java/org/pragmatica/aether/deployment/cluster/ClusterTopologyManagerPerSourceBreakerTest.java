// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.utility.warning.OperatorWarningSink;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.buffer.ByteBuf;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Spec §8.3 — the provisioning circuit breaker is one per source binding. A source whose provisions keep failing opens only its own
/// breaker; another source's replacements are still provisioned. The chain is the production one (committed configuration, source
/// registry, capacity-controlled lifecycle over the source lifecycle), with a provider that refuses creation in the sources named in
/// `failingSources`.
@Timeout(120)
class ClusterTopologyManagerPerSourceBreakerTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId PEER_A = nodeId("node-a").unwrap();
    private static final NodeId PEER_B = nodeId("node-b").unwrap();
    private static final NodeId FROM_FIRST = nodeId("node-from-eu-1").unwrap();
    private static final NodeId FROM_SECOND = nodeId("node-from-eu-2").unwrap();
    private static final NodeId UNPLACED = nodeId("node-unplaced").unwrap();
    private static final Set<NodeId> MEMBERS = Set.of(SELF, PEER_A, PEER_B);
    private static final SourceName FIRST = SourceName.sourceName("eu-1").unwrap();
    private static final SourceName SECOND = SourceName.sourceName("eu-2").unwrap();

    private static final String TWO_CLOUD_SOURCES_TOML = """
            config_version = "1.0.0"

            [cluster]
            name = "prod-cluster"
            version = "1.0.0"

            [source.eu-0]
            type = "cloud"
            provider = "hetzner"
            credentials = "hcloud-token"
            region = "eu-north"
            zones = ["hel1"]

            [source.eu-0.core]
            count = 1
            instance_type = "small"
            image = "test-image"

            [source.eu-1]
            type = "cloud"
            provider = "hetzner"
            credentials = "hcloud-token"
            region = "eu-central"
            zones = ["fsn1"]

            [source.eu-1.core]
            count = 2
            instance_type = "small"
            image = "test-image"

            [source.eu-2]
            type = "cloud"
            provider = "hetzner"
            credentials = "hcloud-token"
            region = "us-east"
            zones = ["ash"]

            [source.eu-2.core]
            count = 1
            instance_type = "small"
            image = "test-image"
            """;

    private final List<ProvisionRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<java.util.function.Function<NodeId, Result<SourceName>>> committedSource =
        new AtomicReference<>(node -> Causes.cause("no placement for " + node.id()).result());
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final Set<String> failingSources = ConcurrentHashMap.newKeySet();
    private ClusterTopologyManager manager;

    @BeforeEach
    void setUp() {
        var snapshotSource = new StubSnapshotSource();
        var config = new TopologyConfig(SELF,
                                        5,
                                        timeSpan(60).seconds(),
                                        timeSpan(1).seconds(),
                                        List.of(NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("10.0.0.1", 6000).unwrap()),
                                                NodeInfo.nodeInfo(PEER_A, NodeAddress.nodeAddress("10.0.0.2", 6000).unwrap()),
                                                NodeInfo.nodeInfo(PEER_B, NodeAddress.nodeAddress("10.0.0.3", 6000).unwrap())));
        var observer = TopologyObserver.topologyObserver(config, quietRouter(), snapshotSource).unwrap();
        var configValue = Option.some(new ClusterConfigValue(Option.some(TWO_CLOUD_SOURCES_TOML),
                                                             "prod-cluster",
                                                             "1.0.0",
                                                             List.of(new AetherValue.TopologyEntry("eu-1", "core", 3)),
                                                             3,
                                                             9,
                                                             "cloud",
                                                             1L,
                                                             System.currentTimeMillis()));
        var kv = new KVStore<StructuredKey, Object>(quietRouter(), noSerializer(), noDeserializer());

        kv.process(kv.createBatch(List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(SELF, 1)),
                                          new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT, configValue.unwrap()))));
        var registry = SourceComputeRegistry.sourceComputeRegistry(() -> configValue,
                                                                   _ -> Result.success(EnvironmentIntegration.withCompute(recordingProvider())));
        var delegate = NodeLifecycleManager.nodeLifecycleManager(registry,
                                                                 node -> committedSource.get().apply(node),
                                                                 Option.none(),
                                                                 Option.none());
        @SuppressWarnings({"rawtypes", "unchecked"})
        var typed = (KVStore<AetherKey, AetherValue>) (Object) kv;
        var capacity = CapacityControlledLifecycle.capacityControlledLifecycle(delegate,
                                                                               SELF,
                                                                               typed,
                                                                               commands -> Promise.success(typed.process(typed.createBatch(commands))),
                                                                               () -> true,
                                                                               () -> 10);

        manager = ClusterTopologyManager.clusterTopologyManager(observer,
                                                                capacity,
                                                                AutoHealConfig.autoHealConfig(timeSpan(1).millis(), AutoHealConfig.DEFAULT_PROVISIONING_TIMEOUT).unwrap(),
                                                                DeploymentMap.deploymentMap(),
                                                                snapshotSource,
                                                                () -> configValue,
                                                                _ -> Promise.success(List.of()),
                                                                () -> ClusterPhase.NORMAL);
        manager.setOperatorWarningSink(OperatorWarningSink.handingOffTo(warning -> events.add(warning.code().code())));
        manager.activate();
    }

    /// Source A (eu-1) failing three times opens A's breaker only; B (eu-2) is still provisioned.
    @Test
    void sourceFailsThreeTimes_otherSourceStillProvisions() {
        placeBoth();
        failingSources.add("eu-1");

        failHeals(FROM_FIRST, 3);
        requests.clear();
        var blocked = heal(Option.some(FROM_FIRST));
        var other = heal(Option.some(FROM_SECOND));

        assertThat(blocked.unwrap()).as("the open source is deferred").isEqualTo(ProvisionDisposition.deferred(ProvisionDisposition.DeferralReason.CIRCUIT_OPEN));
        assertThat(other.unwrap()).as("the other source is not gated by it").isInstanceOf(ProvisionDisposition.Dispatched.class);
        assertThat(requestedSources()).as("the open source never reached the provider").containsExactly("eu-2");
    }

    @Test
    void sourceFailures_areCountedPerSource_andReportedPerSource() {
        placeBoth();
        failingSources.add("eu-1");

        failHeals(FROM_FIRST, 3);
        heal(Option.some(FROM_SECOND));

        var states = manager.circuitBreakerStates();
        assertThat(states.keySet()).as("only a source with a failure has a breaker").containsExactly("eu-1");
        assertThat(states.get("eu-1").consecutiveFailures()).isEqualTo(3);
        assertThat(states.get("eu-1").tripped()).isTrue();
        assertThat(manager.circuitBreakerState().tripped()).as("the aggregate is tripped when any source is").isTrue();
    }

    @Test
    void failuresOfTwoSources_doNotAddUp() {
        placeBoth();
        failingSources.addAll(Set.of("eu-1", "eu-2"));

        failHeals(FROM_FIRST, 2);
        failHeals(FROM_SECOND, 2);

        assertThat(manager.circuitBreakerStates().get("eu-1").tripped()).as("two failures of one source").isFalse();
        assertThat(manager.circuitBreakerStates().get("eu-2").tripped()).as("two failures of the other source").isFalse();
        assertThat(manager.circuitBreakerState().consecutiveFailures()).as("the aggregate is the worst source, not the sum").isEqualTo(2);
    }

    @Test
    void operatorResetWithSource_closesOnlyThatSource() {
        placeBoth();
        failingSources.addAll(Set.of("eu-1", "eu-2"));
        failHeals(FROM_FIRST, 3);
        failHeals(FROM_SECOND, 3);

        var prior = manager.resetCircuitBreaker("test", Option.some(FIRST));

        assertThat(prior).isEqualTo(3);
        assertThat(manager.circuitBreakerStates().keySet()).containsExactly("eu-2");
    }

    @Test
    void operatorResetWithoutSource_closesEverySource() {
        placeBoth();
        failingSources.addAll(Set.of("eu-1", "eu-2"));
        failHeals(FROM_FIRST, 3);
        failHeals(FROM_SECOND, 3);

        var prior = manager.resetCircuitBreaker("test", Option.none());

        assertThat(prior).as("the counts that were cleared").isEqualTo(6);
        assertThat(manager.circuitBreakerStates()).isEmpty();
    }

    @Test
    void setDesiredCount_closesOnlyThatSourcesBreaker() {
        placeBoth();
        failingSources.addAll(Set.of("eu-1", "eu-2"));
        failHeals(FROM_FIRST, 3);
        failHeals(FROM_SECOND, 3);

        manager.setDesiredCount(SECOND, NodeRole.CORE, 2).await();

        assertThat(manager.circuitBreakerStates().keySet()).containsExactly("eu-1");
    }

    @Test
    void nodeJoins_closesOnlyItsSourcesBreaker() {
        placeBoth();
        failingSources.addAll(Set.of("eu-1", "eu-2"));
        failHeals(FROM_FIRST, 3);
        failHeals(FROM_SECOND, 3);

        manager.onNodeReady(FROM_SECOND);

        assertThat(manager.circuitBreakerStates().keySet()).as("the joined node's source is closed, the other is not").containsExactly("eu-1");
    }

    @Test
    void phaseNormalAndActivation_closeEverySource() {
        placeBoth();
        failingSources.addAll(Set.of("eu-1", "eu-2"));
        failHeals(FROM_FIRST, 3);
        failHeals(FROM_SECOND, 3);

        manager.onClusterPhaseChanged(ClusterPhase.NORMAL);

        assertThat(manager.circuitBreakerStates()).isEmpty();
    }

    /// A refusal before a source is resolved is reported under its own bucket and gates no source.
    @Test
    void unresolvedRefusal_isCountedApart_andGatesNoSource() {
        heal(Option.some(UNPLACED));
        heal(Option.some(UNPLACED));
        heal(Option.some(UNPLACED));

        assertThat(manager.circuitBreakerStates().keySet()).containsExactly("(unresolved)");
        placeBoth();
        assertThat(heal(Option.some(FROM_FIRST)).unwrap()).isInstanceOf(ProvisionDisposition.Dispatched.class);
    }

    private void failHeals(NodeId departed, int times) {
        for (var i = 0; i < times; i++) {
            heal(Option.some(departed));
        }
    }

    private void placeBoth() {
        committedSource.set(asked -> asked.equals(FROM_FIRST)
                                     ? Result.success(FIRST)
                                     : asked.equals(FROM_SECOND)
                                       ? Result.success(SECOND)
                                       : Causes.cause("no placement for " + asked.id()).<SourceName> result());
    }

    private Result<ProvisionDisposition> heal(Option<NodeId> failedPeer) {
        return manager.provisionReplacement(NodeId.randomNodeId(), failedPeer, MEMBERS, NodeRole.CORE).await();
    }

    private void placeNode(NodeId node, SourceName source) {
        committedSource.set(asked -> asked.equals(node)
                                     ? Result.success(source)
                                     : Causes.cause("no placement for " + asked.id()).<SourceName> result());
    }

    private List<String> requestedSources() {
        return requests.stream().map(request -> request.context().sourceName().value()).toList();
    }

    private ComputeProvider recordingProvider() {
        return new ComputeProvider() {
            @Override
            public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
                if (failingSources.contains(request.context().sourceName().value())) {
                    return Causes.cause("source down").promise();
                }

                requests.add(request);

                return Promise.success(new InstanceInfo(InstanceId.instanceId("native-" + requests.size()).unwrap(),
                                                        InstanceStatus.RUNNING,
                                                        List.of("127.0.0.1"),
                                                        InstanceType.ON_DEMAND,
                                                        Map.of(),
                                                        request.context().nodeId(),
                                                        Option.some(request.zone())));
            }

            @Override
            public Promise<Unit> terminate(InstanceId id) {
                return Promise.unitPromise();
            }

            @Override
            public Promise<List<InstanceInfo>> listInstances() {
                return Promise.success(List.of());
            }

            @Override
            public Promise<InstanceInfo> instanceStatus(InstanceId id) {
                return EnvironmentError.operationNotSupported("unused").promise();
            }
        };
    }

    private static Serializer noSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf buffer, T value) {}
        };
    }

    private static Deserializer noDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf buffer) {
                return null;
            }
        };
    }

    private static MessageRouter.MutableRouter quietRouter() {
        var router = MessageRouter.mutable();

        router.addRoute(NetworkServiceMessage.ListConnectedNodes.class, _ -> {});

        return router;
    }

    private static final class StubSnapshotSource implements GenerationSnapshotSource {
        @Override
        public Option<MembershipView> currentMembershipView() {
            return Option.none();
        }

        @Override
        public long observedRabiaTerm() {
            return 0L;
        }
    }
}
