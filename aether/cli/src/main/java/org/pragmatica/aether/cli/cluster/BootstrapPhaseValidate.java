// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigValidator;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;

import java.time.Instant;
import java.util.List;

import static org.pragmatica.aether.cli.cluster.BootstrapPhase.VALIDATE;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
sealed interface BootstrapPhaseValidate {
    record unused() implements BootstrapPhaseValidate {}

    static Result<BootstrapContext> execute(ClusterBootstrapConfig config) {
        return execute(config, false);
    }

    static Result<BootstrapContext> execute(ClusterBootstrapConfig config, boolean fullCheck) {
        ClusterBootstrapOrchestrator.logPhase(VALIDATE, "Validating bootstrap configuration");

        return ClusterBootstrapConfigValidator.validate(config)
                                              .map(BootstrapPhaseValidate::emitWarnings)
                                              .flatMap(validated -> runPreflightChecks(validated, fullCheck))
                                              .map(BootstrapPhaseValidate::buildContext);
    }

    private static ClusterBootstrapConfig emitWarnings(ClusterBootstrapConfig validated) {
        ClusterBootstrapConfigValidator.warnings(validated).forEach(BootstrapPhaseValidate::printWarning);

        return validated;
    }

    @Contract
    private static void printWarning(String warning) {
        System.out.println("  WARN: " + warning);
    }

    private static Result<ClusterBootstrapConfig> runPreflightChecks(ClusterBootstrapConfig config, boolean fullCheck) {
        if (fullCheck) {
            return PreflightChecker.runFull(config);
        }

        return PreflightChecker.runDefault(config);
    }

    private static BootstrapContext buildContext(ClusterBootstrapConfig validated) {
        var clusterName = validated.cluster().name();
        var configHash = ClusterBootstrapOrchestrator.computeConfigHash(validated);
        var clusterSecret = ClusterBootstrapOrchestrator.generateClusterSecret();
        var state = BootstrapState.initialState(clusterName,
                                                configHash,
                                                Instant.now().toString()).withClusterSecret(clusterSecret);

        return BootstrapContext.bootstrapContext(validated,
                                                 state,
                                                 List.of(),
                                                 List.of())
                               .withClusterSecret(clusterSecret);
    }
}
