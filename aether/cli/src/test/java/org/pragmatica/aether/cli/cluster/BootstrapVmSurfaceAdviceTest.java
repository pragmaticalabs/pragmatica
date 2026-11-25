// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapError;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.environment.ClusterName.clusterName;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;

/// #1030 — a failed bootstrap must not point at VM logs it has just deleted, nor claim deletion that did not
/// happen. The advice states the OUTCOME: kept (`--keep-on-failure`) names the logs and where they are;
/// removed (cleanup succeeded) says they did not survive and names the flag; cleanup failed says VMs may
/// still be running and names the logs, without "removed". Three arms, both failure sites that point at a VM
/// surface.
class BootstrapVmSurfaceAdviceTest {
    private static final ClusterName CLUSTER = clusterName("vm-advice-test").unwrap();
    private static final String FORMATION_LOGS = "the cloud-init logs";
    private static final String HEALTH_LOGS = "/var/log/cloud-init-output.log";
    private static final Cause CLEANUP_FAILURE = Causes.cause("hcloud server 4242 could not be deleted");

    private Function<BootstrapState, Result<Unit>> originalHook;
    private final List<String> hookCalls = new ArrayList<>();

    @BeforeEach
    void setUp() {
        originalHook = ClusterBootstrapOrchestrator.cleanupHook();
        var _ = BootstrapStatePersistence.delete(CLUSTER);
    }

    @AfterEach
    void tearDown() {
        ClusterBootstrapOrchestrator.setCleanupHook(originalHook);
        var _ = BootstrapStatePersistence.delete(CLUSTER);
    }

    @Test
    void formationTimeout_keptVms_namesLogsAndWhereTheyAre() {
        var message = decorated(formationFailure(), true);

        assertThat(message).contains(FORMATION_LOGS, "ssh aether@<vm-ip>", "were kept")
                           .doesNotContain("were removed");
    }

    @Test
    void formationTimeout_removedVms_saysRemovedAfterTheCleanupRan() {
        recordResourceAndCleanup(Result.unitResult());

        var message = decorated(formationFailure(), false);

        assertThat(hookCalls).as("the cleanup ran before the text was chosen").hasSize(1);
        assertThat(message).contains("were removed", "did not survive", "--keep-on-failure")
                           .doesNotContain("ssh aether@", "were kept", "cleanup failed");
    }

    @Test
    void formationTimeout_cleanupFailed_doesNotClaimRemovalAndNamesTheLogs() {
        recordResourceAndCleanup(CLEANUP_FAILURE.result());

        var message = decorated(formationFailure(), false);

        assertThat(hookCalls).as("the cleanup ran and failed").hasSize(1);
        assertThat(message).contains("cleanup failed, so VMs may still be running", FORMATION_LOGS, "ssh aether@<vm-ip>")
                           .contains("resources were left behind", "hcloud server 4242")
                           .doesNotContain("were removed", "did not survive", "re-run with", "were kept");
    }

    @Test
    void healthGate_keptVms_namesLogsAndWhereTheyAre() {
        var message = decorated(healthFailure(), true);

        assertThat(message).contains(HEALTH_LOGS, "ssh aether@<vm-ip>", "were kept")
                           .doesNotContain("were removed");
    }

    @Test
    void healthGate_removedVms_saysRemovedAfterTheCleanupRan() {
        recordResourceAndCleanup(Result.unitResult());

        var message = decorated(healthFailure(), false);

        assertThat(hookCalls).as("the cleanup ran before the text was chosen").hasSize(1);
        assertThat(message).contains("were removed", "did not survive", "--keep-on-failure")
                           .doesNotContain("ssh aether@", "were kept", "cleanup failed");
    }

    @Test
    void healthGate_cleanupFailed_doesNotClaimRemovalAndNamesTheLogs() {
        recordResourceAndCleanup(CLEANUP_FAILURE.result());

        var message = decorated(healthFailure(), false);

        assertThat(hookCalls).as("the cleanup ran and failed").hasSize(1);
        assertThat(message).contains("cleanup failed, so VMs may still be running", HEALTH_LOGS, "ssh aether@<vm-ip>")
                           .contains("resources were left behind", "hcloud server 4242")
                           .doesNotContain("were removed", "did not survive", "re-run with", "were kept");
    }

    @Test
    void unreadableLedger_doesNotClaimRemovalAndKeepsTheOrphanText() throws IOException {
        recordResourceAndCleanup(Result.unitResult());
        Files.writeString(BootstrapStatePersistence.statePath(CLUSTER), "{ torn");

        var message = decorated(formationFailure(), false);

        assertThat(hookCalls).as("an unreadable ledger never reaches the cleanup").isEmpty();
        assertThat(message).contains("cleanup failed, so VMs may still be running", FORMATION_LOGS, "could not be read")
                           .contains("resources were left behind")
                           .doesNotContain("were removed", "did not survive", "re-run with");
    }

    @Test
    void phaseText_doesNotPointAtLogsBeforeTheFlagIsKnown() {
        assertThat(formationFailure().message()).doesNotContain("cloud-init", "--keep-on-failure");
        assertThat(healthFailure().message()).doesNotContain("cloud-init", "--keep-on-failure");
    }

    @Test
    void otherFailures_passThroughUnchanged() {
        Cause plain = new BootstrapError.DeploymentFailed("n", "plain");

        assertThat(decorated(plain, true)).isEqualTo(plain.message());
        assertThat(decorated(plain, false)).isEqualTo(plain.message());
    }

    private void recordResourceAndCleanup(Result<Unit> outcome) {
        var state = BootstrapState.initialState(CLUSTER, "hash", "2026-11-25T00:00:00Z")
                                  .withResource(CreatedResource.ProvisionedVm.provisionedVm("hetzner", "4242", "src", "core"));

        assertThat(BootstrapStatePersistence.save(state).isSuccess()).isTrue();
        ClusterBootstrapOrchestrator.setCleanupHook(persisted -> {
            hookCalls.add(persisted.clusterName().value());
            return outcome;
        });
    }

    private static String decorated(Cause cause, boolean keepOnFailure) {
        return ClusterBootstrapOrchestrator.decorateAfterCleanup(CLUSTER, cause, keepOnFailure).message();
    }

    private static Cause formationFailure() {
        return BootstrapPhaseDeploy.pollFormedLabels(_ -> Result.success(1),
                                                     3,
                                                     CLUSTER,
                                                     sourceNameOrDefault("eu"),
                                                     60,
                                                     10)
                                   .fold(cause -> cause, _ -> new BootstrapError.DeploymentFailed("n", "unexpected success"));
    }

    private static Cause healthFailure() {
        return new BootstrapError.VmSurfaceFailure(new BootstrapError.DeploymentFailed("eu",
                                                                                     "1 node(s) never answered the management API on port 8080."),
                                                   HEALTH_LOGS);
    }
}
