// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import tools.jackson.databind.JsonNode;

import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_CONFIG_APPLY;
import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_CONFIG_GET;


@Command(name = "apply", description = "Apply cluster configuration changes")
@SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01", "JBCT-SEQ-01"})
class ClusterApplyCommand implements Callable<Integer> {
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    @Parameters(index = "0", description = "Path to aether-cluster.toml config file")
    private Path configFile;

    @Option(names = "--dry-run", description = "Show planned changes without executing")
    private boolean dryRun;

    @Option(names = "--yes", description = "Skip confirmation prompt")
    private boolean skipConfirmation;

    @Option(names = "--resume", description = "Resume a halted apply from first unfinished wave")
    private boolean resume;

    @Option(names = "--rollback", description = "Rollback completed waves to pre-apply state")
    private boolean rollback;

    @Option(names = "--full-check", description = "Run full network pre-flight checks before apply")
    private boolean fullCheck;

    @CommandLine.ParentCommand
    private ClusterCommand parent;

    @Mixin
    ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

    @Override
    public Integer call() {
        return clusterTarget.applyOverrides()
                            .map(_ -> dispatch())
                            .fold(ClusterApplyCommand::onFailure, v -> v);
    }

    private int dispatch() {
        if (resume) {
            return handleResume();
        }

        if (rollback) {
            return handleRollback();
        }

        return readConfigFile().flatMap(this::executeApply)
                             .fold(ClusterApplyCommand::onFailure, v -> v);
    }

    private int handleResume() {
        return readAndParseConfig().flatMap(this::resumeApply)
                                 .fold(ClusterApplyCommand::onFailure, v -> v);
    }

    private int handleRollback() {
        return readAndParseConfig().flatMap(this::rollbackApply)
                                 .fold(ClusterApplyCommand::onFailure, v -> v);
    }

    private Result<Integer> resumeApply(ClusterBootstrapConfig desired) {
        return fetchAndParseStoredConfig().flatMap(stored -> ApplyOrchestrator.resume(desired, stored))
                                        .map(this::printApplyResult);
    }

    private Result<Integer> rollbackApply(ClusterBootstrapConfig desired) {
        return fetchAndParseStoredConfig().flatMap(stored -> ApplyOrchestrator.rollback(desired, stored))
                                        .map(this::printApplyResult);
    }

    private int printApplyResult(ApplyResult result) {
        System.out.printf("Apply complete: %d added, %d modified, %d removed%n",
                          result.nodesAdded(),
                          result.nodesModified(),
                          result.nodesRemoved());

        return ExitCode.SUCCESS;
    }

    private Result<ClusterBootstrapConfig> readAndParseConfig() {
        return readConfigFile().flatMap(ClusterBootstrapConfigParser::parse);
    }

    private Result<ClusterBootstrapConfig> fetchAndParseStoredConfig() {
        return ClusterHttpClient.fetch(CLUSTER_CONFIG_GET)
                                .flatMap(MAPPER::readTree)
                                .flatMap(ClusterApplyCommand::extractStoredToml)
                                .flatMap(ClusterBootstrapConfigParser::parse);
    }

    private static Result<String> extractStoredToml(JsonNode node) {
        var toml = node.path("tomlContent").asText("");

        return toml.isEmpty()
               ? new ApplyOrchestrator.ApplyAborted("No stored config found on cluster").result()
               : Result.success(toml);
    }

    private Result<String> readConfigFile() {
        return Result.lift(() -> Files.readString(configFile));
    }

    private Result<Integer> executeApply(String tomlContent) {
        return fetchCurrentVersion().flatMap(version -> sendApplyRequest(tomlContent, version));
    }

    private Result<Long> fetchCurrentVersion() {
        return ClusterHttpClient.fetch(CLUSTER_CONFIG_GET)
                                .flatMap(MAPPER::readTree)
                                .map(node -> node.path("configVersion")
                                                 .asLong(0));
    }

    private Result<Integer> sendApplyRequest(String tomlContent, long expectedVersion) {
        var jsonBody = buildApplyJson(tomlContent,
                                      dryRun
                                      ? 0
                                      : expectedVersion);

        return ClusterHttpClient.post(CLUSTER_CONFIG_APPLY, jsonBody).map(this::printResult);
    }

    private int printResult(String json) {
        return OutputFormatter.printAction(json,
                                           parent.outputOptions(),
                                           dryRun
                                           ? "Dry-run complete."
                                           : "Applied successfully.");
    }

    private static String buildApplyJson(String tomlContent, long expectedVersion) {
        var escapedToml = escapeJsonString(tomlContent);

        return "{\"tomlContent\":\"" + escapedToml + "\",\"expectedVersion\":" + expectedVersion + "}";
    }

    private static String escapeJsonString(String value) {
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());

        return ExitCode.ERROR;
    }
}
