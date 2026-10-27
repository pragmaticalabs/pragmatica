// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.pragmatica.aether.config.WorkerConfig;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.aether.node.health.CoreSwimHealthDetector;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.BootTokens;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.rabia.ProtocolConfig;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2429: a core attaches its identity ledger at assembly; a worker holds none and keeps the per-observer registry.
class MembershipAssemblyIdentityLedgerTest {
    private static final NodeId SELF = new NodeId("n1");
    private static final NodeInfo INFO = NodeInfo.nodeInfo(SELF, new NodeAddress("127.0.0.1", 9001));

    @TempDir
    Path directory;

    private BootTokens attach(boolean worker, Path ledger) {
        var config = Mockito.mock(AetherNodeConfig.class);

        when(config.self()).thenReturn(SELF);
        when(config.workerConfig()).thenReturn(worker
                                               ? Option.some(Mockito.mock(WorkerConfig.class))
                                               : Option.<WorkerConfig> none());
        when(config.topology()).thenReturn(new TopologyConfig(SELF, 5, timeSpan(1).seconds(), timeSpan(10).seconds(), List.of(INFO)));
        when(config.protocol()).thenReturn(ProtocolConfig.testConfig(Option.none(), Option.some(ledger)));
        when(config.storageConfig()).thenReturn(Map.of());
        var tokens = BootTokens.bootTokens(1L);
        var detector = Mockito.mock(CoreSwimHealthDetector.class);

        when(detector.bootTokens()).thenReturn(Option.some(tokens));
        MembershipAssembly.attachIdentityLedger(config, detector, OperatorWarningSink.handingOffTo(_ -> {}));

        return tokens;
    }

    @Test
    void attachIdentityLedger_forACore_createsTheLedger() {
        var ledger = directory.resolve("core").resolve("identity").resolve("n1").resolve("ledger");
        var tokens = attach(false, ledger);

        assertThat(Files.isRegularFile(ledger)).as("control: a core gets a ledger").isTrue();
        assertThat(tokens.ledgerDurable()).isTrue();
    }

    @Test
    void attachIdentityLedger_forAWorker_attachesNoLedger() {
        var ledger = directory.resolve("worker").resolve("identity").resolve("n1").resolve("ledger");
        var tokens = attach(true, ledger);

        assertThat(Files.exists(ledger)).as("a worker writes no ledger file").isFalse();
        assertThat(tokens.ledgerDurable()).as("and has no ledger attached").isFalse();
    }
}
