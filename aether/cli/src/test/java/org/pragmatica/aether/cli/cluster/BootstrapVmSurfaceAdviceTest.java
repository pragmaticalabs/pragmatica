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
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.environment.ClusterName.clusterName;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;

/// #1030 — a failed bootstrap must not point at VM logs it has just deleted. The advice depends on what
/// happens to the VMs: kept (`--keep-on-failure`) names the logs and where they are; removed names the flag
/// that keeps them on the next run. Both arms, both failure sites that point at a VM surface.
class BootstrapVmSurfaceAdviceTest {
    private static final ClusterName CLUSTER = clusterName("vm-advice-test").unwrap();
    private static final String FORMATION_LOGS = "the cloud-init logs";
    private static final String HEALTH_LOGS = "/var/log/cloud-init-output.log";

    private Function<BootstrapState, Result<Unit>> originalHook;

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
                           .doesNotContain("being removed");
    }

    @Test
    void formationTimeout_removedVms_saysRemovedAndNamesTheFlag() {
        var message = decorated(formationFailure(), false);

        assertThat(message).contains("being removed", "--keep-on-failure")
                           .doesNotContain("ssh aether@", "were kept");
    }

    @Test
    void healthGate_keptVms_namesLogsAndWhereTheyAre() {
        var message = decorated(healthFailure(), true);

        assertThat(message).contains(HEALTH_LOGS, "ssh aether@<vm-ip>", "were kept")
                           .doesNotContain("being removed");
    }

    @Test
    void healthGate_removedVms_saysRemovedAndNamesTheFlag() {
        var message = decorated(healthFailure(), false);

        assertThat(message).contains("being removed", "--keep-on-failure")
                           .doesNotContain("ssh aether@", "were kept");
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
