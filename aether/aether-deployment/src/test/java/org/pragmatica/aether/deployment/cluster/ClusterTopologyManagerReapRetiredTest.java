// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.ComputeProvider;
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
import org.pragmatica.cluster.state.kvstore.KVCommand;
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
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1543: `reapRetired` confirms a retired node's provider instance is GONE before a replacement may be DONE, driven through the REAL
/// `NodeLifecycleManager` down to a `ComputeProvider` fake. The property pinned: a listing that fails, an instance still listed after the
/// terminate, and a status the provider cannot state are all "not confirmed"; only an empty (or all stopped/terminated) listing is gone.
class ClusterTopologyManagerReapRetiredTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId OLD = nodeId("aether-prod-node-01J00000000000000000000000").unwrap();
    private static final String NODE_ID_TAG = "aether.node-id";

    private final AtomicReference<Promise<List<InstanceInfo>>> listing = new AtomicReference<>(Promise.success(List.of()));
    private final AtomicInteger terminates = new AtomicInteger();
    private final AtomicReference<Promise<Unit>> terminateOutcome = new AtomicReference<>(Promise.unitPromise());
    private final AtomicBoolean terminateRemoves = new AtomicBoolean(true);
    private ClusterTopologyManager ctm;

    @BeforeEach
    void setUp() {
        ctm = ctmOver(NodeLifecycleManager.nodeLifecycleManager(Option.some(new FakeProvider())));
        ctm.setRetirementRefusal(_ -> Option.none());
        ctm.activate();
    }

    private ClusterTopologyManager ctmOver(NodeLifecycleManager lifecycleManager) {
        var snapshotSource = new StubSnapshotSource();
        var self = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("localhost", 5000).unwrap());
        var config = new TopologyConfig(SELF, 3, timeSpan(60).seconds(), timeSpan(1).seconds(), List.of(self));
        var observer = TopologyObserver.topologyObserver(config, MessageRouter.mutable(), snapshotSource).unwrap();

        return ClusterTopologyManager.clusterTopologyManager(observer,
                                                            lifecycleManager,
                                                            AutoHealConfig.DEFAULT,
                                                            DeploymentMap.deploymentMap(),
                                                            snapshotSource,
                                                            () -> Option.<ClusterConfigValue> none(),
                                                            commands -> Promise.success(List.<Object> of()),
                                                            () -> AetherValue.ClusterPhase.NORMAL,
                                                            _ -> {},
                                                            _ -> {},
                                                            Option::none,
                                                            MembershipLiveness.UNWIRED);
    }

    private void providerLists(InstanceInfo... instances) {
        listing.set(Promise.success(List.of(instances)));
    }

    private static InstanceInfo oldInstance(String instanceId, InstanceStatus status) {
        return new InstanceInfo(InstanceId.instanceId(instanceId).unwrap(),
                                status,
                                List.of(),
                                InstanceType.ON_DEMAND,
                                Map.of(NODE_ID_TAG, OLD.id()),
                                Option.some(OLD.id()), org.pragmatica.lang.Option.none());
    }

    private org.pragmatica.lang.Result<Unit> reap() {
        return reap(true);
    }

    private org.pragmatica.lang.Result<Unit> reap(boolean seenBefore) {
        return ctm.reapRetired(OLD, SourceName.DEFAULT, seenBefore).await();
    }

    @Test
    void emptyListing_ofAnInstanceSeenBefore_isGone_withoutATerminate_soANewLeaderCanRepeatIt() {
        assertThat(reap(true).isSuccess()).isTrue();
        assertThat(terminates.get()).isZero();
    }

    /// v-2008 r6: an empty listing proves nothing for an instance the provider never listed (a dispatched replacement that has not
    /// appeared yet, an unlabelled VM): not confirmed gone, and the failure names the node.
    @Test
    void emptyListing_ofAnInstanceNeverListed_isNotGone_andNamesTheNode() {
        var result = reap(false);

        assertThat(result.isFailure()).as("one empty listing is not a confirmation").isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains(OLD.id()).contains("never listed"));
        assertThat(terminates.get()).isZero();
    }

    /// #1111: a caller that gave up on confirming an orphan's termination marks it unconfirmed on the manager (`markUnconfirmed`), the single raiser of the pair. The
    /// unconfirmed event fires once however often it is handed over, and a LATER confirmed reap of the node (here the departure reap, as
    /// the grace backstop and the activation replay take) raises the matching confirmed event for the same subject.
    @Test
    void announcedUnconfirmedTermination_firesOnce_andIsClearedByALaterConfirmedReap() {
        var events = new java.util.concurrent.CopyOnWriteArrayList<org.pragmatica.utility.warning.OperatorWarning>();

        ctm.setOperatorWarningSink(org.pragmatica.utility.warning.OperatorWarningSink.handingOffTo(events::add));
        ctm.markUnconfirmed(OLD, "still listed at the provider; provider instance(s) [i-1]");
        ctm.markUnconfirmed(OLD, "again");

        org.awaitility.Awaitility.await().atMost(2, java.util.concurrent.TimeUnit.SECONDS).until(() -> events.size() == 1);
        assertThat(events.getFirst().code()).isEqualTo(org.pragmatica.utility.warning.OperatorWarningCode.INSTANCE_TERMINATION_UNCONFIRMED);
        assertThat(events.getFirst().subject()).isEqualTo(OLD.id());
        assertThat(events.getFirst().message()).contains("i-1");

        providerLists(oldInstance("i-1", InstanceStatus.RUNNING));
        ctm.onMembershipDecision(org.pragmatica.consensus.topology.MembershipDecision.nodeRemoved(OLD, List.of(SELF)));

        org.awaitility.Awaitility.await().atMost(5, java.util.concurrent.TimeUnit.SECONDS).until(() -> events.size() == 2);
        assertThat(events.getLast().code()).isEqualTo(org.pragmatica.utility.warning.OperatorWarningCode.INSTANCE_TERMINATION_CONFIRMED);
        assertThat(events.getLast().subject()).isEqualTo(OLD.id());
        assertThat(terminates.get()).as("the later reap terminated the instance").isEqualTo(1);
    }

    @Test
    void anInstanceListedNowAsTerminated_needsNoEarlierObservation() {
        providerLists(oldInstance("i-1", InstanceStatus.TERMINATED));

        assertThat(reap(false).isSuccess()).as("this very listing saw it").isTrue();
    }

    @Test
    void instanceListed_isTrueForAnyStatus_falseWhenNothingIsListed_andFailsWhenTheListingFails() {
        assertThat(ctm.instanceListed(OLD, SourceName.DEFAULT).await().unwrap()).isFalse();
        providerLists(oldInstance("i-1", InstanceStatus.TERMINATED));
        assertThat(ctm.instanceListed(OLD, SourceName.DEFAULT).await().unwrap()).isTrue();
        listing.set(EnvironmentError.operationNotSupported("provider API down").promise());
        assertThat(ctm.instanceListed(OLD, SourceName.DEFAULT).await().isFailure()).as("a failed listing is not 'nothing listed'").isTrue();
    }

    @Test
    void aListingThatFails_isNeverReadAsGone() {
        listing.set(EnvironmentError.operationNotSupported("provider API down").promise());

        var result = reap();

        assertThat(result.isFailure()).as("a failed listing is not an empty one").isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains(OLD.id()).contains("provider API down"));
        assertThat(terminates.get()).isZero();
    }

    @Test
    void aLiveInstance_isTerminated_andConfirmedGoneByASecondListing() {
        providerLists(oldInstance("i-1", InstanceStatus.RUNNING));

        assertThat(reap(false).isSuccess()).as("listed now, terminated, then an empty listing after the accepted terminate").isTrue();
        assertThat(terminates.get()).isEqualTo(1);
    }

    @Test
    void aTerminateThatDoesNotRemoveTheInstance_isNotConfirmed_andNamesTheInstance() {
        providerLists(oldInstance("i-1", InstanceStatus.RUNNING));
        terminateRemoves.set(false);

        var result = reap();

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("still listed").contains("i-1"));
    }

    @Test
    void aTerminateThatFails_isNotConfirmed_withTheProvidersCause() {
        providerLists(oldInstance("i-1", InstanceStatus.RUNNING));
        terminateOutcome.set(EnvironmentError.operationNotSupported("quota").promise());

        var result = reap();

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("quota"));
    }

    @Test
    void aListingAfterTheTerminateThatFails_isNotConfirmed() {
        providerLists(oldInstance("i-1", InstanceStatus.RUNNING));
        terminateRemoves.set(false);
        terminateOutcome.set(Promise.<Unit> unitPromise().onSuccessRun(() -> listing.set(EnvironmentError.operationNotSupported("listing broke").promise())));

        assertThat(reap().isFailure()).isTrue();
    }

    /// A STOPPED machine is not gone (ruling af2acb10d): providers map an off or exited machine to STOPPING and it still bills and holds
    /// its slot. It is terminated, then confirmed; only TERMINATED instances need no terminate.
    @Test
    void aStoppedInstance_isTerminated_notConfirmedInPlace() {
        providerLists(oldInstance("i-1", InstanceStatus.STOPPING));

        assertThat(reap(false).isSuccess()).isTrue();
        assertThat(terminates.get()).as("terminated, not confirmed in place").isEqualTo(1);
    }

    @Test
    void terminatedInstances_areGone_withoutATerminate() {
        providerLists(oldInstance("i-1", InstanceStatus.TERMINATED), oldInstance("i-2", InstanceStatus.TERMINATED));

        assertThat(reap().isSuccess()).isTrue();
        assertThat(terminates.get()).isZero();
    }

    @Test
    void anUnstatedInstance_isNotGone() {
        providerLists(oldInstance("i-3", InstanceStatus.UNKNOWN));
        terminateRemoves.set(false);

        assertThat(reap().isFailure()).as("a status the provider cannot state is not 'gone'").isTrue();
    }

    /// B1: a reap confirmed once is idempotent per node: a second reap (the drain-grace backstop after NodeRemoved) asks the provider nothing.
    @Test
    void aSecondReapOfAConfirmedNode_makesNoProviderCall() {
        providerLists(oldInstance("i-1", InstanceStatus.RUNNING));
        assertThat(reap(false).isSuccess()).isTrue();
        var calls = terminates.get();

        listing.set(EnvironmentError.operationNotSupported("provider API down").promise());

        assertThat(reap(false).isSuccess()).as("already confirmed: not re-asked, so not failed by a later listing").isTrue();
        assertThat(terminates.get()).isEqualTo(calls);
    }

    /// B3: a provider whose listing lags its delete. The first reap saw the instance and had its terminate accepted but the relisting still
    /// showed it (failure); the retry, whose listing is now empty, is gone because this node REMEMBERS having seen it.
    @Test
    void aLaggingListing_isConfirmedByTheRetry_becauseTheNodeWasSeenAndTerminated() {
        providerLists(oldInstance("i-1", InstanceStatus.RUNNING));
        terminateRemoves.set(false);

        assertThat(reap(false).isFailure()).as("terminate accepted, listing still shows it").isTrue();

        listing.set(Promise.success(List.of()));

        assertThat(reap(false).isSuccess()).as("the retry's empty listing is gone: seen and terminated before").isTrue();
    }

    @Test
    void aRetirementRefusal_isHonoured_aVoterIsNeverReaped() {
        providerLists(oldInstance("i-1", InstanceStatus.RUNNING));
        ctm.setRetirementRefusal(_ -> Option.some("still an installed voter"));

        var result = reap();

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("refused").contains("still an installed voter"));
        assertThat(terminates.get()).isZero();
    }

    @Test
    void anInactiveTopologyManager_confirmsNothing() {
        ctm.deactivate();

        assertThat(reap().isFailure()).isTrue();
        assertThat(terminates.get()).isZero();
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

            return terminateOutcome.get();
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
