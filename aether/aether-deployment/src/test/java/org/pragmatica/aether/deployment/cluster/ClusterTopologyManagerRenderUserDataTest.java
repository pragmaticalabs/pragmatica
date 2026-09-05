// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.NodeUserDataRenderer;
import org.pragmatica.aether.config.cluster.ReplacementNodeConfigComposer;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkServiceMessage;
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

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.environment.ClusterName.clusterName;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;


/// rc2 cloud auto-heal regression: the CTM cloud-provision path must render REPLACEMENT-SPECIFIC
/// cloud-init user-data and attach it to the `ProvisionSpec`, so a Hetzner replacement boots with
/// the NEW node-id and the LIVE peers — not the dead seed PEERS baked into the static
/// `[cloud...] user_data` blob the provider would otherwise fall back to (the "replacement never
/// joins, cluster stuck 5→4→3" defect). The user-data is produced by the SAME
/// [NodeUserDataRenderer] the CLI bootstrap path uses, so the two scripts cannot drift.
class ClusterTopologyManagerRenderUserDataTest {
    /// RFC-0017 C1 — desired topology replaced the core-only scalar; tests that only care about a
    /// core count build a single-source entry.
    private static java.util.List<org.pragmatica.aether.slice.kvstore.AetherValue.TopologyEntry> coreTopology(int count) {
        return java.util.List.of(new org.pragmatica.aether.slice.kvstore.AetherValue.TopologyEntry("primary", "core", count));
    }

    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId PEER_A = nodeId("node-a").unwrap();
    private static final NodeId PEER_B = nodeId("node-b").unwrap();
    private static final NodeId DEAD_PEER = nodeId("node-dead").unwrap();

