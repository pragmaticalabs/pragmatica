// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.environment.ClusterName;

import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.config.cluster.ClusterBootstrapConfig.clusterBootstrapConfig;
import static org.pragmatica.aether.config.cluster.ClusterIdentity.clusterIdentity;
import static org.pragmatica.aether.config.cluster.CoreTopology.defaultCoreTopology;
import static org.pragmatica.aether.config.cluster.InfrastructureConfig.infrastructureConfig;
import static org.pragmatica.aether.config.cluster.OperationsConfig.defaultOperationsConfig;
import static org.pragmatica.lang.Result.success;

/// `BootstrapPhaseDeploy` carries the persisted `startedNodeIds` ledger into the context, and the phase
/// completion then saves the context over the state file. Reading the ledger with the swallowing `load`
/// turned a refused file (torn, or holding an entry this CLI does not know) into "no started nodes", and
/// the save wrote over it. The phase must fail and leave the file untouched.
///
/// Real `~/.aether/clusters/<name>` path with a per-run random name, as in
/// `BootstrapStatePersistenceAppendResourceTest`.
class BootstrapPhaseDeployLedgerRefusalTest {
    private final ClusterName clusterName = uniqueClusterName();

    private static ClusterName uniqueClusterName() {
        var suffix = new byte[6];

        new SecureRandom().nextBytes(suffix);

        return ClusterName.clusterName("deploy-ledger-" + HexFormat.of().formatHex(suffix)).unwrap();
    }

    @AfterEach
    @SuppressWarnings("JBCT-EX-01")
    void cleanUp() throws Exception {
        var dir = BootstrapStatePersistence.AETHER_DIR.resolve(clusterName.value());

        Files.deleteIfExists(dir.resolve(BootstrapStatePersistence.STATE_FILE_NAME));
        Files.deleteIfExists(dir);
    }

    private BootstrapContext context() {
        ClusterBootstrapConfig config = clusterBootstrapConfig(clusterIdentity(clusterName.value(), "1.0.0").unwrap(),
                                                               defaultCoreTopology(),
                                                               Map.of(),
                                                               Map.of(),
                                                               infrastructureConfig(),
                                                               defaultOperationsConfig(),
                                                               Map.of());
        var state = BootstrapState.initialState(clusterName, "h", "2026-10-18T00:00:00Z").withClusterSecret("s");

        return BootstrapContext.bootstrapContext(config, state, List.of(), List.of()).withClusterSecret("s");
    }

    private org.pragmatica.lang.Result<BootstrapContext> deploy() {
        return BootstrapPhaseDeploy.execute(context(), url -> success("OK"), (host, cmd, ssh) -> success("OK"), name -> "");
    }

    @Test
    @SuppressWarnings("JBCT-EX-01")
    void execute_ledgerWithUnknownEntry_failsAndLeavesTheFileByteIdentical() throws Exception {
        var path = BootstrapStatePersistence.statePath(clusterName);
        var ledger = """
            {"clusterName": "%s", "configHash": "h", "startedAt": "2026-10-18T00:00:00Z", "phases": {},
             "createdResources": [{"type": "FutureThing", "resourceId": "x-9"}],
             "provisionedNodeIds": [], "collectedAddresses": [], "clusterSecret": "", "startedNodeIds": ["n-1"]}
            """.formatted(clusterName.value());

        Files.createDirectories(path.getParent());
        Files.writeString(path, ledger);

        deploy().onSuccess(ctx -> fail("a refused ledger must not read as 'no started nodes'"))
                .onFailure(cause -> assertThat(cause.message()).contains("ledger is unreadable").contains("FutureThing"));

        assertThat(Files.readString(path)).isEqualTo(ledger);
    }

    @Test
    @SuppressWarnings("JBCT-EX-01")
    void execute_tornLedger_failsAndLeavesTheBytesIntact() throws Exception {
        var path = BootstrapStatePersistence.statePath(clusterName);

        Files.createDirectories(path.getParent());
        Files.writeString(path, "{ torn");

        deploy().onSuccess(ctx -> fail("a torn ledger must not be read as empty"));

        assertThat(Files.readString(path)).isEqualTo("{ torn");
    }

    @Test
    void execute_absentLedger_succeedsWithNoStartedNodes() {
        deploy().onFailure(cause -> fail("no ledger is the normal first run: " + cause.message()))
                .onSuccess(ctx -> assertThat(ctx.state().startedNodeIds()).isEmpty());
    }

    @Test
    void execute_readableLedger_carriesStartedNodesIntoTheContext() {
        BootstrapStatePersistence.save(BootstrapState.initialState(clusterName, "h", "2026-10-18T00:00:00Z")
                                                     .withStartedNodeId("n-1"))
                                 .onFailure(cause -> fail(cause.message()));

        deploy().onFailure(cause -> fail(cause.message()))
                .onSuccess(ctx -> assertThat(ctx.state().startedNodeIds()).containsExactly("n-1"));
    }
}
