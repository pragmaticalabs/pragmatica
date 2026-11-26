// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.config.BackupConfig;
import org.pragmatica.cluster.node.rabia.NodeConfig;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.ClusterFormationConfig;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.rabia.ProtocolConfig;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.lang.Option;


/// #3525 L2: every core member of a cluster runs the same `[backup]` setting, enforced by the consensus join guard (#3525 L1).
/// The restore gate lives on the node that submits, so a core with `[backup]` off is an ungated submitter during an undecided restore:
/// the guard makes it impossible by refusing a node whose setting differs from a handshaken core peer's.
///
/// This type is the one place the setting becomes a label. The stamp and the name handed to the guard are the same constant
/// ([#LABEL_BACKUP]), so they cannot drift apart.
///
/// Encoding: `[backup]` enabled (see [#enabled]) stamps `backup=enabled`; disabled stamps nothing. The guard treats an absent label as
/// a value that differs from a present one and equal to another absent one, so two nodes with `[backup]` off agree, and a node with
/// `[backup]` off agrees with a node of a version that predates the label. A node with `[backup]` on does not agree with such an old
/// node: accepted, the product is pre-GA and an upgrade is stop-the-world (#2981).
public sealed interface BackupAgreement {
    /// The label key: present (value [#ENABLED]) exactly when this node runs `[backup]`.
    String LABEL_BACKUP = "backup";
    /// The value of [#LABEL_BACKUP] on a node with `[backup]` enabled.
    String ENABLED = "enabled";

    /// `[backup]` enabled with a path: the one condition for the backup service, its restore source, the restore gate on the submit path,
    /// and the label this node advertises.
    static Option<BackupConfig> enabled(Option<BackupConfig> backup) {
        return backup.filter(BackupConfig::enabled)
                     .filter(config -> !config.path()
                                              .isBlank());
    }

    /// The labels every core member must share: what the join guard compares in each handshaken core peer's Hello.
    static Set<String> agreeingLabels() {
        return Set.of(LABEL_BACKUP);
    }

    /// `labels` as this node advertises them: [#LABEL_BACKUP] set to [#ENABLED] when `[backup]` is enabled, absent otherwise.
    static Map<String, String> stamped(Map<String, String> labels, boolean backupEnabled) {
        var stamped = new HashMap<>(labels);

        if (backupEnabled) {
            stamped.put(LABEL_BACKUP, ENABLED);
        } else {
            stamped.remove(LABEL_BACKUP);
        }

        return Map.copyOf(stamped);
    }

    /// `topology` with this node's own entry carrying the stamp: the entry the Hello, the SWIM announce and the guard's own-labels read.
    static TopologyConfig stamped(TopologyConfig topology, Option<BackupConfig> backup) {
        var enabled = enabled(backup).isPresent();

        return new TopologyConfig(topology.self(),
                                  topology.clusterSize(),
                                  topology.reconciliationInterval(),
                                  topology.pingInterval(),
                                  topology.helloTimeout(),
                                  topology.coreNodes()
                                          .stream()
                                          .map(info -> stampedSelf(info,
                                                                   topology.self(),
                                                                   enabled))
                                          .toList(),
                                  topology.tls(),
                                  topology.backoff(),
                                  topology.coreMax(),
                                  topology.coreMin());
    }

    private static NodeInfo stampedSelf(NodeInfo info, NodeId self, boolean enabled) {
        return info.id()
                   .equals(self)
               ? NodeInfo.nodeInfo(info.id(), info.address(), stamped(info.labels(), enabled), info.resolvedAddress())
               : info;
    }

    /// The consensus node configuration of an Aether node: its topology and protocol, and [#agreeingLabels] named to the join guard.
    static NodeConfig nodeConfig(AetherNodeConfig config) {
        record agreeingNodeConfig(ProtocolConfig protocol,
                                  TopologyConfig topology,
                                  boolean activationGated,
                                  ClusterFormationConfig clusterFormation) implements NodeConfig {
            @Override
            public Set<String> agreeingLabels() {
                return BackupAgreement.agreeingLabels();
            }
        }

        return new agreeingNodeConfig(config.protocol(),
                                      config.topology(),
                                      config.activationGated(),
                                      config.clusterFormation());
    }

    record unused() implements BackupAgreement {}
}
