// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

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
    /// The counted core members the surplus-trim verdict reads: empty until a test gives the cluster a node too many.
    private final AtomicReference<Set<NodeId>> counted = new AtomicReference<>(Set.of());
    private final AtomicBoolean autoHealEnabled = new AtomicBoolean(true);
    /// Run on the first listing, which then fails: the operator's `disable` lands between the first attempt and its retry.
    private final AtomicReference<Runnable> duringFirstListing = new AtomicReference<>(() -> {});
    private final AtomicBoolean firstListingFails = new AtomicBoolean(false);
    /// One-shot: the next listing fails, and the next listing runs the hook first (a join or a disable landing while the listing is in flight).
    private final AtomicInteger effectfulListings = new AtomicInteger();
    private final Set<NodeId> externalNodes = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean failNextListing = new AtomicBoolean(false);
    private final AtomicReference<Runnable> onNextListing = new AtomicReference<>(() -> {});
    private final List<OperatorWarning> events = new CopyOnWriteArrayList<>();
    private LogCapture appender;
    private ClusterTopologyManager ctm;

    @BeforeEach
    void setUp() {
        appender = LogCapture.capture(LOGGER_NAME, Level.WARN, false);
        ctm = ctmOver(new SourcedLifecycle(NodeLifecycleManager.nodeLifecycleManager(Option.some(new FakeProvider())), externalNodes::contains, effectfulListings));
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
        var liveness = MembershipLiveness.membershipLiveness(counted::get,
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

    private static InstanceInfo terminatedInstance(String instanceId) {
        return new InstanceInfo(InstanceId.instanceId(instanceId).unwrap(),
                                new InstanceStatus.Terminated(),
                                List.of(),
                                InstanceType.ON_DEMAND,
                                Map.of(NODE_ID_TAG, LOST.id()),
                                Option.some(LOST.id()),
                                Option.none());
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
        assertThat(warned("instance-reap-held", LOST.id(), "NOT deleted", "reap of a departed or surplus node")).as("WARN: " + appender.messages()).isTrue();
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

    /// The held mark is the leader's own: a node held under one activation is held and announced again under the next, so the operator is not
    /// left without an event when the leader changes.
    @Test
    void heldReap_afterTheManagerIsDeactivatedAndActivatedAgain_isHeldAndAnnouncedAgain() {
        disableAutoHeal();
        lost();
        await().atMost(Duration.ofSeconds(5)).until(() -> eventsOf(OperatorWarningCode.INSTANCE_REAP_HELD) == 1);

        ctm.deactivate();
        ctm.activate();
        lost();

        await().atMost(Duration.ofSeconds(5)).until(() -> eventsOf(OperatorWarningCode.INSTANCE_REAP_HELD) == 2);
        assertThat(terminates.get()).isZero();
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
        assertThat(warned("instance-reap-held", LOST.id(), "reap of a departed or surplus node")).as("WARN: " + appender.messages()).isTrue();
    }

    @Test
    void retryOfAFailedReap_autoHealStillEnabled_terminates() {
        firstListingFails.set(true);

        lost();

        await().atMost(Duration.ofSeconds(5)).until(() -> terminates.get() == 1);
        assertThat(eventSeen(OperatorWarningCode.INSTANCE_REAP_HELD)).isFalse();
    }

    /// A drain an operator asked for is not the heal acting: its grace reap and the retries of it go ahead with auto-heal disabled. (OPERATOR_COMMAND has
    /// no production producer yet: this row covers the reason, the production directed paths are REPLACED and community-placement retirement.)
    @Test
    void retryOfAnOperatorDrainsReap_autoHealDisabled_isNotHeld() {
        firstListingFails.set(true);
        duringFirstListing.set(this::disableAutoHeal);

        ctm.drainNode(LOST, DrainReason.OPERATOR_COMMAND);

        await().atMost(Duration.ofSeconds(5)).until(() -> terminates.get() == 1);
        assertThat(eventSeen(OperatorWarningCode.INSTANCE_REAP_HELD)).as("an operator-requested retirement is not an automatic deletion").isFalse();
    }

    private final AtomicReference<Option<String>> refusal = new AtomicReference<>(Option.none());

    /// A node lost later is a new incarnation: it joined first, which drops what was remembered of the confirmed reap of the previous one.
    private void joinsAgain() {
        ctm.onMembershipDecision(MembershipDecision.nodeJoined(LOST, List.of(SELF, LOST)));
    }

    private void relist() {
        listing.set(List.of(instance("i-lost-again")));
    }

    /// Entry: the retry of a refusal. A voter is refused while it votes; auto-heal is disabled before it is voted out; the retry is held.
    @Test
    void refusedReapRetry_autoHealDisabledBeforeTheVoterIsVotedOut_terminatesNothing() {
        ctm.setRetirementRefusal(_ -> refusal.get());
        refusal.set(Option.some("still an installed voter"));
        lost();
        await().atMost(Duration.ofSeconds(5)).until(() -> warned("REFUSED"));

        disableAutoHeal();
        refusal.set(Option.none());

        await().atMost(Duration.ofSeconds(5)).until(() -> eventSeen(OperatorWarningCode.INSTANCE_REAP_HELD));
        settle();
        assertThat(terminates.get()).isZero();
    }

    @Test
    void refusedReapRetry_autoHealEnabled_terminatesOnceVotedOut() {
        ctm.setRetirementRefusal(_ -> refusal.get());
        refusal.set(Option.some("still an installed voter"));
        lost();
        await().atMost(Duration.ofSeconds(5)).until(() -> warned("REFUSED"));

        refusal.set(Option.none());

        await().atMost(Duration.ofSeconds(5)).until(() -> terminates.get() == 1);
    }

    /// `drainNode` itself refuses a voter, so a drain reaches its grace reap refused only when the node became a voter after the drain was
    /// accepted: the first answer is "not a voter", every later one "still a voter" until the test releases it.
    private void voterAfterTheDrainWasAccepted() {
        var asked = new AtomicInteger();

        refusal.set(Option.some("still an installed voter"));
        ctm.setRetirementRefusal(_ -> asked.getAndIncrement() == 0 ? Option.none() : refusal.get());
    }

    /// The exemption follows the reap's origin, not the entry it comes in through: an operator's drain is refused at its grace reap,
    /// auto-heal is disabled meanwhile, and the retry reaps it all the same. (OPERATOR_COMMAND has no production producer yet; see the retry row above.)
    @Test
    void operatorDrainedVoter_refusedThenRetried_isReapedWithAutoHealDisabled() {
        voterAfterTheDrainWasAccepted();
        disableAutoHeal();
        ctm.drainNode(LOST, DrainReason.OPERATOR_COMMAND);
        await().atMost(Duration.ofSeconds(5)).until(() -> warned("REFUSED"));
        assertThat(terminates.get()).as("arming: refused while it votes").isZero();

        refusal.set(Option.none());

        await().atMost(Duration.ofSeconds(5)).until(() -> terminates.get() == 1);
        assertThat(eventSeen(OperatorWarningCode.INSTANCE_REAP_HELD)).isFalse();
    }

    @Test
    void replacedVoter_refusedThenRetried_isReapedWithAutoHealDisabled() {
        voterAfterTheDrainWasAccepted();
        disableAutoHeal();
        ctm.drainNode(LOST, DrainReason.REPLACED);
        await().atMost(Duration.ofSeconds(5)).until(() -> warned("REFUSED"));

        refusal.set(Option.none());

        await().atMost(Duration.ofSeconds(5)).until(() -> terminates.get() == 1);
    }

    /// The same refusal path, an automatic origin: held.
    @Test
    void joinGraceVoter_refusedThenRetried_isHeldWithAutoHealDisabled() {
        voterAfterTheDrainWasAccepted();
        disableAutoHeal();
        ctm.drainNode(LOST, DrainReason.JOIN_GRACE_REAP);
        await().atMost(Duration.ofSeconds(5)).until(() -> warned("REFUSED"));

        refusal.set(Option.none());

        await().atMost(Duration.ofSeconds(5)).until(() -> eventSeen(OperatorWarningCode.INSTANCE_REAP_HELD));
        settle();
        assertThat(terminates.get()).isZero();
    }

    /// The recheck carries the origin too: a directed reap whose termination was left unconfirmed is re-checked with auto-heal disabled.
    @Test
    void recheckOfADirectedReap_autoHealDisabled_terminates() {
        var record = (ClusterTopologyManagerRecord) ctm;

        disableAutoHeal();
        record.directedReaps().add(LOST);
        record.seenInstances().add(LOST);
        record.unconfirmedReaps().add(LOST);

        await().atMost(Duration.ofSeconds(5)).until(() -> terminates.get() == 1);
        assertThat(eventSeen(OperatorWarningCode.INSTANCE_REAP_HELD)).isFalse();
    }

    /// Entry: the drain-grace reap of a never-joined zombie is the cluster's own decision, so it is held like a departed node's reap.
    @Test
    void joinGraceReap_autoHealDisabled_terminatesNothing_andIsHeld() {
        disableAutoHeal();

        ctm.drainNode(LOST, DrainReason.JOIN_GRACE_REAP);

        await().atMost(Duration.ofSeconds(5)).until(() -> eventSeen(OperatorWarningCode.INSTANCE_REAP_HELD));
        settle();
        assertThat(terminates.get()).isZero();
        assertThat(warned("instance-reap-held", LOST.id(), "drain-grace reap")).isTrue();
    }

    @Test
    void joinGraceReap_autoHealEnabled_terminates() {
        ctm.drainNode(LOST, DrainReason.JOIN_GRACE_REAP);

        await().atMost(Duration.ofSeconds(5)).until(() -> terminates.get() == 1);
        assertThat(eventSeen(OperatorWarningCode.INSTANCE_REAP_HELD)).isFalse();
    }

    /// Entry: the surplus-trim grace reap. The cluster decided it had a node too many; with auto-heal disabled that is held.
    @Test
    void surplusTrimGraceReap_autoHealDisabled_terminatesNothing() {
        counted.set(Set.of(SELF, nodeId("node-b").unwrap(), nodeId("node-c").unwrap()));
        disableAutoHeal();

        ctm.drainNode(LOST, DrainReason.OVERPROVISION_SCALE_DOWN);

        await().atMost(Duration.ofSeconds(5)).until(() -> eventSeen(OperatorWarningCode.INSTANCE_REAP_HELD));
        settle();
        assertThat(terminates.get()).isZero();
    }

    @Test
    void surplusTrimGraceReap_autoHealEnabled_terminates() {
        counted.set(Set.of(SELF, nodeId("node-b").unwrap(), nodeId("node-c").unwrap()));
        ctm.drainNode(LOST, DrainReason.OVERPROVISION_SCALE_DOWN);

        await().atMost(Duration.ofSeconds(5)).until(() -> terminates.get() == 1);
    }

    /// Entry: the replacement protocol's drain is a directed retirement of a node the cluster replaced, not the heal acting: not held.
    @Test
    void replacedDrainReap_autoHealDisabled_isNotHeld() {
        disableAutoHeal();

        ctm.drainNode(LOST, DrainReason.REPLACED);

        await().atMost(Duration.ofSeconds(5)).until(() -> terminates.get() == 1);
        assertThat(eventSeen(OperatorWarningCode.INSTANCE_REAP_HELD)).isFalse();
    }

    /// A directed reap exempts only itself: the same node lost later, on the cluster's own account, is held.
    @Test
    void directedReap_doesNotExemptTheSameNodeLostLater() {
        disableAutoHeal();
        ctm.drainNode(LOST, DrainReason.OPERATOR_COMMAND);
        await().atMost(Duration.ofSeconds(5)).until(() -> terminates.get() == 1);
        relist();

        lost();

        await().atMost(Duration.ofSeconds(5)).until(() -> eventSeen(OperatorWarningCode.INSTANCE_REAP_HELD));
        settle();
        assertThat(terminates.get()).isEqualTo(1);
    }

    /// Nor does it exempt a later automatic drain of the same node.
    @Test
    void directedReap_doesNotExemptALaterAutomaticDrainOfTheSameNode() {
        disableAutoHeal();
        ctm.drainNode(LOST, DrainReason.OPERATOR_COMMAND);
        await().atMost(Duration.ofSeconds(5)).until(() -> terminates.get() == 1);
        relist();

        ctm.drainNode(LOST, DrainReason.JOIN_GRACE_REAP);

        await().atMost(Duration.ofSeconds(5)).until(() -> eventSeen(OperatorWarningCode.INSTANCE_REAP_HELD));
        settle();
        assertThat(terminates.get()).isEqualTo(1);
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

    /// Directed retirements are the operator's act, not the cluster's: once the directed reap has deleted the instance, the node's own NodeRemoved
    /// has nothing left to hold, so it raises no held event and parks nothing. (OPERATOR_COMMAND has no production producer yet; the row covers
    /// the reason, REPLACED and community-placement retirement are the production directed paths.)
    @Test
    void operatorCommandReapDone_thenNodeRemoved_raisesNoHeldEvent() {
        disableAutoHeal();
        ctm.drainNode(LOST, DrainReason.OPERATOR_COMMAND);
        await().atMost(Duration.ofSeconds(5)).until(() -> terminates.get() == 1);
        settle();

        lost();
        settle();

        assertThat(eventsOf(OperatorWarningCode.INSTANCE_REAP_HELD)).as("held for a deleted instance: " + eventMessages(OperatorWarningCode.INSTANCE_REAP_HELD)).isZero();
        assertThat(((ClusterTopologyManagerRecord) ctm).abandonedReaps()).doesNotContain(LOST);
    }

    @Test
    void replacedReapDone_thenNodeRemoved_raisesNoHeldEvent() {
        disableAutoHeal();
        ctm.drainNode(LOST, DrainReason.REPLACED);
        await().atMost(Duration.ofSeconds(5)).until(() -> terminates.get() == 1);
        settle();

        lost();
        settle();

        assertThat(eventsOf(OperatorWarningCode.INSTANCE_REAP_HELD)).as("held for a replaced, deleted instance: " + eventMessages(OperatorWarningCode.INSTANCE_REAP_HELD)).isZero();
        assertThat(((ClusterTopologyManagerRecord) ctm).abandonedReaps()).doesNotContain(LOST);
    }

    /// The reverse order: the node's removal was held first, then the directed reap deletes the instance, so the held event is closed with the release.
    @Test
    void nodeRemovedHeld_thenOperatorCommandReapDeletes_heldEventIsReleased() {
        disableAutoHeal();
        lost();
        await().atMost(Duration.ofSeconds(5)).until(() -> eventSeen(OperatorWarningCode.INSTANCE_REAP_HELD));
        ctm.drainNode(LOST, DrainReason.OPERATOR_COMMAND);
        await().atMost(Duration.ofSeconds(5)).until(() -> terminates.get() == 1);
        settle();

        assertThat(eventsOf(OperatorWarningCode.INSTANCE_REAP_RELEASED)).as("held event left open after the instance was deleted").isEqualTo(1);
    }

    @Test
    void nodeRemovedHeld_thenReplacedReapDeletes_heldEventIsReleased() {
        disableAutoHeal();
        lost();
        await().atMost(Duration.ofSeconds(5)).until(() -> eventSeen(OperatorWarningCode.INSTANCE_REAP_HELD));
        ctm.drainNode(LOST, DrainReason.REPLACED);
        await().atMost(Duration.ofSeconds(5)).until(() -> terminates.get() == 1);
        settle();

        assertThat(eventsOf(OperatorWarningCode.INSTANCE_REAP_RELEASED)).as("held event left open after the instance was deleted").isEqualTo(1);
    }

    /// A confirmed reap is a fact about the instances listed when it was confirmed, not about the node id: a LATER instance under the same id that never
    /// joined (a zombie of a replacement, an orphan) is a new instance, so with auto-heal disabled it is held and announced, never silently left to bill.
    private void directedReapDoneThenAnotherInstanceUnderTheSameId() {
        disableAutoHeal();
        ctm.drainNode(LOST, DrainReason.REPLACED);
        await().atMost(Duration.ofSeconds(5)).until(() -> terminates.get() == 1);
        settle();
        relist();
    }

    private void orphanSweepFinds(NodeId node) throws ReflectiveOperationException {
        var method = ClusterTopologyManagerRecord.class.getDeclaredMethod("terminateOrphan", NodeId.class);

        method.setAccessible(true);
        method.invoke(ctm, node);
    }

    @Test
    void confirmedReap_thenNeverJoinedZombieUnderTheSameId_autoHealDisabled_isHeldAndAnnounced() {
        directedReapDoneThenAnotherInstanceUnderTheSameId();
        var effectful = effectfulListings.get();

        ctm.drainNode(LOST, DrainReason.JOIN_GRACE_REAP);

        await().atMost(Duration.ofSeconds(5)).until(() -> eventSeen(OperatorWarningCode.INSTANCE_REAP_HELD));
        settle();
        assertThat(effectfulListings.get()).as("the check of a confirmed node is a pure read").isEqualTo(effectful);
        assertThat(terminates.get()).isEqualTo(1);
        assertThat(eventsOf(OperatorWarningCode.INSTANCE_REAP_HELD)).isEqualTo(1);
    }

    @Test
    void confirmedReap_thenOrphanUnderTheSameId_autoHealDisabled_isHeldAndAnnounced() throws ReflectiveOperationException {
        directedReapDoneThenAnotherInstanceUnderTheSameId();

        orphanSweepFinds(LOST);

        await().atMost(Duration.ofSeconds(5)).until(() -> eventSeen(OperatorWarningCode.INSTANCE_REAP_HELD));
        settle();
        assertThat(terminates.get()).isEqualTo(1);
    }

    @Test
    void confirmedReap_thenOrphanUnderTheSameId_afterAnAutomaticReap_autoHealDisabled_isHeldAndAnnounced() throws ReflectiveOperationException {
        lost();
        await().atMost(Duration.ofSeconds(5)).until(() -> terminates.get() == 1);
        settle();
        disableAutoHeal();
        relist();

        orphanSweepFinds(LOST);

        await().atMost(Duration.ofSeconds(5)).until(() -> eventSeen(OperatorWarningCode.INSTANCE_REAP_HELD));
        settle();
        assertThat(terminates.get()).isEqualTo(1);
    }

    /// The memory is of the instances that were deleted: the same instance still listed (a provider whose listing lags), or a listed instance that is
    /// already TERMINATED, is not another instance under the id.
    @Test
    void confirmedReap_thenTheSameInstanceStillListed_autoHealDisabled_isNotHeld() {
        disableAutoHeal();
        ctm.drainNode(LOST, DrainReason.REPLACED);
        await().atMost(Duration.ofSeconds(5)).until(() -> terminates.get() == 1);
        settle();
        listing.set(List.of(instance("i-lost-1")));

        lost();
        settle();

        assertThat(eventsOf(OperatorWarningCode.INSTANCE_REAP_HELD)).isZero();
    }

    @Test
    void confirmedReap_thenOnlyATerminatedInstanceListed_autoHealDisabled_isNotHeld() {
        disableAutoHeal();
        ctm.drainNode(LOST, DrainReason.REPLACED);
        await().atMost(Duration.ofSeconds(5)).until(() -> terminates.get() == 1);
        settle();
        listing.set(List.of(terminatedInstance("i-other-gone")));

        lost();
        settle();

        assertThat(eventsOf(OperatorWarningCode.INSTANCE_REAP_HELD)).isZero();
    }

    /// A directed reap exempts the instance it was issued for, whichever it is: a second directed retirement of the node deletes the new instance.
    @Test
    void directedReap_thenAnotherInstanceUnderTheSameIdDirectedAgain_isDeleted() {
        directedReapDoneThenAnotherInstanceUnderTheSameId();

        ctm.drainNode(LOST, DrainReason.REPLACED);

        await().atMost(Duration.ofSeconds(5)).until(() -> terminates.get() == 2);
        assertThat(eventsOf(OperatorWarningCode.INSTANCE_REAP_HELD)).isZero();
    }

    /// The listing that tells a new instance from a confirmed one can fail: that is a failed attempt, retried, not a dropped reap.
    @Test
    void confirmedReap_listingFailsOnce_thenZombieUnderTheSameId_autoHealDisabled_isStillHeld() {
        directedReapDoneThenAnotherInstanceUnderTheSameId();
        failNextListing.set(true);

        lost();

        await().atMost(Duration.ofSeconds(5)).until(() -> eventSeen(OperatorWarningCode.INSTANCE_REAP_HELD));
        assertThat(terminates.get()).isEqualTo(1);
    }

    /// A node that joined while the listing was in flight is a new incarnation: the reap of the old one does nothing to it, held or otherwise.
    @Test
    void confirmedReap_nodeJoinsWhileTheListingIsInFlight_autoHealDisabled_raisesNoHeldEvent() {
        directedReapDoneThenAnotherInstanceUnderTheSameId();
        var before = listings.get();
        onNextListing.set(this::joinsAgain);

        lost();
        await().atMost(Duration.ofSeconds(5)).until(() -> listings.get() > before);
        settle();

        assertThat(eventsOf(OperatorWarningCode.INSTANCE_REAP_HELD)).isZero();
    }

    /// Auto-heal enabled: nothing is held, so a second reap of a confirmed node stays a no-op that asks the provider nothing.
    @Test
    void confirmedReap_secondReapOfTheSameNode_autoHealEnabled_asksTheProviderNothing() {
        lost();
        await().atMost(Duration.ofSeconds(5)).until(() -> terminates.get() == 1);
        settle();
        var asked = listings.get();

        lost();
        settle();

        assertThat(listings.get()).as("no provider call for a second reap").isEqualTo(asked);
        assertThat(terminates.get()).isEqualTo(1);
    }

    /// An EXTERNAL node has no provider instance of ours: nothing under its id is read at the provider, so nothing is held for it.
    @Test
    void confirmedReap_ofAnExternalNode_thenALaterListing_autoHealDisabled_isNotHeld() {
        externalNodes.add(LOST);
        disableAutoHeal();
        ctm.drainNode(LOST, DrainReason.REPLACED);
        settle();
        relist();

        lost();
        settle();

        assertThat(eventsOf(OperatorWarningCode.INSTANCE_REAP_HELD)).isZero();
    }

    /// The held zombie is not forgotten: once auto-heal is back the next re-arm deletes it (the confirmation did not cover it) and closes the event.
    @Test
    void confirmedReap_thenHeldZombieUnderTheSameId_whenAutoHealIsEnabledAgain_isDeleted() {
        directedReapDoneThenAnotherInstanceUnderTheSameId();
        ctm.drainNode(LOST, DrainReason.JOIN_GRACE_REAP);
        await().atMost(Duration.ofSeconds(5)).until(() -> eventSeen(OperatorWarningCode.INSTANCE_REAP_HELD));

        autoHealEnabled.set(true);
        ctm.onSwimFaulty(LOST);

        await().atMost(Duration.ofSeconds(5)).until(() -> terminates.get() == 2);
        await().atMost(Duration.ofSeconds(5)).until(() -> eventSeen(OperatorWarningCode.INSTANCE_REAP_RELEASED));
    }

    /// The directed reap closed the held mark: the rejoin that follows has nothing left to release, so the recovery is published once.
    @Test
    void nodeRemovedHeld_thenReplacedReapDeletes_rejoinReleasesNothingMore() {
        disableAutoHeal();
        lost();
        await().atMost(Duration.ofSeconds(5)).until(() -> eventSeen(OperatorWarningCode.INSTANCE_REAP_HELD));
        ctm.drainNode(LOST, DrainReason.REPLACED);
        await().atMost(Duration.ofSeconds(5)).until(() -> eventsOf(OperatorWarningCode.INSTANCE_REAP_RELEASED) == 1);

        joinsAgain();
        settle();

        assertThat(eventsOf(OperatorWarningCode.INSTANCE_REAP_RELEASED)).as("the held mark outlived the directed reap").isEqualTo(1);
    }

    /// The rejoin closes the held mark, not only the event: a later loss of the same node is held and announced again.
    @Test
    void heldThenRejoinThenLostAgain_isAnnouncedAgain() {
        disableAutoHeal();
        lost();
        await().atMost(Duration.ofSeconds(5)).until(() -> eventsOf(OperatorWarningCode.INSTANCE_REAP_HELD) == 1);
        ctm.onMembershipDecision(MembershipDecision.nodeJoined(LOST, List.of(SELF, LOST)));
        await().atMost(Duration.ofSeconds(5)).until(() -> eventSeen(OperatorWarningCode.INSTANCE_REAP_RELEASED));

        lost();

        await().atMost(Duration.ofSeconds(5)).until(() -> eventsOf(OperatorWarningCode.INSTANCE_REAP_HELD) == 2);
    }

    /// The production lifecycle's `sourceOf` is total (a source is always named); the single-provider record names none, so this names the default.
    private record SourcedLifecycle(NodeLifecycleManager delegate,
                                     Predicate<NodeId> external,
                                     AtomicInteger effectfulListings) implements NodeLifecycleManager {
        /// The listing a reap chain makes: in production it commits and releases reservations, so it is counted apart from the pure read.
        @Override
        public Promise<List<InstanceInfo>> instancesForNode(NodeId nodeId, SourceName source) {
            effectfulListings.incrementAndGet();

            return delegate.instancesForNode(nodeId);
        }

        @Override
        public Promise<List<InstanceInfo>> readInstancesForNode(NodeId nodeId, SourceName source) {
            return delegate.instancesForNode(nodeId);
        }

        @Override
        public boolean externalNode(NodeId nodeId) {
            return external.test(nodeId);
        }

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

            onNextListing.getAndSet(() -> {}).run();
            if (failNextListing.getAndSet(false)) {
                return EnvironmentError.operationNotSupported("transient listing failure").promise();
            }

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
