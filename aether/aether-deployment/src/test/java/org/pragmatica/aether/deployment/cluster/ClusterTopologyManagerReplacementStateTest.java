// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;
import java.util.Map;
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

/// #1049 — the CTM's two answers to the `LeaderReconciler`'s in-flight tracker, driven through the REAL
/// `NodeLifecycleManager` down to a `ComputeProvider` fake:
///
/// - `replacementInstanceState` classifies the provider's listing for a replacement's node id. The fake
///   implements only the no-arg `listInstances()` and stamps a real `aether.node-id` tag, so the
///   production default tag filter runs against it — a fake that ignored the filter would hide the exact
///   key mismatch that made AWS / GCP / Azure read every existing replacement as absent.
/// - `replacementCeiling` resolves the per-source ceiling from the persisted cluster config, so the value
///   an operator writes is the value the runtime reads (the #675 "parsed, never read" shape is the
///   failure this pins against).
class ClusterTopologyManagerReplacementStateTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId REPLACEMENT = nodeId("aether-prod-node-01J00000000000000000000000").unwrap();
    private static final NodeId OTHER_NODE = nodeId("aether-prod-node-01J11111111111111111111111").unwrap();
    private static final String NODE_ID_TAG = "aether.node-id";

    private static final String CLOUD_TOML = """
            config_version = "1.0.0"

            [cluster]
            name = "prod-cluster"
            version = "1.0.0"

            [source.eu-1]
            type = "cloud"
            provider = "hetzner"
            region = "eu-central"
            %s

            [source.eu-1.core]
            count = 3
            """;

    private final AtomicReference<Promise<List<InstanceInfo>>> listing = new AtomicReference<>(Promise.success(List.of()));
    private AtomicReference<Option<ClusterConfigValue>> configRef;
    private ClusterTopologyManager ctm;

    @BeforeEach
    void setUp() {
        configRef = new AtomicReference<>(Option.none());
        ctm = ctmOver(NodeLifecycleManager.nodeLifecycleManager(Option.some(new ListingProvider(listing))));
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
                                                            configRef::get,
                                                            ClusterTopologyManagerReplacementStateTest::applyNoop,
                                                            () -> AetherValue.ClusterPhase.NORMAL,
                                                            _ -> {},
                                                            _ -> {},
                                                            Option::none,
                                                            MembershipLiveness.UNWIRED);
    }

    private static Promise<List<Object>> applyNoop(List<KVCommand<AetherKey>> commands) {
        return Promise.success(List.of());
    }

    private ReplacementInstanceState stateOf(NodeId nodeId) {
        return ctm.replacementInstanceState(nodeId)
                  .await()
                  .fold(cause -> fail("replacementInstanceState must not fail — an unanswerable query is UNKNOWN, got: " + cause.message()),
                        state -> state);
    }

    private void providerLists(InstanceInfo... instances) {
        listing.set(Promise.success(List.of(instances)));
    }

    private static InstanceInfo instanceOf(NodeId nodeId, String instanceId, InstanceStatus status) {
        return new InstanceInfo(InstanceId.instanceId(instanceId).unwrap(),
                                status,
                                List.of(),
                                InstanceType.ON_DEMAND,
                                Map.of(NODE_ID_TAG, nodeId.id()),
                                Option.some(nodeId.id()), org.pragmatica.lang.Option.none());
    }

    @Test
    void replacementInstanceState_provisioningInstance_isPresent() {
        providerLists(instanceOf(REPLACEMENT, "i-1", InstanceStatus.PROVISIONING));

        assertThat(stateOf(REPLACEMENT)).isEqualTo(ReplacementInstanceState.PRESENT);
    }

    @Test
    void replacementInstanceState_runningInstance_isPresent() {
        providerLists(instanceOf(REPLACEMENT, "i-1", InstanceStatus.RUNNING));

        assertThat(stateOf(REPLACEMENT)).isEqualTo(ReplacementInstanceState.PRESENT);
    }

    @Test
    void replacementInstanceState_onlyStoppingOrTerminatedInstances_isFailed() {
        providerLists(instanceOf(REPLACEMENT, "i-1", InstanceStatus.STOPPING),
                      instanceOf(REPLACEMENT, "i-2", InstanceStatus.TERMINATED));

        assertThat(stateOf(REPLACEMENT)).isEqualTo(ReplacementInstanceState.FAILED);
    }

    /// A stopped leftover under the same id does not mask a live instance: a replacement is still coming.
    @Test
    void replacementInstanceState_stoppedLeftoverBesideRunningInstance_isPresent() {
        providerLists(instanceOf(REPLACEMENT, "i-1", InstanceStatus.TERMINATED),
                      instanceOf(REPLACEMENT, "i-2", InstanceStatus.RUNNING));

        assertThat(stateOf(REPLACEMENT)).isEqualTo(ReplacementInstanceState.PRESENT);
    }

    /// #1049 round 3 (S2) — a status the provider could not state (Hetzner `unknown`, GCP `REPAIRING`,
    /// anything unmapped) is UNKNOWN, never FAILED: FAILED drops the replacement at once while it may exist.
    @Test
    void replacementInstanceState_unknownStatusInstance_isUnknown_neverFailed() {
        providerLists(instanceOf(REPLACEMENT, "i-1", InstanceStatus.UNKNOWN));

        assertThat(stateOf(REPLACEMENT)).isEqualTo(ReplacementInstanceState.UNKNOWN);
    }

    @Test
    void replacementInstanceState_unknownStatusBesideStoppedLeftover_isUnknown_neverFailed() {
        providerLists(instanceOf(REPLACEMENT, "i-1", InstanceStatus.TERMINATED),
                      instanceOf(REPLACEMENT, "i-2", InstanceStatus.UNKNOWN));

        assertThat(stateOf(REPLACEMENT)).isEqualTo(ReplacementInstanceState.UNKNOWN);
    }

    @Test
    void replacementInstanceState_unknownStatusBesideRunningInstance_isPresent() {
        providerLists(instanceOf(REPLACEMENT, "i-1", InstanceStatus.UNKNOWN),
                      instanceOf(REPLACEMENT, "i-2", InstanceStatus.RUNNING));

        assertThat(stateOf(REPLACEMENT)).isEqualTo(ReplacementInstanceState.PRESENT);
    }

    /// The listing is scoped by node id: another node's running instance must not answer for this one.
    @Test
    void replacementInstanceState_noInstanceForThisNode_isAbsent_evenWhenOtherNodesRun() {
        providerLists(instanceOf(OTHER_NODE, "i-9", InstanceStatus.RUNNING));

        assertThat(stateOf(REPLACEMENT)).isEqualTo(ReplacementInstanceState.ABSENT);
    }

    @Test
    void replacementInstanceState_listingFails_isUnknown() {
        listing.set(EnvironmentError.listInstancesFailed(new RuntimeException("503 from provider")).promise());

        assertThat(stateOf(REPLACEMENT)).isEqualTo(ReplacementInstanceState.UNKNOWN);
    }

    @Test
    void replacementInstanceState_noComputeProvider_isUnknown_neverAbsent() {
        ctm = ctmOver(NodeLifecycleManager.nodeLifecycleManager(Option.none()));

        assertThat(stateOf(REPLACEMENT)).isEqualTo(ReplacementInstanceState.UNKNOWN);
    }

    @Test
    void replacementCeiling_noPersistedConfig_isTenMinuteDefault() {
        assertThat(ctm.replacementCeiling(NodeRole.CORE).millis()).isEqualTo(timeSpan(10).minutes().millis());
        assertThat(SourceProfile.DEFAULT_REPLACEMENT_CEILING.millis()).isEqualTo(timeSpan(10).minutes().millis());
    }

    /// The wiring half: the `replacement_ceiling` written on the cloud source backing the role is what the
    /// runtime reads, through the persisted cluster config.
    @Test
    void replacementCeiling_configuredOnCloudSourceBackingTheRole_isRead() {
        seedConfig(CLOUD_TOML.formatted("replacement_ceiling = \"7m\""));

        assertThat(ctm.replacementCeiling(NodeRole.CORE).millis()).isEqualTo(timeSpan(7).minutes().millis());
    }

    @Test
    void replacementCeiling_roleNotBackedByTheConfiguredSource_isDefault() {
        seedConfig(CLOUD_TOML.formatted("replacement_ceiling = \"7m\""));

        assertThat(ctm.replacementCeiling(NodeRole.WORKER).millis()).isEqualTo(timeSpan(10).minutes().millis());
    }

    private void seedConfig(String toml) {
        configRef.set(Option.some(new ClusterConfigValue(toml, "prod", "1.0.0", List.of(), 3, 9, "test", 1L,
                                                         System.currentTimeMillis())));
    }

    private record ListingProvider(AtomicReference<Promise<List<InstanceInfo>>> listing) implements ComputeProvider {
        @Override
        public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
            return EnvironmentError.operationNotSupported("createFrom").promise();
        }

        @Override
        public Promise<Unit> terminate(InstanceId instanceId) {
            return EnvironmentError.operationNotSupported("terminate").promise();
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
