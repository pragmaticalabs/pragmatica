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
import org.pragmatica.aether.environment.SourceName;
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


/// #1543 F2 (ruling 7f2f9d772): a DOCKER source's replacement boots the image its runtime profile pins at the COMMITTED
/// `[cluster] version`, carried to the provider in the spec. The upgrade rewrites that version, so the same pin yields the target
/// image afterwards. Every other source, and a docker role pinning no image, leaves the spec image empty (the provider's own).
class ClusterTopologyManagerDockerImageTest {
    private static java.util.List<org.pragmatica.aether.slice.kvstore.AetherValue.TopologyEntry> coreTopology(int count) {
        return java.util.List.of(new org.pragmatica.aether.slice.kvstore.AetherValue.TopologyEntry("primary", "core", count));
    }

    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId PEER_A = nodeId("node-a").unwrap();
    private static final NodeId PEER_B = nodeId("node-b").unwrap();

    private static final NodeInfo INFO_SELF = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("10.0.0.1", 6000).unwrap());
    private static final NodeInfo INFO_A = NodeInfo.nodeInfo(PEER_A, NodeAddress.nodeAddress("10.0.0.2", 6000).unwrap());
    private static final NodeInfo INFO_B = NodeInfo.nodeInfo(PEER_B, NodeAddress.nodeAddress("10.0.0.3", 6000).unwrap());

    private static final String DOCKER_TOML = """
            config_version = "1.0.0"

            [cluster]
            name = "prod-cluster"
            version = "1.1.0"

            [runtime.app]
            type = "docker"
            image = "registry/aether-node:{version}"

            [source.dock]
            type = "docker"

            [source.dock.core]
            count = 3
            runtime = "app"
            """;

    private static final String DOCKER_NO_PIN_TOML = DOCKER_TOML.replace("image = \"registry/aether-node:{version}\"\n", "");

    private static final String CLOUD_TOML = """
            config_version = "1.0.0"

            [cluster]
            name = "prod-cluster"
            version = "1.1.0"

            [runtime.app]
            type = "container"
            image = "registry/aether-node:{version}"

            [source.eu-1]
            type = "cloud"
            provider = "hetzner"
            credentials = "hcloud-token"
            region = "eu-central"

            [source.eu-1.core]
            count = 3
            runtime = "app"
            """;

    private StubSnapshotSource snapshotSource;
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
        var observer = TopologyObserver.topologyObserver(config, quietRouter(), snapshotSource).unwrap();
        lifecycleManager = new RecordingLifecycleManager();
        clusterStore = new RecordingClusterStore();
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(1).millis(), AutoHealConfig.DEFAULT_PROVISIONING_TIMEOUT).unwrap();
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
    void provisionReplacement_dockerSourceWithVersionedPin_carriesThePinAtTheCommittedVersion() {
        clusterStore.seedToml(DOCKER_TOML);
        ctm.activate();

        replace("dock");

        assertThat(lifecycleManager.lastSpec().imageId().or("<none>")).isEqualTo("registry/aether-node:1.1.0");
    }

    @Test
    void provisionReplacement_dockerSourceWithoutAPin_carriesNoImage_soTheProviderKeepsItsOwn() {
        clusterStore.seedToml(DOCKER_NO_PIN_TOML);
        ctm.activate();

        replace("dock");

        assertThat(lifecycleManager.lastSpec().imageId().isEmpty()).isTrue();
    }

    /// A node of a cluster bootstrapped from static PEERS has no source label, so its replacement is asked for without a source name: the
    /// sole source declaring the role is the answer, and its pin the image. Before, it asked for "default" and was refused.
    @Test
    void replacementWithoutSource_soleDeclaringSource_isDerived_andItsPinnedImageUsed() {
        clusterStore.seedToml(DOCKER_TOML);
        ctm.activate();

        var result = withoutSource();

        assertThat(result.isSuccess()).as(String.valueOf(result)).isTrue();
        assertThat(lifecycleManager.lastSpec().context().sourceName().value()).isEqualTo("dock");
        assertThat(lifecycleManager.lastSpec().imageId().or("<none>")).isEqualTo("registry/aether-node:1.1.0");
    }

    /// Several sources declare the role: a typed refusal naming role and candidates, nothing provisioned, never the source "default".
    @Test
    void replacementWithoutSource_severalDeclaringSources_isRefusedNamingThem_andProvisionsNothing() {
        clusterStore.seedToml(DOCKER_TOML + "\n[source.dock2]\ntype = \"docker\"\n\n[source.dock2.core]\ncount = 3\nruntime = \"app\"\n");
        ctm.activate();

        assertRefusedUnresolved(withoutSource(), "[dock, dock2]");
    }

    /// A cloud source and a docker source both declare the role: no preference between types. Replacing a docker node must not provision a
    /// cloud VM.
    @Test
    void replacementWithoutSource_cloudAndDockerBothDeclareTheRole_isRefused_andNoCloudVmIsProvisioned() {
        clusterStore.seedToml(DOCKER_TOML + """

                [source.eu-1]
                type = "cloud"
                provider = "hetzner"
                credentials = "hcloud-token"
                region = "eu-central"

                [source.eu-1.core]
                count = 3
                runtime = "app"
                """);
        ctm.activate();

        assertRefusedUnresolved(withoutSource(), "[dock, eu-1]");
    }

    @Test
    void replacementWithoutSource_noSourceDeclaresTheRole_isRefusedNamingTheRole() {
        clusterStore.seedToml(DOCKER_TOML.replace("[source.dock.core]", "[source.dock.worker]"));
        ctm.activate();

        assertRefusedUnresolved(withoutSource(), "no source of the committed config declares role core");
    }

    /// No committed config (the bootstrap seed, forge): nothing to derive from, so the replacement proceeds as an auto-heal's does.
    @Test
    void replacementWithoutSource_noCommittedConfig_proceedsAsAnAutoHealDoes() {
        clusterStore.seedBootstrapSeed();
        ctm.activate();

        assertThat(withoutSource().isSuccess()).isTrue();
        assertThat(lifecycleManager.lastSpec()).isNotNull();
    }

    /// The same rule for the retirement and the listing of such a node: the sole declaring source, a typed refusal for several, "default" only
    /// with no committed config.
    @Test
    void sourceOfSourcelessNode_soleSource_isNamed() {
        clusterStore.seedToml(DOCKER_TOML);

        assertThat(ctm.sourceOfSourcelessNode(NodeRole.CORE).map(SourceName::value).or("<refused>")).isEqualTo("dock");
    }

    @Test
    void sourceOfSourcelessNode_severalSources_isTheTypedRefusal() {
        clusterStore.seedToml(DOCKER_TOML + "\n[source.dock2]\ntype = \"docker\"\n\n[source.dock2.core]\ncount = 3\nruntime = \"app\"\n");

        ctm.sourceOfSourcelessNode(NodeRole.CORE)
           .onSuccess(source -> org.junit.jupiter.api.Assertions.fail("must not guess: " + source))
           .onFailure(cause -> assertThat(cause).isInstanceOf(ReplacementSourceUnresolved.class));
    }

    @Test
    void sourceOfSourcelessNode_noCommittedConfig_isDefault() {
        clusterStore.seedBootstrapSeed();

        assertThat(ctm.sourceOfSourcelessNode(NodeRole.CORE).map(SourceName::value).or("<refused>")).isEqualTo("default");
    }

    private org.pragmatica.lang.Result<ProvisionDisposition> withoutSource() {
        return ctm.provisionReplacementWithoutSource(nodeId("node-replacement").unwrap(), Set.of(SELF, PEER_A, PEER_B), NodeRole.CORE).await();
    }

    private void assertRefusedUnresolved(org.pragmatica.lang.Result<ProvisionDisposition> result, String mention) {
        assertThat(result.isFailure()).as(String.valueOf(result)).isTrue();
        result.onFailure(cause -> {
            assertThat(cause).isInstanceOf(ReplacementSourceUnresolved.class);
            assertThat(cause.message()).contains(mention).contains("core").doesNotContain("default");
        });
        assertThat(lifecycleManager.lastSpec()).as("nothing was provisioned").isNull();
    }

    @Test
    void provisionReplacement_cloudSource_neverUsesTheContainerPinAsTheVmImage() {
        clusterStore.seedToml(CLOUD_TOML);
        ctm.activate();

        replace("eu-1");

        assertThat(lifecycleManager.lastSpec().imageId().isEmpty()).as("a cloud spec image is the VM boot image, not the runtime container").isTrue();
    }

    @Test
    void provisionReplacement_bootstrapSeedWithNoToml_carriesNoImage() {
        clusterStore.seedBootstrapSeed();
        ctm.activate();

        replace("dock");

        assertThat(lifecycleManager.lastSpec().imageId().isEmpty()).isTrue();
    }

    private void replace(String source) {
        var result = ctm.provisionReplacement(nodeId("node-replacement").unwrap(),
                                              Option.none(),
                                              Set.of(SELF, PEER_A, PEER_B),
                                              NodeRole.CORE,
                                              SourceName.sourceNameOrDefault(source))
                        .await();

        assertThat(result.isSuccess()).as(String.valueOf(result)).isTrue();
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
            seed(Option.some(toml));
        }

        void seedBootstrapSeed() {
            seed(Option.none());
        }

        private void seed(Option<String> toml) {
            current.set(Option.some(new ClusterConfigValue(toml, "prod-cluster", "1.1.0", coreTopology(5), 3, 9, "docker", 1L, System.currentTimeMillis())));
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

        @Override public boolean isCloudManaged() {
            return true;
        }
    }
}
