// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.time.Instant;
import java.util.List;

import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapError;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigValidator;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.RoleSubTable;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

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
                                              .flatMap(BootstrapPhaseValidate::refuseDockerWithDeclaredTls)
                                              .flatMap(validated -> refuseDockerCoresBesideOtherCores(validated))
                                              .map(BootstrapPhaseValidate::emitWarnings)
                                              .flatMap(validated -> runPreflightChecks(validated, fullCheck))
                                              .map(BootstrapPhaseValidate::buildContext);
    }

    /// #2089: HTTP is used only when the config explicitly disables TLS, never inferred from the source type. A docker source cannot serve
    /// the TLS a default config declares, so that combination is refused here, before provisioning.
    static Result<ClusterBootstrapConfig> refuseDockerWithDeclaredTls(ClusterBootstrapConfig config) {
        if (!config.operations().tls().autoGenerate()) {
            return Result.success(config);
        }

        return config.sources()
                     .entrySet()
                     .stream()
                     .filter(entry -> entry.getValue()
                                           .type() == SourceType.DOCKER)
                     .map(entry -> entry.getKey())
                     .sorted()
                     .findFirst()
                     .<Result<ClusterBootstrapConfig>> map(name -> new BootstrapError.DockerSourceDeclaresTls(name).result())
                     .orElseGet(() -> Result.success(config));
    }

    /// #2089: a docker source beside non-docker cores cannot reach them, and they cannot reach it. Docker nodes name each other by
    /// container name, the others by address, so no single peer list serves both: cores would split into two clusters, and a docker source
    /// holding only workers would boot them with an empty core list. Any docker source beside non-docker cores is refused, never split.
    static Result<ClusterBootstrapConfig> refuseDockerCoresBesideOtherCores(ClusterBootstrapConfig config) {
        var dockerSources = dockerSources(config);
        var otherCores = coreSources(config, false);

        return dockerSources.isEmpty() || otherCores.isEmpty()
               ? Result.success(config)
               : new BootstrapError.DockerCoresMixedWithOtherCores(dockerSources.getFirst(), otherCores.getFirst()).<ClusterBootstrapConfig> result();
    }

    private static List<String> dockerSources(ClusterBootstrapConfig config) {
        return config.sources()
                     .entrySet()
                     .stream()
                     .filter(entry -> entry.getValue()
                                           .type() == SourceType.DOCKER)
                     .map(entry -> entry.getKey())
                     .sorted()
                     .toList();
    }

    private static List<String> coreSources(ClusterBootstrapConfig config, boolean docker) {
        return config.sources()
                     .entrySet()
                     .stream()
                     .filter(entry -> (entry.getValue()
                                            .type() == SourceType.DOCKER) == docker)
                     .filter(entry -> hasCores(entry.getValue()))
                     .map(entry -> entry.getKey())
                     .sorted()
                     .toList();
    }

    private static boolean hasCores(SourceProfile source) {
        return Option.option(source.roles().get(NodeRole.CORE))
                     .flatMap(RoleSubTable::count)
                     .or(0) > 0 || Option.option(source.roles().get(NodeRole.CORE))
                                         .flatMap(RoleSubTable::hosts)
                                         .map(hosts -> !hosts.isEmpty())
                                         .or(false);
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
                                                Instant.now().toString())
                                  .withClusterSecret(clusterSecret);

        return BootstrapContext.bootstrapContext(validated,
                                                 state,
                                                 List.of(),
                                                 List.of())
                               .withClusterSecret(clusterSecret);
    }
}
