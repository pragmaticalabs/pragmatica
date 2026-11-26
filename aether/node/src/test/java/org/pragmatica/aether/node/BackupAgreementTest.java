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
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
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
        var disabled = BackupConfig.backupConfig(false, "/data/backup", "", RestoreMode.AUTO);
        var blankPath = BackupConfig.backupConfig(true, " ", "", RestoreMode.AUTO);

        assertThat(labelsOf(configWith(Option.none()), SELF)).doesNotContainKey(BackupAgreement.LABEL_BACKUP);
        assertThat(labelsOf(configWith(Option.some(disabled)), SELF)).doesNotContainKey(BackupAgreement.LABEL_BACKUP);
        assertThat(labelsOf(configWith(Option.some(blankPath)), SELF)).doesNotContainKey(BackupAgreement.LABEL_BACKUP);
    }

    @Test
    void enabled_isTheConditionTheNodeUsesForTheRestoreGate() {
        var config = configWith(Option.some(enabledBackup()));

        assertThat(AetherNode.enabledBackup(config).isPresent()).isTrue();
        assertThat(AetherNode.enabledBackup(configWith(Option.none())).isPresent()).isFalse();
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
                               .backupConfig(backup)
                               .build();
    }

    private static NodeInfo node(NodeId id, int port) {
        return NodeInfo.nodeInfo(id, NodeAddress.nodeAddress("localhost", port).unwrap());
    }
}
