// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.pragmatica.aether.deployment.DeploymentMap;
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
import org.pragmatica.aether.slice.kvstore.AetherValue.AutoHealStateValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.testlogging.LogCapture;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1038: disabling auto-heal suppresses the DELETION of a lost node's VM as well as its replacement. Driven through the REAL
/// `NodeLifecycleManager` down to a `ComputeProvider` fake, so the assertion is on what reaches the provider: a `terminate` call. Every
/// automatic deletion path is pinned: the first reap of a departed node, the retry of a failed reap, and the re-check of an unconfirmed
/// termination; a held reap raises `instance-reap-held` once per node and a release raises the recovery; a reap an operator's drain asked
/// for is not held; with auto-heal enabled the deletion happens as before.
class ClusterTopologyManagerAutoHealDisabledReapTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId LOST = nodeId("aether-prod-node-01J00000000000000000000001").unwrap();
    private static final String NODE_ID_TAG = "aether.node-id";
    private static final String LOGGER_NAME = "org.pragmatica.aether.deployment.cluster.ClusterTopologyManager";

    private final AtomicReference<List<InstanceInfo>> listing = new AtomicReference<>(List.of());
    private final AtomicInteger terminates = new AtomicInteger();
    private final AtomicInteger listings = new AtomicInteger();
    private final AtomicBoolean autoHealEnabled = new AtomicBoolean(true);
    /// Run on the first listing, which then fails: the operator's `disable` lands between the first attempt and its retry.
    private final AtomicReference<Runnable> duringFirstListing = new AtomicReference<>(() -> {});
    private final AtomicBoolean firstListingFails = new AtomicBoolean(false);
    private final List<OperatorWarning> events = new CopyOnWriteArrayList<>();
    private LogCapture appender;
    private ClusterTopologyManager ctm;

    @BeforeEach
    void setUp() {
        appender = LogCapture.capture(LOGGER_NAME, Level.WARN, false);
        ctm = ctmOver(new SourcedLifecycle(NodeLifecycleManager.nodeLifecycleManager(Option.some(new FakeProvider()))));
        ctm.setRetirementRefusal(_ -> Option.none());
        ctm.setOperatorWarningSink(OperatorWarningSink.handingOffTo(events::add));
        ctm.activate();
        listing.set(List.of(instance("i-lost-1")));
    }

    @AfterEach
    void tearDown() {
        ctm.deactivate();
        appender.close();
    }

    /// A small provisioning window makes every retry interval of the confirmed reap a few milliseconds.
    private ClusterTopologyManager ctmOver(NodeLifecycleManager lifecycleManager) {
        var snapshotSource = new StubSnapshotSource();
        var self = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("localhost", 5000).unwrap());
        var config = new TopologyConfig(SELF, 3, timeSpan(60).seconds(), timeSpan(1).seconds(), List.of(self));
        var observer = TopologyObserver.topologyObserver(config, MessageRouter.mutable(), snapshotSource).unwrap();
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(1).millis(), timeSpan(60).millis()).unwrap();
        var liveness = MembershipLiveness.membershipLiveness(Set::of,
                                                             Set::of,
                                                             _ -> false,
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
                                                            this::autoHealState,
                                                            liveness);
    }

    private Option<AutoHealStateValue> autoHealState() {
        return Option.some(new AutoHealStateValue(autoHealEnabled.get(), "test", 0L));
    }

    private static InstanceInfo instance(String instanceId) {
        return new InstanceInfo(InstanceId.instanceId(instanceId).unwrap(),
                                InstanceStatus.RUNNING,
                                List.of(),
                                InstanceType.ON_DEMAND,
                                Map.of(NODE_ID_TAG, LOST.id()),
                                Option.some(LOST.id()),
                                Option.none());
    }

    private void lost() {
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(LOST, List.of(SELF)));
    }

    private void disableAutoHeal() {
        autoHealEnabled.set(false);
    }

    private long eventsOf(OperatorWarningCode code) {
        return events.stream().filter(event -> event.code() == code && event.subject().equals(LOST.id())).count();
    }

    private List<String> eventMessages(OperatorWarningCode code) {
        return events.stream().filter(event -> event.code() == code).map(OperatorWarning::message).toList();
    }

    private boolean eventSeen(OperatorWarningCode code) {
        return eventsOf(code) > 0;
    }

    private boolean warned(String... fragments) {
        return appender.messages()
                       .stream()
                       .anyMatch(message -> List.of(fragments).stream().allMatch(message::contains));
    }

    private static void settle() {
        await().pollDelay(Duration.ofMillis(700)).atMost(Duration.ofSeconds(5)).until(() -> true);
    }

    @Test
    void nodeRemoved_autoHealDisabled_noTerminateReachesTheProvider_andTheHeldEventNamesTheNode() {
        disableAutoHeal();

        lost();

        await().atMost(Duration.ofSeconds(5)).until(() -> eventSeen(OperatorWarningCode.INSTANCE_REAP_HELD));
        settle();
        assertThat(terminates.get()).as("the provider fake was never asked to terminate").isZero();
        assertThat(warned("instance-reap-held", LOST.id(), "NOT deleted", "reap of the departed node")).as("WARN: " + appender.messages()).isTrue();
    }

    @Test
    void nodeRemoved_autoHealEnabled_terminatesTheInstance_andRaisesNoHeldEvent() {
        lost();

        await().atMost(Duration.ofSeconds(5)).until(() -> terminates.get() == 1);
        settle();
        assertThat(terminates.get()).isEqualTo(1);
        assertThat(eventSeen(OperatorWarningCode.INSTANCE_REAP_HELD)).isFalse();
        assertThat(eventSeen(OperatorWarningCode.INSTANCE_REAP_RELEASED)).as("a recovery needs a held reap").isFalse();
    }

    @Test
    void heldReap_isRaisedOncePerNode_howeverOftenItIsHeldAgain() {
        disableAutoHeal();

        lost();
        await().atMost(Duration.ofSeconds(5)).until(() -> eventSeen(OperatorWarningCode.INSTANCE_REAP_HELD));
        ctm.onSwimFaulty(LOST);
        ctm.onSwimFaulty(LOST);
        settle();

        assertThat(eventsOf(OperatorWarningCode.INSTANCE_REAP_HELD)).as("the held mark is the flood guard").isEqualTo(1);
        assertThat(terminates.get()).isZero();
    }

    @Test
    void heldReap_whenAutoHealIsEnabledAgain_goesAheadOnTheNextSwimFaulty_andRaisesTheRecovery() {
        disableAutoHeal();
        lost();
        await().atMost(Duration.ofSeconds(5)).until(() -> eventSeen(OperatorWarningCode.INSTANCE_REAP_HELD));

        autoHealEnabled.set(true);
        ctm.onSwimFaulty(LOST);

        await().atMost(Duration.ofSeconds(5)).until(() -> terminates.get() == 1);
        await().atMost(Duration.ofSeconds(5)).until(() -> eventSeen(OperatorWarningCode.INSTANCE_REAP_RELEASED));
        assertThat(eventMessages(OperatorWarningCode.INSTANCE_REAP_RELEASED)).anyMatch(message -> message.contains("enabled again") && message.contains(LOST.id()));
    }

    @Test
    void heldReap_whenTheNodeJoinsAgain_isClosedAsRejoined_andNothingIsTerminated() {
        disableAutoHeal();
        lost();
        await().atMost(Duration.ofSeconds(5)).until(() -> eventSeen(OperatorWarningCode.INSTANCE_REAP_HELD));

        ctm.onMembershipDecision(MembershipDecision.nodeJoined(LOST, List.of(SELF, LOST)));

        await().atMost(Duration.ofSeconds(5)).until(() -> eventSeen(OperatorWarningCode.INSTANCE_REAP_RELEASED));
        assertThat(terminates.get()).isZero();
        assertThat(eventMessages(OperatorWarningCode.INSTANCE_REAP_RELEASED)).anyMatch(message -> message.contains("joined the cluster again") && message.contains("nothing was deleted"));
    }

    /// The retry path: the first attempt fails while auto-heal is enabled, the operator disables it before the retry, and the retry is held.
    @Test
    void retryOfAFailedReap_autoHealDisabledAfterTheFirstAttempt_terminatesNothing() {
        firstListingFails.set(true);
        duringFirstListing.set(this::disableAutoHeal);

        lost();

        await().atMost(Duration.ofSeconds(5)).until(() -> eventSeen(OperatorWarningCode.INSTANCE_REAP_HELD));
        settle();
        assertThat(listings.get()).as("the first attempt ran, so the held event is the retry's").isEqualTo(1);
        assertThat(terminates.get()).as("the retry did not reach the provider").isZero();
        assertThat(warned("instance-reap-held", LOST.id(), "retry of a failed reap")).as("WARN: " + appender.messages()).isTrue();
    }

    @Test
    void retryOfAFailedReap_autoHealStillEnabled_terminates() {
        firstListingFails.set(true);

        lost();

        await().atMost(Duration.ofSeconds(5)).until(() -> terminates.get() == 1);
        assertThat(eventSeen(OperatorWarningCode.INSTANCE_REAP_HELD)).isFalse();
    }

    /// A drain an operator asked for is not the heal acting: its grace reap and the retries of it go ahead with auto-heal disabled.
    @Test
    void retryOfAnOperatorDrainsReap_autoHealDisabled_isNotHeld() {
        firstListingFails.set(true);
        duringFirstListing.set(this::disableAutoHeal);

        ctm.drainNode(LOST, DrainReason.OPERATOR_COMMAND);

        await().atMost(Duration.ofSeconds(5)).until(() -> terminates.get() == 1);
        assertThat(eventSeen(OperatorWarningCode.INSTANCE_REAP_HELD)).as("an operator-requested retirement is not an automatic deletion").isFalse();
    }

    /// The re-check of an unconfirmed termination terminates again, so it is an automatic deletion too.
    @Test
    void recheckOfAnUnconfirmedTermination_autoHealDisabled_terminatesNothing() {
        var record = (ClusterTopologyManagerRecord) ctm;

        disableAutoHeal();
        record.seenInstances().add(LOST);
        record.unconfirmedReaps().add(LOST);

        await().atMost(Duration.ofSeconds(5)).until(() -> eventSeen(OperatorWarningCode.INSTANCE_REAP_HELD));
        settle();
        assertThat(terminates.get()).isZero();
        assertThat(warned("instance-reap-held", LOST.id(), "re-check of an unconfirmed termination")).as("WARN: " + appender.messages()).isTrue();
    }

    @Test
    void recheckOfAnUnconfirmedTermination_autoHealEnabled_terminates() {
        var record = (ClusterTopologyManagerRecord) ctm;

        record.seenInstances().add(LOST);
        record.unconfirmedReaps().add(LOST);

        await().atMost(Duration.ofSeconds(5)).until(() -> terminates.get() == 1);
        assertThat(eventSeen(OperatorWarningCode.INSTANCE_REAP_HELD)).isFalse();
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
            listing.set(List.of());

            return Promise.unitPromise();
        }

        @Override
        public Promise<List<InstanceInfo>> listInstances() {
            var call = listings.incrementAndGet();

            if (call == 1 && firstListingFails.get()) {
                duringFirstListing.get().run();

                return EnvironmentError.operationNotSupported("transient listing failure").promise();
            }

            return Promise.success(listing.get());
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
