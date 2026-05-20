// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.aether.cli.OutputFormatter.Column;
import org.pragmatica.aether.cli.OutputFormatter.TableSpec;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;

import java.util.List;
import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_AUTO_HEAL_DISABLE;
import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_AUTO_HEAL_ENABLE;
import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_AUTO_HEAL_STATUS;
import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_CIRCUIT_BREAKER_RESET;
import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_CIRCUIT_BREAKER_STATUS;
import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_TOPOLOGY;


@Command(name = "topology",
         description = "Show cluster topology with node details",
         subcommands = {ClusterTopologyCommand.CircuitBreakerCommand.class, ClusterTopologyCommand.AutoHealCommand.class}) @SuppressWarnings("JBCT-RET-01") class ClusterTopologyCommand implements Callable<Integer> {
    private static final TableSpec TOPOLOGY_TABLE = new TableSpec("Cluster Topology",
                                                                  List.of(new Column("NODE", "nodeId", 16),
                                                                          new Column("ROLE", "role", 10),
                                                                          new Column("HEALTH", "health", 12),
                                                                          new Column("HOSTNAME", "hostname", 20),
                                                                          new Column("ZONE", "zone", 14),
                                                                          new Column("ADDRESS", "address", 24)),
                                                                  "nodeDetails");

    @CommandLine.ParentCommand private ClusterCommand parent;

    @Mixin ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

    @Override public Integer call() {
        return clusterTarget.applyOverrides().flatMap(_ -> ClusterHttpClient.fetch(CLUSTER_TOPOLOGY))
                                           .fold(ClusterTopologyCommand::onFailure, this::onSuccess);
    }

    private int onSuccess(String json) {
        return OutputFormatter.printQuery(json, parent.outputOptions(), TOPOLOGY_TABLE);
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());
        return ExitCode.ERROR;
    }

    @Command(name = "circuit-breaker",
             description = "CTM provisioning circuit breaker",
             subcommands = {CircuitBreakerCommand.StatusCommand.class, CircuitBreakerCommand.ResetCommand.class}) static class CircuitBreakerCommand implements Runnable {
        @CommandLine.ParentCommand private ClusterTopologyCommand topologyParent;

        @Contract@Override public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "status",
                 description = "Show CTM provisioning circuit breaker state") @SuppressWarnings("JBCT-RET-01") static class StatusCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private CircuitBreakerCommand cbParent;

            @Mixin ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

            @Override public Integer call() {
                return clusterTarget.applyOverrides().flatMap(_ -> ClusterHttpClient.fetch(CLUSTER_CIRCUIT_BREAKER_STATUS))
                                                   .fold(StatusCommand::onFailure, this::onSuccess);
            }

            private int onSuccess(String json) {
                return OutputFormatter.printQuery(json, cbParent.topologyParent.parent.outputOptions());
            }

            private static int onFailure(Cause cause) {
                System.err.println("Error: " + cause.message());
                return ExitCode.ERROR;
            }
        }

        @Command(name = "reset",
                 description = "Reset the CTM provisioning circuit breaker (operator override; use after fixing the underlying provisioning issue)") @SuppressWarnings("JBCT-RET-01") static class ResetCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private CircuitBreakerCommand cbParent;

            @Mixin ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

            @Override public Integer call() {
                return clusterTarget.applyOverrides().flatMap(_ -> ClusterHttpClient.post(CLUSTER_CIRCUIT_BREAKER_RESET, "{}"))
                                                   .fold(ResetCommand::onFailure, this::onSuccess);
            }

            private int onSuccess(String json) {
                return OutputFormatter.printAction(json, cbParent.topologyParent.parent.outputOptions(), "Circuit breaker reset");
            }

            private static int onFailure(Cause cause) {
                System.err.println("Error: " + cause.message());
                return ExitCode.ERROR;
            }
        }
    }

    @Command(name = "auto-heal",
             description = "CTM auto-heal (deficit-driven replacement provisioning) toggle",
             subcommands = {AutoHealCommand.StatusCommand.class, AutoHealCommand.EnableCommand.class, AutoHealCommand.DisableCommand.class}) static class AutoHealCommand implements Runnable {
        @CommandLine.ParentCommand private ClusterTopologyCommand topologyParent;

        @Contract@Override public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "status",
                 description = "Show whether CTM auto-heal is enabled") @SuppressWarnings("JBCT-RET-01") static class StatusCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private AutoHealCommand ahParent;

            @Mixin ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

            @Override public Integer call() {
                return clusterTarget.applyOverrides().flatMap(_ -> ClusterHttpClient.fetch(CLUSTER_AUTO_HEAL_STATUS))
                                                   .fold(StatusCommand::onFailure, this::onSuccess);
            }

            private int onSuccess(String json) {
                return OutputFormatter.printQuery(json, ahParent.topologyParent.parent.outputOptions());
            }

            private static int onFailure(Cause cause) {
                System.err.println("Error: " + cause.message());
                return ExitCode.ERROR;
            }
        }

        @Command(name = "enable",
                 description = "Enable CTM auto-heal (resume deficit-driven replacement provisioning)") @SuppressWarnings("JBCT-RET-01") static class EnableCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private AutoHealCommand ahParent;

            @Mixin ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

            @Override public Integer call() {
                return clusterTarget.applyOverrides().flatMap(_ -> ClusterHttpClient.post(CLUSTER_AUTO_HEAL_ENABLE, "{}"))
                                                   .fold(EnableCommand::onFailure, this::onSuccess);
            }

            private int onSuccess(String json) {
                return OutputFormatter.printAction(json, ahParent.topologyParent.parent.outputOptions(), "Auto-heal enabled");
            }

            private static int onFailure(Cause cause) {
                System.err.println("Error: " + cause.message());
                return ExitCode.ERROR;
            }
        }

        @Command(name = "disable",
                 description = "Disable CTM auto-heal (halt deficit-driven replacement provisioning until re-enabled)") @SuppressWarnings("JBCT-RET-01") static class DisableCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private AutoHealCommand ahParent;

            @Mixin ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

            @Override public Integer call() {
                return clusterTarget.applyOverrides().flatMap(_ -> ClusterHttpClient.post(CLUSTER_AUTO_HEAL_DISABLE, "{}"))
                                                   .fold(DisableCommand::onFailure, this::onSuccess);
            }

            private int onSuccess(String json) {
                return OutputFormatter.printAction(json, ahParent.topologyParent.parent.outputOptions(), "Auto-heal disabled");
            }

            private static int onFailure(Cause cause) {
                System.err.println("Error: " + cause.message());
                return ExitCode.ERROR;
            }
        }
    }
}
