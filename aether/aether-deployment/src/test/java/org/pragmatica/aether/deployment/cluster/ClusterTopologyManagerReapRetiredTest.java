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
        return ctm.reapRetired(OLD, SourceName.DEFAULT).await();
    }

    @Test
    void emptyListing_isGone_withoutATerminate_soANewLeaderCanRepeatIt() {
        assertThat(reap().isSuccess()).isTrue();
        assertThat(terminates.get()).isZero();
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

        assertThat(reap().isSuccess()).isTrue();
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

    @Test
    void stoppedOrTerminatedInstances_areGone_butAnUnstatedOneIsNot() {
        providerLists(oldInstance("i-1", InstanceStatus.STOPPING), oldInstance("i-2", InstanceStatus.TERMINATED));
        assertThat(reap().isSuccess()).isTrue();
        assertThat(terminates.get()).isZero();

        providerLists(oldInstance("i-3", InstanceStatus.UNKNOWN));
        terminateRemoves.set(false);
        assertThat(reap().isFailure()).as("a status the provider cannot state is not 'gone'").isTrue();
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
