// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.config.cluster.CloudProviderName;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterIdentity;
import org.pragmatica.aether.config.cluster.CoreTopology;
import org.pragmatica.aether.config.cluster.InfrastructureConfig;
import org.pragmatica.aether.config.cluster.LoadBalancerMode;
import org.pragmatica.aether.config.cluster.NetworkingType;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.OperationsConfig;
import org.pragmatica.aether.config.cluster.RoleSubTable;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootstrapPhaseProvisionUserDataTest {

    private static SourceProfile cloudHetznerSource(int coreCount) {
        return SourceProfile.sourceProfile("eu-1",
                                           SourceType.CLOUD,
                                           Option.some(CloudProviderName.HETZNER),
                                           Option.some("dummy-token"),
                                           Option.some("eu-central"),
                                           Option.empty(),
                                           Option.empty(),
                                           Option.empty(),
                                           Option.empty(),
                                           LoadBalancerMode.NONE,
                                           List.of(),
                                           Option.empty(),
                                           Map.of(),
                                           Map.of(NodeRole.CORE,
                                                  RoleSubTable.roleSubTable(NodeRole.CORE,
                                                                            Option.some(coreCount),
                                                                            Option.empty(),
                                                                            Option.empty(),
                                                                            "default")),
                                           List.of());
    }

    /// RFC-0016 W2 — a cloud source whose CORE role sets NO image but whose WORKER role carries its
    /// own `image`, so the seed spec's tier-1 imageId can be asserted per role (worker → its image,
    /// core → absent, no cross-role fallback).
    private static SourceProfile cloudSourceWithPerRoleImages() {
        return SourceProfile.sourceProfile("eu-1",
                                           SourceType.CLOUD,
                                           Option.some(CloudProviderName.HETZNER),
                                           Option.some("dummy-token"),
                                           Option.some("eu-central"),
                                           Option.empty(),
                                           Option.empty(),
                                           Option.empty(),
                                           Option.empty(),
                                           LoadBalancerMode.NONE,
                                           List.of(),
                                           Option.empty(),
                                           Map.of(),
                                           Map.of(NodeRole.CORE,
                                                  RoleSubTable.roleSubTable(NodeRole.CORE,
                                                                            Option.some(1),
                                                                            Option.empty(),
                                                                            Option.empty(),
                                                                            "default"),
                                                  NodeRole.WORKER,
                                                  RoleSubTable.roleSubTable(NodeRole.WORKER,
                                                                            Option.some(1),
                                                                            Option.empty(),
                                                                            Option.empty(),
                                                                            Option.some("worker-snap-99"),
                                                                            "default")),
                                           List.of());
    }

    private static ClusterBootstrapConfig configWithSource(SourceProfile source) {
        return ClusterBootstrapConfig.clusterBootstrapConfig("1.0.0",
                                                             ClusterIdentity.clusterIdentity("prod", "1.0.0").unwrap(),
                                                             CoreTopology.defaultCoreTopology(),
                                                             Map.of("eu-1", source),
                                                             Map.of(),
                                                             InfrastructureConfig.infrastructureConfig(NetworkingType.MANUAL),
                                                             OperationsConfig.defaultOperationsConfig());
    }

    private static BootstrapContext baseContext(ClusterBootstrapConfig config) {
        var state = BootstrapState.initialState("prod", "h", "now").withClusterSecret("test-secret-xyz");
        return BootstrapContext.bootstrapContext(config, state, List.of(), List.of())
                               .withClusterSecret("test-secret-xyz");
    }

    /// ComputeProvider stub: captures every spec passed to provision(), then returns
    /// a synthetic InstanceInfo so the bootstrap loop can continue. We can then assert
    /// over each captured spec's tags + userData to confirm per-node distinct payloads.
    private static final class CapturingCompute implements ComputeProvider {
        final List<ProvisionSpec> calls = new ArrayList<>();
        private final AtomicLong nextId = new AtomicLong(100);

        @Override public Promise<InstanceInfo> provision(InstanceType instanceType) {
            return Promise.success(makeInfo());
        }

        @Override public Promise<InstanceInfo> provision(ProvisionSpec spec) {
            calls.add(spec);
            return Promise.success(makeInfo());
        }

        @Override public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
            return Promise.success(makeInfo());
        }

        private InstanceInfo makeInfo() {
            var id = nextId.getAndIncrement();
            return new InstanceInfo(new InstanceId(String.valueOf(id)),
                                    InstanceStatus.RUNNING,
                                    List.of("203.0.113." + id),
                                    InstanceType.ON_DEMAND,
                                    Map.of());
        }

        @Override public Promise<Unit> terminate(InstanceId instanceId) {
            return Promise.success(Unit.unit());
        }

        @Override public Promise<List<InstanceInfo>> listInstances() {
            return Promise.success(List.of());
        }

        @Override public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
            return Promise.success(makeInfo());
        }
    }

    @Test
    void buildCloudProvisionSpec_emitsPerNodeDistinctUserData_threeCoreNodes() {
        var source = cloudHetznerSource(3);
        var ctx = baseContext(configWithSource(source));

        var n0 = invokeBuildCloudProvisionSpec(ctx, source, "eu-1-core-0", 0);
        var n1 = invokeBuildCloudProvisionSpec(ctx, source, "eu-1-core-1", 1);
        var n2 = invokeBuildCloudProvisionSpec(ctx, source, "eu-1-core-2", 2);

        var ud0 = n0.userData().or("");
        var ud1 = n1.userData().or("");
        var ud2 = n2.userData().or("");

        assertFalse(ud0.isEmpty(), "user_data must not be empty");
        assertNotEquals(ud0, ud1, "Each VM must receive its own cloud-init user_data (node 0 vs 1)");
        assertNotEquals(ud0, ud2, "Each VM must receive its own cloud-init user_data (node 0 vs 2)");
        assertNotEquals(ud1, ud2, "Each VM must receive its own cloud-init user_data (node 1 vs 2)");
    }

    @Test
    void buildCloudProvisionSpec_eachUserDataMentionsCorrectNodeId() {
        var source = cloudHetznerSource(3);
        var ctx = baseContext(configWithSource(source));

        var n0 = invokeBuildCloudProvisionSpec(ctx, source, "eu-1-core-0", 0);
        var n2 = invokeBuildCloudProvisionSpec(ctx, source, "eu-1-core-2", 2);

        assertTrue(n0.userData().or("").contains("AETHER_NODE_ID=\"eu-1-core-0\""),
                   "Node 0 user_data must announce AETHER_NODE_ID=eu-1-core-0");
        assertTrue(n2.userData().or("").contains("AETHER_NODE_ID=\"eu-1-core-2\""),
                   "Node 2 user_data must announce AETHER_NODE_ID=eu-1-core-2");
        assertFalse(n0.userData().or("").contains("AETHER_NODE_ID=\"eu-1-core-2\""),
                    "Node 0 user_data must not leak node 2's id");
    }

    @Test
    void buildCloudProvisionSpec_userDataInstallsDocker_andRunsContainer() {
        var source = cloudHetznerSource(1);
        var ctx = baseContext(configWithSource(source));

        var spec = invokeBuildCloudProvisionSpec(ctx, source, "eu-1-core-0", 0);
        var ud = spec.userData().or("");

        assertTrue(ud.contains("docker pull"),
                   "Cloud-init must pull the aether-node image (otherwise stock Ubuntu has no aether)");
        assertTrue(ud.contains("docker run"),
                   "Cloud-init must launch the aether-node container");
        assertTrue(ud.contains("/opt/aether/config/aether.toml"),
                   "Cloud-init must write the composed aether.toml config");
    }

    @Test
    void buildCloudProvisionSpec_userDataIncludesClusterSecret() {
        var source = cloudHetznerSource(1);
        var ctx = baseContext(configWithSource(source));

        var spec = invokeBuildCloudProvisionSpec(ctx, source, "eu-1-core-0", 0);
        var ud = spec.userData().or("");

        assertTrue(ud.contains("AETHER_CLUSTER_SECRET=\"test-secret-xyz\""),
                   "Cluster secret must be embedded in cloud-init script (header/env vars)");
    }

    @Test
    void buildCloudProvisionSpec_specCarriesPerNodeTags() {
        var source = cloudHetznerSource(2);
        var ctx = baseContext(configWithSource(source));

        var spec = invokeBuildCloudProvisionSpec(ctx, source, "eu-1-core-0", 0);

        assertEquals("prod", spec.context().clusterName(),
                     "Context must include cluster name so reaper can label-match orphans");
        assertEquals("eu-1", spec.context().sourceName());
        assertEquals("core", spec.context().role());
    }

    @Test
    void buildCloudProvisionSpec_threadsDistinctNodeIdEnvVarPerVm() {
        // Bug 14: each VM's user_data must export its own NODE_ID so Main.parseNodeId
        // resolves a deterministic id rather than randomNodeId().
        var source = cloudHetznerSource(3);
        var ctx = baseContext(configWithSource(source));

        var ud0 = invokeBuildCloudProvisionSpec(ctx, source, "eu-1-core-0", 0).userData().or("");
        var ud1 = invokeBuildCloudProvisionSpec(ctx, source, "eu-1-core-1", 1).userData().or("");
        var ud2 = invokeBuildCloudProvisionSpec(ctx, source, "eu-1-core-2", 2).userData().or("");

        assertTrue(ud0.contains("AETHER_NODE_ID=\"eu-1-core-0\""));
        assertTrue(ud1.contains("AETHER_NODE_ID=\"eu-1-core-1\""));
        assertTrue(ud2.contains("AETHER_NODE_ID=\"eu-1-core-2\""));
        assertTrue(ud0.contains("-e NODE_ID=\"${AETHER_NODE_ID}\""),
                   "docker run -e NODE_ID feeds Main.parseNodeId via env so the node lands in coreNodes");
    }

    @Test
    void buildCloudProvisionSpec_threadsIdenticalClusterAndManagementPortAcrossVms() {
        // Per-cluster invariant: every VM uses the same operator-supplied ports.
        var source = cloudHetznerSource(3);
        var ctx = baseContext(configWithSource(source));

        var ud0 = invokeBuildCloudProvisionSpec(ctx, source, "eu-1-core-0", 0).userData().or("");
        var ud2 = invokeBuildCloudProvisionSpec(ctx, source, "eu-1-core-2", 2).userData().or("");

        // OperationsConfig.defaultOperationsConfig() sets cluster=8090, management=8080.
        assertTrue(ud0.contains("AETHER_CLUSTER_PORT=\"8090\""),
                   "Cluster port must come from operations.ports.cluster");
        assertTrue(ud0.contains("AETHER_MANAGEMENT_PORT=\"8080\""),
                   "Management port must come from operations.ports.management");
        assertTrue(ud2.contains("AETHER_CLUSTER_PORT=\"8090\""), "All VMs share cluster port");
        assertTrue(ud2.contains("AETHER_MANAGEMENT_PORT=\"8080\""), "All VMs share mgmt port");
        assertTrue(ud0.contains("-e CLUSTER_PORT=\"${AETHER_CLUSTER_PORT}\""),
                   "docker run must export CLUSTER_PORT for the bundled entrypoint to consume");
        assertTrue(ud0.contains("-e MANAGEMENT_PORT=\"${AETHER_MANAGEMENT_PORT}\""),
                   "docker run must export MANAGEMENT_PORT for the bundled entrypoint to consume");
    }

    @Test
    void buildCloudProvisionSpec_threadsClusterSecretAsContainerEnvVar() {
        // Without -e AETHER_CLUSTER_SECRET, Main.resolveClusterSecret falls back
        // to the empty default and TLS init fails (the original Bug 13 symptom).
        var source = cloudHetznerSource(1);
        var ctx = baseContext(configWithSource(source));

        var ud = invokeBuildCloudProvisionSpec(ctx, source, "eu-1-core-0", 0).userData().or("");

        assertTrue(ud.contains("-e AETHER_CLUSTER_SECRET=\"${AETHER_CLUSTER_SECRET}\""),
                   "docker run must export AETHER_CLUSTER_SECRET so the env-fallback path resolves");
    }

    @Test
    void buildCloudProvisionSpec_emitsEmptyPeersAtProvisionTime() {
        // Cloud peers can't be known at user-data render time (IPs assigned
        // after provisionOne returns). The cloud-init must therefore ship an
        // empty PEERS placeholder; the Dockerfile entrypoint's
        // ${PEERS:+--peers=$PEERS} skips the flag and Main.parsePeers falls
        // through to its config / default chain. The deploy phase is the
        // proper home for the finalised peer list (cascading concern).
        var source = cloudHetznerSource(3);
        var ctx = baseContext(configWithSource(source));

        var ud = invokeBuildCloudProvisionSpec(ctx, source, "eu-1-core-0", 0).userData().or("");

        assertTrue(ud.contains("AETHER_PEERS=\"\""),
                   "Cloud user_data must ship empty AETHER_PEERS (IPs unknown at render time)");
        assertTrue(ud.contains("-e PEERS=\"${AETHER_PEERS}\""),
                   "PEERS env var still exported so the Dockerfile entrypoint can fold it");
    }

    @Test
    void buildCloudProvisionSpec_workerRole_bakesCoreSeedPeersAtCreate() {
        // RFC-0017 stage 4 — cores are provisioned FIRST, so worker user-data rendered afterwards
        // carries the real core addresses: "peer set arrives at birth", no SSH re-launch needed.
        var source = cloudHetznerSource(3);
        var ctx = baseContext(configWithSource(source));
        var coreSeeds = java.util.List.of("eu-1-core-0:203.0.113.10:8090", "eu-1-core-1:203.0.113.11:8090");

        var ud = invokeBuildCloudProvisionSpec(ctx, source, NodeRole.WORKER, "eu-1-worker-0", 3, coreSeeds).userData()
                                                                                                          .or("");

        assertTrue(ud.contains("AETHER_PEERS=\"eu-1-core-0:203.0.113.10:8090,eu-1-core-1:203.0.113.11:8090\""),
                   "Worker user_data must bake the core seed list at create");
    }

    @Test
    void buildCloudProvisionSpec_workerRoleWithOwnImage_specImageIsWorkerImage() {
        // RFC-0016 W2 tier-1 — a WORKER role with its own image yields a spec whose imageId is the
        // worker's image, so the seed VM boots from the worker's snapshot (not the core's, not empty).
        var source = cloudSourceWithPerRoleImages();
        var ctx = baseContext(configWithSource(source));

        var spec = invokeBuildCloudProvisionSpec(ctx, source, NodeRole.WORKER, "eu-1-worker-0", 0);

        assertEquals("worker-snap-99", spec.imageId().or(""),
                     "spec.imageId must be the WORKER role's own image (tier-1 per-role)");
    }

    @Test
    void buildCloudProvisionSpec_coreRoleWithoutImage_specImageAbsent_noSiblingFallback() {
        // RFC-0016 W2 tier-1, no cross-role fallback — the CORE role sets no image, so its spec carries
        // NO imageId even though the worker sibling declares one; resolution falls to tier-2 then the
        // loud default rather than inheriting the worker's snapshot.
        var source = cloudSourceWithPerRoleImages();
        var ctx = baseContext(configWithSource(source));

        var spec = invokeBuildCloudProvisionSpec(ctx, source, NodeRole.CORE, "eu-1-core-0", 0);

        assertTrue(spec.imageId().isEmpty(),
                   "spec.imageId must be absent for a role with no image, never the sibling worker's");
    }

    /// Reflective indirection because BootstrapPhaseProvision keeps these helpers
    /// package-private/private. We exercise them through Java reflection rather
    /// than widening visibility just for tests.
    private static ProvisionSpec invokeBuildCloudProvisionSpec(BootstrapContext ctx,
                                                                SourceProfile source,
                                                                String nodeId,
                                                                int nodeIndex) {
        return invokeBuildCloudProvisionSpec(ctx, source, NodeRole.CORE, nodeId, nodeIndex);
    }

    private static ProvisionSpec invokeBuildCloudProvisionSpec(BootstrapContext ctx,
                                                                SourceProfile source,
                                                                NodeRole role,
                                                                String nodeId,
                                                                int nodeIndex) {
        return invokeBuildCloudProvisionSpec(ctx, source, role, nodeId, nodeIndex, java.util.List.of());
    }

    /// RFC-0017 stage 4: `seedPeers` is EMPTY for cores (they self-assemble via discovery) and the
    /// baked core seed list for workers/spot.
    private static ProvisionSpec invokeBuildCloudProvisionSpec(BootstrapContext ctx,
                                                                SourceProfile source,
                                                                NodeRole role,
                                                                String nodeId,
                                                                int nodeIndex,
                                                                java.util.List<String> seedPeers) {
        try {
            var method = BootstrapPhaseProvision.class.getDeclaredMethod("buildCloudProvisionSpec",
                                                                          BootstrapContext.class,
                                                                          String.class,
                                                                          SourceProfile.class,
                                                                          NodeRole.class,
                                                                          String.class,
                                                                          int.class,
                                                                          String.class,
                                                                          java.util.List.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            var result = (org.pragmatica.lang.Result<ProvisionSpec>) method.invoke(null,
                                                                                    ctx,
                                                                                    "eu-1",
                                                                                    source,
                                                                                    role,
                                                                                    nodeId,
                                                                                    nodeIndex,
                                                                                    "prod",
                                                                                    seedPeers);
            return result.unwrap();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to invoke buildCloudProvisionSpec", e);
        }
    }
}
