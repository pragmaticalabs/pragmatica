// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_MIGRATE;
import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_MIGRATE_PLAN;


@Command(name = "migrate", description = "Migrate cluster to a different cloud environment") @SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01", "JBCT-SEQ-01"}) class ClusterMigrateCommand implements Callable<Integer> {
    @Option(names = "--target", required = true, description = "Target cloud provider (aws, gcp, azure, hetzner)") private String targetProvider;

    @Option(names = "--zone", required = true, description = "Target availability zone or region (e.g., us-east-1a)") private String targetZone;

    @Option(names = "--strategy", defaultValue = "rolling", description = "Migration strategy: rolling or blue_green (default: rolling)") private String strategy;

    @Option(names = "--dns", description = "DNS hostname to update after migration (e.g., app.example.com)") private String dnsHostname;

    @Option(names = "--dry-run", description = "Show migration plan without executing") private boolean dryRun;

    @CommandLine.ParentCommand private ClusterCommand parent;

    @Override public Integer call() {
        return validateStrategy().flatMap(this::sendMigrateRequest)
                               .fold(ClusterMigrateCommand::onFailure, this::onSuccess);
    }

    private Result<String> validateStrategy() {
        return switch (strategy.toLowerCase()){
            case "rolling", "blue_green" -> Result.success(strategy.toLowerCase());
            default -> new MigrateError.InvalidStrategy(strategy).result();
        };
    }

    private Result<String> sendMigrateRequest(String validStrategy) {
        var jsonBody = buildRequestJson(validStrategy);
        return ClusterHttpClient.post(dryRun
                                      ? CLUSTER_MIGRATE_PLAN
                                      : CLUSTER_MIGRATE, jsonBody);
    }

    private String buildRequestJson(String validStrategy) {
        var sb = new StringBuilder(128);
        sb.append("{\"targetProvider\":\"").append(escapeJson(targetProvider))
                 .append('"');
        sb.append(",\"targetZone\":\"").append(escapeJson(targetZone))
                 .append('"');
        sb.append(",\"strategy\":\"").append(escapeJson(validStrategy))
                 .append('"');
        if (dnsHostname != null && !dnsHostname.isEmpty()) {sb.append(",\"dnsHostname\":\"").append(escapeJson(dnsHostname))
                                                                     .append('"');}
        sb.append('}');
        return sb.toString();
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private int onSuccess(String json) {
        var label = dryRun
                   ? "Migration plan generated."
                   : "Migration initiated.";
        return OutputFormatter.printAction(json, parent.outputOptions(), label);
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());
        return ExitCode.ERROR;
    }

    sealed interface MigrateError extends Cause {
        record InvalidStrategy(String strategy) implements MigrateError {
            @Override public String message() {
                return "Invalid migration strategy: " + strategy + " (expected: rolling or blue_green)";
            }
        }
    }
}
