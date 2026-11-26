// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.BackupConfig;
import org.pragmatica.aether.config.BackupConfig.RestoreMode;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.config.TimeoutsConfig;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.deployment.cluster.DeploymentAtomicity;
import org.pragmatica.aether.invoke.ObservabilityConfig;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.ClusterFormationConfig;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.rabia.ProtocolConfig;
import org.pragmatica.consensus.topology.BackoffConfig;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;

import static org.assertj.core.api.Assertions.assertThat;

/// #3525 L2: a node's `[backup]` setting becomes ONE label, stamped into its own topology entry and named to the join guard by the
/// same constant. Disabled stamps nothing, so a node with `[backup]` off agrees with another such node and with a node that predates
/// the label (an absent label equals an absent one).
class BackupAgreementTest {
    private static final NodeId SELF = NodeId.nodeId("node-1").unwrap();
    private static final NodeId PEER = NodeId.nodeId("node-2").unwrap();

    @Test
    void stamped_backupEnabled_theNodeAdvertisesTheLabelWithTheEnabledValue() {
        assertThat(BackupAgreement.stamped(Map.of("role", "core"), true)).isEqualTo(Map.of("role", "core",
                                                                                           BackupAgreement.LABEL_BACKUP,
                                                                                           BackupAgreement.ENABLED));
    }

    @Test
    void stamped_backupDisabled_theNodeAdvertisesNoLabelSoItAgreesWithANodeThatPredatesIt() {
        var preLabelNode = Map.of("role", "core");

        assertThat(BackupAgreement.stamped(preLabelNode, false)).isEqualTo(preLabelNode);
        assertThat(BackupAgreement.stamped(Map.of(BackupAgreement.LABEL_BACKUP, "stale"), false)).isEmpty();
    }

    @Test
    void agreeingLabels_andTheStamp_useTheSameConstant() {
        var config = configWith(Option.some(enabledBackup()));
        var stampedKeys = BackupAgreement.stamped(Map.of(), true).keySet();

        assertThat(BackupAgreement.nodeConfig(config).agreeingLabels()).isEqualTo(Set.of(BackupAgreement.LABEL_BACKUP))
                                                                       .isEqualTo(stampedKeys);
        assertThat(BackupAgreement.agreeingLabels()).isEqualTo(stampedKeys);
    }

    @Test
    void nodeConfig_namesTheAgreeingLabelWhateverTheBackupSetting() {
        assertThat(BackupAgreement.nodeConfig(configWith(Option.none())).agreeingLabels()).containsExactly(BackupAgreement.LABEL_BACKUP);
        assertThat(BackupAgreement.nodeConfig(configWith(Option.some(enabledBackup()))).agreeingLabels())
            .containsExactly(BackupAgreement.LABEL_BACKUP);
    }

    @Test
    void config_backupEnabled_stampsOwnEntryOnly_throughEveryWithCopy() {
        var config = configWith(Option.some(enabledBackup()));

        assertThat(labelsOf(config, SELF)).containsEntry(BackupAgreement.LABEL_BACKUP, BackupAgreement.ENABLED);
        assertThat(labelsOf(config, PEER)).doesNotContainKey(BackupAgreement.LABEL_BACKUP);
        assertThat(BackupAgreement.nodeConfig(config).topology()).isEqualTo(config.topology());
        assertThat(labelsOf(config.withAutoHeal(AutoHealConfig.DEFAULT), SELF)).containsEntry(BackupAgreement.LABEL_BACKUP,
                                                                                           BackupAgreement.ENABLED);
    }

    @Test
    void config_backupAbsentDisabledOrWithoutPath_stampsNothing() {
        assertThat(labelsOf(configWith(Option.none()), SELF)).doesNotContainKey(BackupAgreement.LABEL_BACKUP);
        assertThat(labelsOf(configWith(Option.some(disabledBackup())), SELF)).doesNotContainKey(BackupAgreement.LABEL_BACKUP);
        assertThat(labelsOf(configWith(Option.some(blankPathBackup())), SELF)).doesNotContainKey(BackupAgreement.LABEL_BACKUP);
    }

    @Test
    void enabled_isTheConditionTheNodeUsesForTheRestoreGate() {
        var config = configWith(Option.some(enabledBackup()));

        assertThat(AetherNode.enabledBackup(config).isPresent()).isTrue();
        assertThat(AetherNode.enabledBackup(configWith(Option.none())).isPresent()).isFalse();
        assertThat(AetherNode.enabledBackup(configWith(Option.some(disabledBackup()))).isPresent()).as("present but disabled")
                                                                                              .isFalse();
        assertThat(AetherNode.enabledBackup(configWith(Option.some(blankPathBackup()))).isPresent()).as("enabled with a blank path")
                                                                                                .isFalse();
    }