    private static final NodeInfo INFO_SELF = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("10.0.0.1", 6000).unwrap());
    private static final NodeInfo INFO_A = NodeInfo.nodeInfo(PEER_A, NodeAddress.nodeAddress("10.0.0.2", 6000).unwrap());
    private static final NodeInfo INFO_B = NodeInfo.nodeInfo(PEER_B, NodeAddress.nodeAddress("10.0.0.3", 6000).unwrap());

    private static final String CLOUD_TOML = """
            config_version = "1.0.0"

            [cluster]
            name = "prod-cluster"
            version = "1.0.0"

            [operations.ports]
            cluster = 6000
            management = 5160
            app_http = 8070

            [source.eu-1]
            type = "cloud"
            provider = "hetzner"
            region = "eu-central"

            [source.eu-1.core]
            count = 3
            """;

    private static final String CLOUD_TOML_WITH_AUTHORIZED_KEYS = """
            config_version = "1.0.0"

            [cluster]
            name = "prod-cluster"
            version = "1.0.0"

            [operations.ports]
            cluster = 6000
            management = 5160
            app_http = 8070

            [source.eu-1]
            type = "cloud"
            provider = "hetzner"
            region = "eu-central"

            [source.eu-1.core]
            count = 3

            [infrastructure.ssh]
            authorized_keys = ["ssh-ed25519 AAAAC3OperatorKey operator@laptop"]
            """;

    private StubSnapshotSource snapshotSource;
    private TopologyObserver observer;
    private RecordingLifecycleManager lifecycleManager;
    private RecordingClusterStore clusterStore;
    private ClusterTopologyManager ctm;

    @BeforeEach
    void setUp() {
        snapshotSource = new StubSnapshotSource();
        var config = new TopologyConfig(SELF,
                                        5,
                                        timeSpan(60).seconds(),
                                        timeSpan(1).seconds(),
                                        List.of(INFO_SELF, INFO_A, INFO_B));
        observer = TopologyObserver.topologyObserver(config, quietRouter(), snapshotSource).unwrap();
        lifecycleManager = new RecordingLifecycleManager();
        clusterStore = new RecordingClusterStore();
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(60).seconds(),
                                                     timeSpan(1).millis(),
                                                     AutoHealConfig.DEFAULT_STALE_OBSERVATION_TTL,
                                                     AutoHealConfig.DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                                                     AutoHealConfig.DEFAULT_PROVISIONING_TIMEOUT,
                                                     timeSpan(0).millis())
                                     .unwrap();
        ctm = ClusterTopologyManager.clusterTopologyManager(observer,
                                                            lifecycleManager,
                                                            autoHeal,
                                                            DeploymentMap.deploymentMap(),
                                                            snapshotSource,
                                                            clusterStore::current,
                                                            clusterStore::apply,
                                                            () -> ClusterPhase.NORMAL);
    }

    @Test
    void provisionReplacement_cloudConfig_attachesUserDataWithNewNodeIdAndLivePeers() {
        clusterStore.seedToml(CLOUD_TOML);
        ctm.activate();
        var newNodeId = nodeId("node-replacement").unwrap();

        var result = ctm.provisionReplacement(newNodeId, Option.some(DEAD_PEER), Set.of(SELF, PEER_A, PEER_B), NodeRole.CORE).await();

        assertThat(result.isSuccess()).isTrue();
        var userData = lifecycleManager.lastSpec().userData();
        assertThat(userData.isPresent())
                .as("CTM cloud-provision attaches rendered replacement user-data to the spec")
                .isTrue();
        var script = userData.or("");
        assertThat(script)
                .as("user-data boots the new replacement under its leader-minted node-id")
                .contains("AETHER_NODE_ID=\"node-replacement\"");
        assertThat(script)
                .as("user-data carries the cluster name from the persisted config")
                .contains("name = \"prod-cluster\"");
        assertThat(script)
                .as("user-data seeds PEERS from the LIVE member set (self always present)")
                .contains("node-self:10.0.0.1:6000");
        assertThat(script)
                .as("user-data must NOT point the replacement at the dead departed peer")
                .doesNotContain("node-dead");
    }

    /// #442 — a replacement can later be elected leader and provision ITS OWN replacements. For that
    /// to resolve keys from config (no API lookup), the replacement's composed `aether.toml` must
    /// carry the leader's ssh_key_ids. The CTM reads them from the leader's own resolved
    /// `[cloud.compute] ssh_key_ids` (`resolvedLocalConfig`) and threads them through
    /// [ReplacementNodeConfigComposer] into the replacement's cloud-init config — extending the
    /// bootstrap-seeded inheritance across replacement generations without persisting the ids.
    @Test
    void provisionReplacement_threadsLeaderSshKeyIdsIntoReplacementConfig() {
        clusterStore.seedToml(CLOUD_TOML);
        var leaderResolved = TomlDocument.EMPTY.with("cloud.compute", "ssh_key_ids", "113681412");
        var ctmWithKeys = ClusterTopologyManager.clusterTopologyManager(observer,
                                                                        lifecycleManager,
                                                                        renderTestAutoHeal(),
                                                                        DeploymentMap.deploymentMap(),
                                                                        snapshotSource,
                                                                        clusterStore::current,
                                                                        clusterStore::apply,
                                                                        () -> ClusterPhase.NORMAL,
                                                                        ignored -> {},
                                                                        ignored -> {},
                                                                        () -> Option.some(leaderResolved),
                                                                        Option::none);
        ctmWithKeys.activate();

        var result = ctmWithKeys.provisionReplacement(nodeId("node-r3").unwrap(),
                                                      Option.none(),
                                                      Set.of(SELF, PEER_A, PEER_B),
                                                      NodeRole.CORE).await();

        assertThat(result.isSuccess()).isTrue();
        var script = lifecycleManager.lastSpec().userData().or("");
        assertThat(script)
                .as("replacement's composed aether.toml inherits the leader's ssh_key_ids so it "
                    + "provisions from config when it becomes leader")
                .contains("ssh_key_ids = \"113681412\"");
    }

    private static AutoHealConfig renderTestAutoHeal() {
        return AutoHealConfig.autoHealConfig(timeSpan(60).seconds(),
                                             timeSpan(1).millis(),
                                             AutoHealConfig.DEFAULT_STALE_OBSERVATION_TTL,
                                             AutoHealConfig.DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                                             AutoHealConfig.DEFAULT_PROVISIONING_TIMEOUT,
                                             timeSpan(0).millis())
                             .unwrap();
    }

    /// The cluster secret and dev-mode posture are sourced from the running (leader) node's env,
    /// exactly as the renderer's `emitIdentityEnv` does. `System.getenv` cannot be mutated
    /// in-process, so this branches on the ambient env (mirrors the CLI `UserDataTemplate` test).
    @Test
    void provisionReplacement_cloudConfig_emitsIdentityEnvFromHostEnv() {
        clusterStore.seedToml(CLOUD_TOML);
        ctm.activate();

        var result = ctm.provisionReplacement(nodeId("node-r2").unwrap(), Option.none(), Set.of(SELF, PEER_A, PEER_B), NodeRole.CORE).await();

        assertThat(result.isSuccess()).isTrue();
        var script = lifecycleManager.lastSpec().userData().or("");
        var devMode = System.getenv("AETHER_INSECURE_DEV_MODE");
        if (devMode != null && !devMode.isEmpty()) {
            assertThat(script)
                    .as("dev-mode posture is inherited from the leader env when set")
                    .contains("AETHER_INSECURE_DEV_MODE=\"" + devMode + "\"");
        } else {
            assertThat(script)
                    .as("dev-mode line is absent when the leader env does not export it")
                    .doesNotContain("AETHER_INSECURE_DEV_MODE");
        }
        var secret = System.getenv("AETHER_CLUSTER_SECRET");
        if (secret != null && !secret.isBlank()) {
            assertThat(script)
                    .as("cluster secret is baked from the leader env when present")
                    .contains("AETHER_CLUSTER_SECRET=\"" + secret + "\"");
        }
    }

    @Test
    void provisionReplacement_cloudConfig_userDataMatchesSharedRendererOutput() {
        clusterStore.seedToml(CLOUD_TOML);
        ctm.activate();
        var newNodeId = nodeId("node-r3").unwrap();

        var result = ctm.provisionReplacement(newNodeId, Option.none(), Set.of(SELF, PEER_A, PEER_B), NodeRole.CORE).await();
        assertThat(result.isSuccess()).isTrue();

        var config = ClusterBootstrapConfigParser.parse(CLOUD_TOML).unwrap();
        var source = config.sources().get("eu-1");
        var secret = Option.option(System.getenv("AETHER_CLUSTER_SECRET")).filter(s -> !s.isBlank());
        var composed = ReplacementNodeConfigComposer.compose(config, source, secret).unwrap();
        var peers = lifecycleManager.lastSpec().context().peers().or("");
        var expected = NodeUserDataRenderer.render(config,
                                                   source,
                                                   NodeRole.CORE,
                                                   "node-r3",
                                                   0,
                                                   secret.or(""),
                                                   clusterName("prod-cluster").unwrap(),
                                                   composed,
                                                   List.of(),
                                                   List.of(peers.split(",")));

        assertThat(lifecycleManager.lastSpec().userData().or(""))
                .as("CTM uses the SAME shared NodeUserDataRenderer as bootstrap (no drift)")
                .isEqualTo(expected);
    }

    @Test
    void provisionReplacement_blankToml_attachesNoUserData_provingProviderFallbackPreserved() {
        clusterStore.seedToml("");
        ctm.activate();

        var result = ctm.provisionReplacement(nodeId("node-r4").unwrap(), Option.none(), Set.of(SELF, PEER_A, PEER_B), NodeRole.CORE).await();

        assertThat(result.isSuccess()).isTrue();
        assertThat(lifecycleManager.lastSpec().userData().isEmpty())
                .as("a blank persisted config degrades to no user-data so the provider fallback applies")
                .isTrue();
    }

    @Test
    void provisionReplacement_cloudConfig_injectsOperatorAuthorizedKeysFromPersistedToml() {
        // rc2 cloud auto-heal regression: a CTM replacement VM must accept the SAME operator SSH
        // key a bootstrap-minted node does. Bootstrap persists the resolved key CONTENTS into the
        // cluster config TOML ([infrastructure.ssh].authorized_keys); CTM reads them back and threads
        // them into the SHARED renderer's cloud-init authorized_keys block.
        clusterStore.seedToml(CLOUD_TOML_WITH_AUTHORIZED_KEYS);
        ctm.activate();

        var result = ctm.provisionReplacement(nodeId("node-keyed").unwrap(), Option.none(), Set.of(SELF, PEER_A, PEER_B), NodeRole.CORE).await();

        assertThat(result.isSuccess()).isTrue();
        var script = lifecycleManager.lastSpec().userData().or("");
        assertThat(script)
                .as("replacement user-data installs the operator key into root's authorized_keys")
                .contains("cat >> /root/.ssh/authorized_keys");
        assertThat(script)
                .as("replacement user-data carries the operator public key persisted in the config")
                .contains("ssh-ed25519 AAAAC3OperatorKey operator@laptop");
    }

    @Test
    void provisionReplacement_cloudConfigWithoutKeys_omitsAuthorizedKeysBlock() {
        // No [infrastructure.ssh].authorized_keys persisted -> no SSH block, exactly as a keyless
        // bootstrap renders. Guards against an unconditional surface beyond what bootstrap does.
        clusterStore.seedToml(CLOUD_TOML);
        ctm.activate();

        var result = ctm.provisionReplacement(nodeId("node-keyless").unwrap(), Option.none(), Set.of(SELF, PEER_A, PEER_B), NodeRole.CORE).await();

        assertThat(result.isSuccess()).isTrue();
        assertThat(lifecycleManager.lastSpec().userData().or(""))
                .as("a config without persisted authorized_keys renders no SSH authorized_keys block")
                .doesNotContain("cat >> /root/.ssh/authorized_keys");
    }

    @Test
    void provisionReplacement_cloudConfig_resolvesRoutableAdvertiseHostOnTheVm() {
        // The defect this whole path exists to close: a replacement that advertises its
        // unresolvable VM hostname gets suspected and SWIM-killed. The rendered user-data must
        // resolve the VM's ROUTABLE IP on the host (provider-agnostic, never failing the boot) and
        // pass it through as AETHER_ADVERTISE_HOST ONLY when non-empty, for the container runtime.
        clusterStore.seedToml(CLOUD_TOML);
        ctm.activate();

        var result = ctm.provisionReplacement(nodeId("node-r5").unwrap(), Option.some(DEAD_PEER), Set.of(SELF, PEER_A, PEER_B), NodeRole.CORE).await();

        assertThat(result.isSuccess()).isTrue();
        var script = lifecycleManager.lastSpec().userData().or("");
        assertThat(script)
                .as("replacement user-data resolves the VM's routable IPv4 on the host without failing the boot")
                .contains("AETHER_ADVERTISE_HOST=\"$(ip route get 1.1.1.1 2>/dev/null | sed -n 's/.*src \\([0-9.]*\\).*/\\1/p' | head -n1)\" || true");
        assertThat(script)
                .as("replacement user-data passes the routable IP into the container only when resolved")
                .contains("if [ -n \"${AETHER_ADVERTISE_HOST}\" ]; then ADVERTISE_ENV=\"-e AETHER_ADVERTISE_HOST=${AETHER_ADVERTISE_HOST}\"; fi");
    }

    private static MessageRouter.MutableRouter quietRouter() {
        var router = MessageRouter.mutable();
        router.addRoute(NetworkServiceMessage.ListConnectedNodes.class, _ -> {});
        return router;
    }

    private static final class StubSnapshotSource implements GenerationSnapshotSource {
        private final AtomicReference<Option<MembershipView>> view = new AtomicReference<>(Option.none());

        @Override public Option<MembershipView> currentMembershipView() {
            return view.get();
        }

        @Override public long observedRabiaTerm() {
            return 0L;
        }
    }

    private static final class RecordingClusterStore {
        private final AtomicReference<Option<ClusterConfigValue>> current = new AtomicReference<>(Option.none());

        void seedToml(String toml) {
            current.set(Option.some(new ClusterConfigValue(toml, "prod-cluster", "1.0.0", coreTopology(5), 3, 9, "cloud", 1L, System.currentTimeMillis())));
        }

        Option<ClusterConfigValue> current() {
            return current.get();
        }

        Promise<List<Object>> apply(List<KVCommand<AetherKey>> commands) {
            return Promise.success(List.of());
        }
    }

    private static final class RecordingLifecycleManager implements NodeLifecycleManager {
        private final AtomicReference<ProvisionSpec> lastSpec = new AtomicReference<>();

        ProvisionSpec lastSpec() {
            return lastSpec.get();
        }

        @Override public Promise<ActionResult> executeAction(NodeAction action) {
            return Promise.success(new ActionResult.NodeStarted(InstanceInfo.instanceInfo(InstanceId.instanceId("stub").unwrap(),
                                                                                          InstanceStatus.RUNNING,
                                                                                          List.of("127.0.0.1"),
                                                                                          InstanceType.ON_DEMAND).unwrap()));
        }

        @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
            lastSpec.set(spec);
            return Promise.success(InstanceInfo.instanceInfo(InstanceId.instanceId("stub").unwrap(),
                                                             InstanceStatus.RUNNING,
                                                             List.of("127.0.0.1"),
                                                             InstanceType.ON_DEMAND).unwrap());
        }

        @Override public Promise<Unit> terminateNode(NodeId nodeId) {
            return Promise.success(Unit.unit());
        }

        @Override public Promise<Unit> restartNode(NodeId nodeId) {
            return Promise.success(Unit.unit());
        }

        @Override public boolean isCloudManaged() {
            return true;
        }
    }
}
