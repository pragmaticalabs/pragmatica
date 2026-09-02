// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.concurrent.Callable;

import org.pragmatica.aether.cli.DestructiveAction;
import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_MIGRATE;
import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_MIGRATE_PLAN;


@Command(name = "migrate", description = "Migrate cluster to a different cloud environment")
@SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01", "JBCT-SEQ-01"})
class ClusterMigrateCommand implements Callable<Integer> {
    @Option(names = "--target", required = true, description = "Target cloud provider (aws, gcp, azure, hetzner)")
    private String targetProvider;

    @Option(names = "--zone", required = true, description = "Target availability zone or region (e.g., us-east-1a)")
    private String targetZone;

    @Option(names = "--strategy", defaultValue = "rolling", description = "Migration strategy: rolling or blue_green (default: rolling)")
    private String strategy;

    @Option(names = "--dns", description = "DNS hostname to update after migration (e.g., app.example.com)")
    private String dnsHostname;

    @Option(names = "--dry-run", description = "Show migration plan without executing")
    private boolean dryRun;

    @Option(names = {"--yes", "--force"}, description = "Skip interactive confirmation")
    private boolean skipConfirmation;

    @CommandLine.ParentCommand
    private ClusterCommand parent;

    @Mixin
    ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

    /// Pre-flight the PLAN route BEFORE prompting (#539).
    ///
    /// The prompt used to come first, so an operator was asked to authorise "this will migrate the
    /// cluster to <provider>" — typed yes — and only then discovered the server has no migration
    /// handler at all (`NotImplementedRoutes` answers 501; before #525 it was a bare 404). Asking
    /// someone to confirm an action the server cannot perform is the most misleading shape available:
    /// the confirmation itself advertises a capability that does not exist.
    ///
    /// The plan route is the honest pre-flight because it is the DRY-RUN of this very command — it is
    /// read-only by construction, so probing costs nothing and needs no separate capability endpoint.
    /// If it fails for ANY reason (501 unimplemented, unreachable, unauthorised) no prompt is shown:
    /// we could not establish that the server can do this, so there is nothing to authorise. When the
    /// handler is eventually built this same path starts succeeding and the confirmation returns —
    /// with the plan already on screen, which is what an operator should be shown before consenting
    /// to a whole-cluster migration anyway.
    @Override
    public Integer call() {
        return clusterTarget.applyOverrides()
                            .flatMap(_ -> validateStrategy())
                            .flatMap(this::sendPlanRequest)
                            .fold(this::onFailure, this::afterPlan);
    }

    private int afterPlan(String planJson) {
        if (dryRun) {
            return OutputFormatter.printAction(planJson, parent.outputOptions(), "Migration plan generated.");
        }

        System.out.println(planJson);
        if (!confirmMigration()) {
            System.out.println("Aborted.");

            return ExitCode.SUCCESS;
        }

        return validateStrategy().flatMap(this::sendMigrateRequest)
                               .fold(this::onFailure, this::onSuccess);
    }

    private Result<String> sendPlanRequest(String validStrategy) {
        return ClusterHttpClient.post(CLUSTER_MIGRATE_PLAN, buildRequestJson(validStrategy));
    }

    private boolean confirmMigration() {
        return DestructiveAction.destructiveAction().confirm(skipConfirmation,
                                                             "This will migrate the cluster to " + targetProvider
                                                            + "/" + targetZone
                                                            + " (strategy: " + strategy
                                                            + ").");
    }

    private Result<String> validateStrategy() {
        return switch (strategy.toLowerCase()) {
            case "rolling", "blue_green" -> Result.success(strategy.toLowerCase());
            default -> new MigrateError.InvalidStrategy(strategy).result();
        };
    }

    private Result<String> sendMigrateRequest(String validStrategy) {
        return ClusterHttpClient.post(CLUSTER_MIGRATE, buildRequestJson(validStrategy));
    }

    private String buildRequestJson(String validStrategy) {
        var sb = new StringBuilder(128);

        sb.append("{\"targetProvider\":\"").append(escapeJson(targetProvider)).append('"');
        sb.append(",\"targetZone\":\"").append(escapeJson(targetZone)).append('"');
        sb.append(",\"strategy\":\"").append(escapeJson(validStrategy)).append('"');
        if (dnsHostname != null && !dnsHostname.isEmpty()) {
            sb.append(",\"dnsHostname\":\"").append(escapeJson(dnsHostname)).append('"');
        }

        sb.append('}');

        return sb.toString();
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"");
    }

    /// Only reached for a real migration — the dry-run path returns from [#afterPlan].
    private int onSuccess(String json) {
        return OutputFormatter.printAction(json, parent.outputOptions(), "Migration initiated.");
    }

    private int onFailure(Cause cause) {
        return OutputFormatter.printError(cause, parent.outputOptions());
    }

    sealed interface MigrateError extends Cause {
        record InvalidStrategy(String strategy) implements MigrateError {
            @Override
            public String message() {
                return "Invalid migration strategy: " + strategy + " (expected: rolling or blue_green)";
            }
        }
    }
}
