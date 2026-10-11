// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManagerActuatorTest.CapturingAppender;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1111: an in-flight replacement the leader drops (ceiling, FAILED, twelve absent listings, failed create) is reaped through the same confirmed
/// reap as a retirement, driven through the REAL `NodeLifecycleManager` down to a `ComputeProvider` fake. The properties pinned: the instance is
/// terminated with a WARN naming the source, the node and the instance id; a termination that never confirms ends in the unconfirmed-termination
/// event; a node that shows life is never terminated.
class ClusterTopologyManagerDroppedReplacementTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId DROPPED = nodeId("aether-prod-node-01J00000000000000000000001").unwrap();
    private static final String NODE_ID_TAG = "aether.node-id";
    private static final String LOGGER_NAME = "org.pragmatica.aether.deployment.cluster.ClusterTopologyManager";

    private final AtomicReference<Promise<List<InstanceInfo>>> listing = new AtomicReference<>(Promise.success(List.of()));
    private final AtomicInteger terminates = new AtomicInteger();
    private final AtomicBoolean terminateRemoves = new AtomicBoolean(true);
    private final AtomicReference<Set<NodeId>> swimAlive = new AtomicReference<>(Set.of());
    private CapturingAppender appender;
    private LoggerConfig loggerConfig;
    private Level originalLevel;
    private ClusterTopologyManager ctm;

    @BeforeEach
    void setUp() {
        attachAppender();
        ctm = ctmOver(new SourcedLifecycle(NodeLifecycleManager.nodeLifecycleManager(Option.some(new FakeProvider()))));
        ctm.setRetirementRefusal(_ -> Option.none());
        ctm.activate();
    }

    @AfterEach
    void tearDown() {
        ctm.deactivate();
        var ctx = (LoggerContext) LogManager.getContext(false);

        loggerConfig.removeAppender(appender.getName());
        loggerConfig.setLevel(originalLevel);
        ctx.updateLoggers();
        appender.stop();
    }

    private void attachAppender() {
        appender = CapturingAppender.create("CtmDroppedReplacementCapture");
        appender.start();
        var ctx = (LoggerContext) LogManager.getContext(false);
        var configuration = ctx.getConfiguration();
        var existing = configuration.getLoggerConfig(LOGGER_NAME);

        loggerConfig = LOGGER_NAME.equals(existing.getName())
                       ? existing
                       : new LoggerConfig(LOGGER_NAME, Level.WARN, false);
        if (!LOGGER_NAME.equals(existing.getName())) {
            configuration.addLogger(LOGGER_NAME, loggerConfig);
        }
        originalLevel = loggerConfig.getLevel();
        loggerConfig.addAppender(appender, Level.WARN, null);
        loggerConfig.setLevel(Level.WARN);
        ctx.updateLoggers();
    }

    /// A small provisioning timeout makes every retry interval of the confirmed reap a few milliseconds.
    private ClusterTopologyManager ctmOver(NodeLifecycleManager lifecycleManager) {
        var snapshotSource = new StubSnapshotSource();
        var self = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("localhost", 5000).unwrap());
        var config = new TopologyConfig(SELF, 3, timeSpan(60).seconds(), timeSpan(1).seconds(), List.of(self));
        var observer = TopologyObserver.topologyObserver(config, MessageRouter.mutable(), snapshotSource).unwrap();
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(1).millis(), timeSpan(60).millis()).unwrap();
        var liveness = MembershipLiveness.membershipLiveness(Set::of,
                                                             Set::of,
                                                             node -> swimAlive.get().contains(node),
                                                             _ -> false,
                                                             Set::of,
                                                             () -> 3,
                                                             _ -> Option.none());

        return ClusterTopologyManager.clusterTopologyManager(observer,
                                                            lifecycleManager,
                                                            autoHeal,
                                                            DeploymentMap.deploymentMap(),
                                                            snapshotSource,
                                                            () -> Option.<ClusterConfigValue> none(),
                                                            commands -> Promise.success(List.<Object> of()),
                                                            () -> AetherValue.ClusterPhase.NORMAL,
                                                            _ -> {},
                                                            _ -> {},
                                                            Option::none,
                                                            liveness);
    }

    private void providerLists(InstanceInfo... instances) {
        listing.set(Promise.success(List.of(instances)));
    }

    private static InstanceInfo instance(String instanceId, InstanceStatus status) {
        return new InstanceInfo(InstanceId.instanceId(instanceId).unwrap(),
                                status,
                                List.of(),
                                InstanceType.ON_DEMAND,
                                Map.of(NODE_ID_TAG, DROPPED.id()),
                                Option.some(DROPPED.id()),
                                Option.none());
    }

    private boolean warned(String... fragments) {
        return appender.capturedWarns()
                       .stream()
                       .anyMatch(message -> List.of(fragments).stream().allMatch(message::contains));
    }

    private static void settle() {
        await().pollDelay(Duration.ofMillis(400)).atMost(Duration.ofSeconds(5)).until(() -> true);
    }

    @Test
    void droppedReplacement_isTerminated_andTheWarnNamesSourceNodeAndInstanceId() {
        providerLists(instance("i-orphan-7", InstanceStatus.RUNNING));

        ctm.reapDroppedReplacement(DROPPED, "still unjoined after its ceiling", true);

        await().atMost(Duration.ofSeconds(5)).until(() -> terminates.get() == 1);
        assertThat(warned("in-flight replacement", DROPPED.id(), "i-orphan-7", "source=", "still unjoined after its ceiling")).as("warns: " + appender.capturedWarns()).isTrue();
    }

    @Test
    void droppedReplacement_whoseTerminationNeverConfirms_endsInTheUnconfirmedEvent() {
        providerLists(instance("i-stuck-1", InstanceStatus.RUNNING));
        terminateRemoves.set(false);

        ctm.reapDroppedReplacement(DROPPED, "the provider reports FAILED", true);

        await().atMost(Duration.ofSeconds(10))
               .until(() -> warned("instance-termination-unconfirmed", DROPPED.id()));
        assertThat(terminates.get()).as("the bounded retries each terminated").isGreaterThan(1);
    }

    @Test
    void droppedReplacement_theProviderListedBefore_andNowListsNothing_isConfirmedGone_withoutAnEvent() {
        ctm.reapDroppedReplacement(DROPPED, "the provider reports ABSENT", true);

        settle();
        assertThat(terminates.get()).isZero();
        assertThat(warned("instance-termination-unconfirmed")).isFalse();
    }

    /// The conservative reading #2062 fixed for retirements: an empty listing proves nothing for an instance the provider never listed.
    @Test
    void droppedReplacement_neverListed_andListingNothing_isNotConfirmed_andRaisesTheEvent() {
        ctm.reapDroppedReplacement(DROPPED, "twelve absent listings", false);

        await().atMost(Duration.ofSeconds(10))
               .until(() -> warned("instance-termination-unconfirmed", DROPPED.id()));
        assertThat(terminates.get()).isZero();
    }

    /// The production lifecycle always names a source; a lifecycle that cannot (a test double) still gets the instance terminated, by node id.
    @Test
    void droppedReplacement_withNoKnownSource_isStillTerminatedByNodeId() {
        ctm.deactivate();
        ctm = ctmOver(NodeLifecycleManager.nodeLifecycleManager(Option.some(new FakeProvider())));
        ctm.setRetirementRefusal(_ -> Option.none());
        ctm.activate();
        providerLists(instance("i-nosource-2", InstanceStatus.RUNNING));

        ctm.reapDroppedReplacement(DROPPED, "the provider reports FAILED", true);

        await().atMost(Duration.ofSeconds(5)).until(() -> terminates.get() == 1);
        assertThat(warned("in-flight replacement", DROPPED.id(), "no compute source is known")).isTrue();
    }

    /// The listing that names the instance id fails: the WARN says so, with the source, the node and the listing's cause.
    @Test
    void droppedReplacement_whenTheAnnouncingListingFails_warnsWithSourceNodeAndCause() {
        listing.set(EnvironmentError.operationNotSupported("provider API down").promise());

        ctm.reapDroppedReplacement(DROPPED, "still unjoined after its ceiling", false);

        await().atMost(Duration.ofSeconds(5))
               .until(() -> warned("in-flight replacement", DROPPED.id(), "source=", "instance id unknown", "provider API down"));
    }

    /// Control: a replacement that joined (SWIM shows it alive) is never terminated, however many retries pass.
    @Test
    void droppedReplacement_thatShowsLife_isNeverTerminated() {
        providerLists(instance("i-joined-1", InstanceStatus.RUNNING));
        swimAlive.set(Set.of(DROPPED));

        ctm.reapDroppedReplacement(DROPPED, "still unjoined after its ceiling", true);

        await().atMost(Duration.ofSeconds(5)).until(() -> warned("DEFERRED", DROPPED.id()));
        settle();
        assertThat(terminates.get()).isZero();
    }

    /// The production lifecycle's `sourceOf` is total (a source is always named); the single-provider record names none, so this names the default.
    private record SourcedLifecycle(NodeLifecycleManager delegate) implements NodeLifecycleManager {
        @Override
        public Promise<ActionResult> executeAction(NodeAction action) {
            return delegate.executeAction(action);
        }

        @Override
        public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
            return delegate.provisionNode(spec);
        }

        @Override
        public Promise<Unit> terminateNode(NodeId nodeId) {
            return delegate.terminateNode(nodeId);
        }

        @Override
        public Promise<List<InstanceInfo>> instancesForNode(NodeId nodeId) {
            return delegate.instancesForNode(nodeId);
        }

        @Override
        public boolean isCloudManaged() {
            return delegate.isCloudManaged();
        }

        @Override
        public Option<SourceName> sourceOf(NodeId nodeId) {
            return Option.some(SourceName.DEFAULT);
        }
    }

    private final class FakeProvider implements ComputeProvider {
        @Override
        public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
            return EnvironmentError.operationNotSupported("createFrom").promise();
        }

        @Override
        public Promise<Unit> terminate(InstanceId instanceId) {
            terminates.incrementAndGet();
            if (terminateRemoves.get()) {
                listing.set(Promise.success(List.of()));
            }

            return Promise.unitPromise();
        }

        @Override
        public Promise<List<InstanceInfo>> listInstances() {
            return listing.get();
        }

        @Override
        public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
            return EnvironmentError.operationNotSupported("instanceStatus").promise();
        }
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
