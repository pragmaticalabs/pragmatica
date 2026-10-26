// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.aether.node.ClusterTestPorts;
import org.pragmatica.aether.node.HermeticStorage;
import org.pragmatica.aether.node.StorageFactory.StorageSetup;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.storage.DegradeReporter;
import org.pragmatica.storage.DemotionManager;
import org.pragmatica.storage.PrefetchManager;
import org.pragmatica.storage.PromotionManager;
import org.pragmatica.storage.StorageGarbageCollector;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// A0b: the leader-pinned storage maintenance group has no instance reporter of its own; the assembly builds one and binds it to the
/// node's operator-warning sink. The adapter's own test hands its reporter a sink directly, so this reads the assembly's output:
/// a leader activation that fails reaches the sink it was assembled with. Red with the bind removed from `storageMaintenance`.
class StorageAssemblyOperatorWarningTest {
    @TempDir
    Path tempDir;

    @Test
    void leaderMaintenance_whenItsActivationFails_reachesTheSinkTheAssemblyWasGiven() throws Exception {
        var raised = new CopyOnWriteArrayList<OperatorWarning>();
        var sink = OperatorWarningSink.handingOffTo(raised::add);
        var demotion = mock(DemotionManager.class);
        var collector = mock(StorageGarbageCollector.class);

        when(demotion.activate()).thenReturn(Result.failure(Causes.cause("no demotion")));
        when(collector.activate()).thenReturn(Result.success(org.pragmatica.lang.Unit.unit()));
        var streams = new StorageSetup("streams", null, null, null, null, demotion, collector, Option.none(), Option.none(),
                                       PromotionManager.NOOP, PrefetchManager.NOOP, DegradeReporter.degradeReporter("streams"));

        var outputs = StorageAssembly.storageMaintenance(new StorageAssembly.StorageMaintenanceInputs(config(), Map.of("streams", streams), sink));
        outputs.delegatedStorageAdapter().activate();
        awaitOne(raised);

        assertThat(raised).hasSize(1);
        assertThat(raised.getFirst().code()).isEqualTo(OperatorWarningCode.STORAGE_MAINTENANCE_NOT_STARTED);
        assertThat(raised.getFirst().subject()).isEqualTo("leader-maintenance");
        assertThat(raised.getFirst().message()).contains("no demotion");
    }

    private static void awaitOne(List<OperatorWarning> raised) throws InterruptedException {
        var deadline = System.nanoTime() + 5_000_000_000L;

        while (raised.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    private AetherNodeConfig config() {
        var self = NodeId.nodeId("storage-assembly").unwrap();
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
                               .storageConfig(HermeticStorage.nodeStorageIn(tempDir, false))
                               .build();
    }
}