    @Test
    void stamped_topology_copiesEveryFieldButTheSelfLabelVerbatim() {
        var topology = fullTopology();
        var stamped = BackupAgreement.stamped(topology, Option.some(enabledBackup()));

        assertThat(stamped).usingRecursiveComparison()
                           .ignoringFields("coreNodes")
                           .isEqualTo(topology);
        assertThat(stamped.coreNodes()).containsExactly(NodeInfo.nodeInfo(SELF,
                                                                          node(SELF, 7001).address(),
                                                                          Map.of(BackupAgreement.LABEL_BACKUP,
                                                                                 BackupAgreement.ENABLED)),
                                                        node(PEER, 7002));
        assertThat(BackupAgreement.stamped(topology, Option.none())).isEqualTo(topology);
    }

    @Test
    void nodeConfig_copiesEveryUnstampedFieldVerbatim() {
        var formation = new ClusterFormationConfig(TimeSpan.timeSpan(7).seconds(),
                                                   TimeSpan.timeSpan(8).seconds(),
                                                   TimeSpan.timeSpan(9).seconds());
        var gated = gatedConfigWith(formation);
        var gatedNode = BackupAgreement.nodeConfig(gated);
        var plainNode = BackupAgreement.nodeConfig(configWith(Option.none()));

        assertThat(gatedNode.activationGated()).as("activation gating of a gated config").isTrue();
        assertThat(plainNode.activationGated()).as("activation gating of a default config").isFalse();
        assertThat(gatedNode.clusterFormation()).isEqualTo(formation);
        assertThat(gatedNode.protocol()).isEqualTo(gated.protocol()).isNotEqualTo(ProtocolConfig.defaultConfig());
        assertThat(gatedNode.topology()).isEqualTo(gated.topology());
    }

    private static BackupConfig disabledBackup() {
        return BackupConfig.backupConfig(false, "/data/backup", "", RestoreMode.AUTO);
    }

    private static BackupConfig blankPathBackup() {
        return BackupConfig.backupConfig(true, " ", "", RestoreMode.AUTO);
    }

    private static TopologyConfig fullTopology() {
        return new TopologyConfig(SELF,
                                  3,
                                  TimeSpan.timeSpan(7).seconds(),
                                  TimeSpan.timeSpan(11).seconds(),
                                  TimeSpan.timeSpan(13).seconds(),
                                  List.of(node(SELF, 7001), node(PEER, 7002)),
                                  Option.some(TlsConfig.selfSignedServer()),
                                  new BackoffConfig(7, BackoffConfig.DEFAULT.backoffStrategy()),
                                  5,
                                  2);
    }

    /// A config whose activation gating, protocol and cluster formation differ from the builder defaults.
    private static AetherNodeConfig gatedConfigWith(ClusterFormationConfig formation) {
        return baseBuilder(Option.none()).membership(Option.none())
                                         .streaming(StreamingConfig.streamingConfig())
                                         .protocol(ProtocolConfig.testConfig())
                                         .sliceAction(SliceActionConfig.sliceActionConfig())
                                         .cache(DHTConfig.CACHE_DEFAULT)
                                         .controllerConfig(ControllerConfig.DEFAULT)
                                         .autoHeal(AutoHealConfig.DEFAULT)
                                         .observability(ObservabilityConfig.DEFAULT)
                                         .atomicity(DeploymentAtomicity.ALL_OR_NOTHING)
                                         .activationGated(true)
                                         .timeouts(TimeoutsConfig.timeoutsConfig())
                                         .workerConfig(Option.none())
                                         .deploymentDefaults(AetherNodeConfig.DeploymentDefaults.DEFAULT)
                                         .clusterFormation(formation);
    }

    private static BackupConfig enabledBackup() {
        return BackupConfig.backupConfig(true, "/data/backup", "", RestoreMode.AUTO);
    }

    private static Map<String, String> labelsOf(AetherNodeConfig config, NodeId id) {
        return config.topology()
                     .coreNodes()
                     .stream()
                     .filter(info -> info.id().equals(id))
                     .findFirst()
                     .orElseThrow()
                     .labels();
    }

    private static AetherNodeConfig configWith(Option<BackupConfig> backup) {
        return baseBuilder(backup).build();
    }

    private static AetherNodeConfig.WithMembership baseBuilder(Option<BackupConfig> backup) {
        return AetherNodeConfig.builder()
                               .self(SELF)
                               .coreNodes(List.of(node(SELF, 7001), node(PEER, 7002)))
                               .managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
                               .sliceConfig(SliceConfig.sliceConfig())
                               .artifactRepo(DHTConfig.FULL)
                               .coreMax(1)
                               .appHttp(AppHttpConfig.appHttpConfig())
                               .tls(Option.none())
                               .quicTls(TlsConfig.selfSignedServer())
                               .certificateProvider(Option.none())
                               .configProvider(Option.none())
                               .environment(Option.none())
                               .managementHttpProtocol(HttpProtocol.H1)
                               .storageConfig(Map.of())
                               .backupConfig(backup);
    }

    private static NodeInfo node(NodeId id, int port) {
        return NodeInfo.nodeInfo(id, NodeAddress.nodeAddress("localhost", port).unwrap());
    }
}
