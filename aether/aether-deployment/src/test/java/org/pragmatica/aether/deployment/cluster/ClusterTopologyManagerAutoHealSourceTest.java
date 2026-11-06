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


/// #2115 — a core auto-heal replacement is made in the source of the node it replaces. Two cloud sources declare the core role; the
/// manager used to stamp every heal with the FIRST of them, whichever source the departed node came from. The chain under test is the
/// production one: committed configuration, source registry, capacity-controlled lifecycle over the source lifecycle, so the source
/// the provider receives is read where the cloud bill is decided.
@Timeout(120)
class ClusterTopologyManagerAutoHealSourceTest {
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

    /// Three sources declare the role and `eu-0` is declared first: the removed first-cloud preference would pick `eu-0` for every heal, so
    /// each of the two tests below names a source that pick would get wrong.
    @Test
    void nodeOfTheSecondSourceDeparts_theHealGoesToTheSecondSource() {
        placeNode(FROM_SECOND, SECOND);

        var outcome = heal(Option.some(FROM_SECOND));

        assertThat(outcome.isSuccess()).as("actual heal outcome: %s", outcome).isTrue();
        assertThat(requestedSources()).as("the replacement is made where the departed node was").containsExactly("eu-2");
    }

    @Test
    void nodeOfTheFirstSourceDeparts_theHealGoesToTheFirstSource() {
        placeNode(FROM_FIRST, FIRST);

        heal(Option.some(FROM_FIRST));

        assertThat(requestedSources()).containsExactly("eu-1");
    }

    /// Several sources declare the role and the departed node has no committed source: any pick would be a guess at which provider to bill, so
    /// nothing is provisioned and the refusal is a named deferral, counted, and told to the operator once.
    @Test
    void sourceOfTheDepartedNodeUnresolvable_nothingIsProvisioned_andTheOperatorIsTold() {
        var first = heal(Option.some(UNPLACED));
        var second = heal(Option.some(UNPLACED));

        assertThat(first.unwrap()).isEqualTo(ProvisionDisposition.deferred(ProvisionDisposition.DeferralReason.SOURCE_UNRESOLVED));
        assertThat(second.unwrap()).isEqualTo(ProvisionDisposition.deferred(ProvisionDisposition.DeferralReason.SOURCE_UNRESOLVED));
        assertThat(requests).as("no provider effect, so no guessed source is billed").isEmpty();
        assertThat(manager.circuitBreakerState().consecutiveFailures()).as("a refusal is not a provider failure: never counted").isZero();
        assertThat(manager.lastProvisionFailure().map(ClusterTopologyManager.LastProvisionFailure::cause).or(""))
            .as("the cause names the role and the candidate sources")
            .contains("core", "eu-0", "eu-1", "eu-2");
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> !events.isEmpty());
        assertThat(events).as("one event for the run, not one per refusal").containsExactly("core-replacement-source-unresolved");
    }

    /// The same heal with no failed peer named is the same refusal: the removed first-cloud preference must not come back through the
    /// unnamed path.
    @Test
    void noDepartedNodeNamed_severalSourcesDeclareTheRole_isRefusedNotPickedByOrder() {
        var outcome = heal(Option.none());

        assertThat(outcome.unwrap()).isEqualTo(ProvisionDisposition.deferred(ProvisionDisposition.DeferralReason.SOURCE_UNRESOLVED));
        assertThat(requests).isEmpty();
    }

    @Test
    void sourceBecomesResolvable_theHealIsDispatched_andTheRefusalIsClosed() {
        heal(Option.some(FROM_SECOND));
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> events.size() == 1);

        placeNode(FROM_SECOND, SECOND);
        manager.resetCircuitBreaker("test: end the refusal backoff");
        var outcome = heal(Option.some(FROM_SECOND));

        assertThat(outcome.unwrap()).isInstanceOf(ProvisionDisposition.Dispatched.class);
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> events.size() == 2);
        assertThat(events).containsExactly("core-replacement-source-unresolved", "core-replacement-source-resolved");
        assertThat(requestedSources()).containsExactly("eu-2");
    }

    /// The event pair is one open/close per run: a refusal run closed by two successful heals raises exactly one RESOLVED, and a heal that
    /// never followed a refusal raises none.
    @Test
    void healOkTwiceAfterOneRefusal_raisesExactlyOneResolved() {
        heal(Option.some(FROM_SECOND));
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> events.size() == 1);

        placeNode(FROM_SECOND, SECOND);
        manager.resetCircuitBreaker("test: end the refusal backoff");
        heal(Option.some(FROM_SECOND));
        heal(Option.some(FROM_SECOND));

        Awaitility.await().atMost(Duration.ofSeconds(5)).during(Duration.ofMillis(500)).until(() -> events.size() == 2);
        assertThat(events).containsExactly("core-replacement-source-unresolved", "core-replacement-source-resolved");
    }

    @Test
    void healOkWithNoPriorRefusal_raisesNoEvent() {
        placeNode(FROM_SECOND, SECOND);

        heal(Option.some(FROM_SECOND));

        Awaitility.await().atMost(Duration.ofSeconds(5)).during(Duration.ofMillis(500)).until(events::isEmpty);
    }

    /// A node a heal made earlier is committed to the source it was made in (its capacity reservation), so when it departs in turn its own
    /// replacement goes there, though nothing else names its source.
    @Test
    void aHealedNodeDeparts_itsReplacementGoesToTheSourceItsReservationNames() {
        var healed = NodeId.randomNodeId();

        placeNode(FROM_SECOND, SECOND);
        manager.provisionReplacement(healed, Option.some(FROM_SECOND), MEMBERS, NodeRole.CORE).await();
        committedSource.set(node -> Causes.cause("no placement for " + node.id()).result());
        requests.clear();

        var outcome = heal(Option.some(healed));

        assertThat(outcome.unwrap()).isInstanceOf(ProvisionDisposition.Dispatched.class);
        assertThat(requestedSources()).containsExactly("eu-2");
    }

    /// A lifecycle manager that knows no placements commits no source: the default answers empty, never a default source name.
    @Test
    void committedSourceOf_interfaceDefault_isEmpty() {
        var defaults = (NodeLifecycleManager) java.lang.reflect.Proxy.newProxyInstance(NodeLifecycleManager.class.getClassLoader(),
                                                                                       new Class<?>[]{NodeLifecycleManager.class},
                                                                                       (proxy, method, args) -> java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args));

        assertThat(defaults.committedSourceOf(FROM_SECOND).isEmpty()).isTrue();
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
