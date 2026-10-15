// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput;
import org.pragmatica.aether.deployment.node.machine.NodeFsmInput;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.TlsConfig;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// W-15 (phase-3 design 8.3, D2): a booted node's recorded bindings equal the checked-in baseline of its engine, `wiring-legacy.txt` or
/// `wiring-fsm2.txt`. The registry is what `DeploymentWiring` recorded while `AetherNode` bound each branch point (B1..B20), so a hook bound to
/// the wrong engine, a branch point nobody bound, or a binding added without its baseline row is red, and each MT-14a..s inversion changes a row.
/// A real self-forming single-node boot (the #858 shape): the bindings are made at construction, whatever the cluster does afterwards.
///
/// Re-record the baselines deliberately, never as part of a normal build, and read the diff before committing it:
/// `mvn test -pl aether/node -Dtest=DeploymentWiringBootTest#recordBaselines -Dwiring.baseline.record=true`.
class DeploymentWiringBootTest {
    private static final Path RESOURCES = Path.of("src", "test", "resources", "fsm2");

    @TempDir
    Path tempDir;

    private AetherNode node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop().await(timeSpan(10).seconds()).onFailure(cause -> {});
        }
        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void legacyNode_recordsTheLegacyBaseline() {
        assertThat(boot(DeploymentEngine.LEGACY)).isEqualTo(baseline("wiring-legacy.txt"));
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void fsm2Node_recordsTheFsm2Baseline() {
        assertThat(boot(DeploymentEngine.FSM2)).isEqualTo(baseline("wiring-fsm2.txt"));
    }

    /// What `AetherNode` hands the FSM2 system, seen from a real boot (W-14, W-33, W-23, B13, B20): the system is started and its seams sample the
    /// node's real membership (the core view, the local liveness and the member sources are wired, not left `none`), and the node's stop closes both
    /// interpreters. (A single node commits nothing through consensus here, so the minted marker is read in the multi-node Level B boot test.)
    @Test
    @Timeout(value = 180, unit = SECONDS)
    void fsm2Node_startsItsSystem_samplesItsRealSeams_andStopClosesBoth() {
        boot(DeploymentEngine.FSM2);
        var system = node.deploymentWiring().system().unwrap();
        var meters = node.deploymentWiring().meters();

        await("seams counter").atMost(Duration.ofSeconds(60)).until(() -> meters.find("aether_deployment_fsm_inputs_total").tags("fsm", "cluster", "input", "SeamsRefreshed").counters().size() > 0);
        var seams = system.seamPoller().sample();

        assertThat(seams.coreView().map(view -> view.contains(node.self())).or(false)).as("the core view is MembershipFsm.coreMembers()").isTrue();
        assertThat(seams.localAlive().map(alive -> alive.contains(node.self())).or(false)).as("the local liveness is wired").isTrue();
        assertThat(seams.memberSource()).as("the member sources are the membership's own").containsKey(node.self());

        node.stop().await(timeSpan(30).seconds());

        assertThat(system.nodeInterpreter().deliver(new NodeFsmInput.QuorumDisappeared())).as("the node's stop closed the node FSM").isFalse();
        assertThat(system.clusterInterpreter().deliver(new ClusterInput.Flush())).as("and the cluster FSM").isFalse();
    }

    /// The two engines never share a binding target for a branch point: every FSM2 row for a legacy hook is a counted drop, and no LEGACY row
    /// names the FSM2 system.
    @Test
    @Timeout(value = 120, unit = SECONDS)
    void noEngineBindsTheOthersTarget() {
        var legacy = boot(DeploymentEngine.LEGACY);

        tearDown();
        var fsm2 = boot(DeploymentEngine.FSM2);

        assertThat(legacy).noneMatch(row -> row.contains("fsm2:") || row.endsWith("-> dropped"));
        assertThat(fsm2).noneMatch(row -> row.endsWith("-> legacy") || row.endsWith("-> legacy+gate"));
        assertThat(fsm2).anyMatch(row -> row.startsWith("B15.construction"));
    }

    @Test
    @EnabledIfSystemProperty(named = "wiring.baseline.record", matches = "true")
    @Timeout(value = 240, unit = SECONDS)
    void recordBaselines() throws IOException {
        write("wiring-legacy.txt", boot(DeploymentEngine.LEGACY));
        tearDown();
        write("wiring-fsm2.txt", boot(DeploymentEngine.FSM2));
    }

    private List<String> boot(DeploymentEngine engine) {
        node = AetherNode.aetherNode(minimalConfig(tempDir).withDeploymentEngine(engine), () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();
        node.start().await(timeSpan(30).seconds()).onFailure(cause -> fail("start() must succeed: " + cause.message()));

        return node.deploymentWiring().recorded();
    }

    private static List<String> baseline(String name) {
        try {
            return Files.readAllLines(RESOURCES.resolve(name), StandardCharsets.UTF_8).stream().filter(line -> !line.startsWith("#") && !line.isBlank()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(String name, List<String> rows) throws IOException {
        Files.createDirectories(RESOURCES);
        Files.writeString(RESOURCES.resolve(name),
                          "# Deployment wiring baseline (phase-3 design 8.3, W-15). GENERATED by DeploymentWiringBootTest#recordBaselines; read the diff before committing.\n"
                          + "# One line per branch point: <point> -> <target>. A changed line is a hook bound to a different engine.\n"
                          + String.join("\n", rows)
                          + "\n",
                          StandardCharsets.UTF_8);
    }

    private static AetherNodeConfig minimalConfig(Path storageRoot) {
        var self = NodeId.nodeId("wiring-boot-" + UUID.randomUUID()).unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", ClusterTestPorts.freeClusterPort()).unwrap());

        return AetherNodeConfig.builder()
                               .self(self)
                               .coreNodes(List.of(selfInfo))
                               .managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
                               .sliceConfig(SliceConfig.sliceConfig())
                               .artifactRepo(DHTConfig.FULL)
                               .coreMax(1)
                               .appHttp(AppHttpConfig.appHttpConfig())
                               .tls(Option.none())
                               .quicTls(TlsConfig.selfSignedMutual())
                               .certificateProvider(Option.none())
                               .configProvider(Option.none())
                               .environment(Option.none())
                               .managementHttpProtocol(HttpProtocol.H1)
                               .storageConfig(HermeticStorage.nodeStorageIn(storageRoot, false))
                               .build();
    }
}
