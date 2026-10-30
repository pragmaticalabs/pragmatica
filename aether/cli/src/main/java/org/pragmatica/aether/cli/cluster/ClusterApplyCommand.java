// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
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
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;
import tools.jackson.databind.JsonNode;

import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_CONFIG_APPLY;
import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_CONFIG_GET;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


@Command(name = "apply", description = {"Apply cluster configuration changes", "", "With --cluster <name>, the file's [cluster] name is rewritten to <name> before it is applied,", "as 'aether cluster bootstrap --cluster' does, so a cluster bootstrapped under an override accepts its own TOML."})
@SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01", "JBCT-SEQ-01"})
class ClusterApplyCommand implements Callable<Integer> {
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    @Parameters(index = "0", description = "Path to aether-cluster.toml config file")
    private Path configFile;

    @CommandLine.Option(names = "--dry-run", description = "Show planned changes without executing")
    private boolean dryRun;

    @CommandLine.Option(names = "--yes", description = "Skip confirmation prompt")
    private boolean skipConfirmation;

    @CommandLine.Option(names = "--resume", description = "Resume a halted apply from first unfinished wave")
    private boolean resume;

    @CommandLine.Option(names = "--rollback", description = "Terminate the VMs a halted apply created (from its ledger), newest first; exits non-zero and keeps the ledger if any is left")
    private boolean rollback;

    @CommandLine.Option(names = "--full-check", description = "Run full network pre-flight checks before apply")
    private boolean fullCheck;

    @CommandLine.ParentCommand
    private ClusterCommand parent;

    @Mixin
    ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

    @Override
    public Integer call() {
        return rollback
               ? rollbackWithTarget()
               : applyWithTarget();
    }

    private Integer applyWithTarget() {
        return clusterTarget.applyOverrides()
                            .map(_ -> dispatch())
                            .fold(ClusterApplyCommand::onFailure, v -> v);
    }

    private int dispatch() {
        if (resume) {
            return handleResume();
        }

        return readConfigFile().flatMap(this::executeApply)
                             .fold(ClusterApplyCommand::onFailure, v -> v);
    }

    private int handleResume() {
        return readAndResolveConfig().flatMap(this::resumeApply)
                                   .fold(ClusterApplyCommand::onFailure, v -> v);
    }

    private Integer rollbackWithTarget() {
        return clusterTarget.applyOverrides()
                            .map(_ -> handleRollback())
                            .fold(ClusterApplyCommand::onFailure, v -> v);
    }

    /// Only the cluster's name is needed from the config file: the VMs to terminate are the ledger's, not the plan's.
    private int handleRollback() {
        return readAndParseConfig().flatMap(config -> ApplyOrchestrator.rollback(config.cluster().name()))
                                 .map(_ -> printRollbackComplete())
                                 .fold(ClusterApplyCommand::onFailure, v -> v);
    }

    private int printRollbackComplete() {
        System.out.println("Rollback complete: every VM the apply recorded is gone.");

        return ExitCode.SUCCESS;
    }

    private Result<Integer> resumeApply(ClusterBootstrapConfig desired) {
        return fetchAndParseStoredConfig().flatMap(stored -> ApplyOrchestrator.resume(desired, stored))
                                        .map(this::printApplyResult);
    }

    private int printApplyResult(ApplyResult result) {
        System.out.printf("Apply complete: %d added, %d modified, %d removed%n",
                          result.nodesAdded(),
                          result.nodesModified(),
                          result.nodesRemoved());

        return ExitCode.SUCCESS;
    }

    /// As written, `${env:...}` / `${secrets:...}` references left in place. Enough where only structure is read (`--rollback`
    /// needs the cluster's name and nothing else, so it must not fail on a reference it does not use).
    private Result<ClusterBootstrapConfig> readAndParseConfig() {
        return readConfigFile().flatMap(ClusterBootstrapConfigParser::parse);
    }

    /// The config the wave ACTS on: references resolved from the operator's environment, exactly as `aether cluster bootstrap`
    /// resolves them. The scalar `credentials` is the provider's `api_token` verbatim, so an unresolved reference would be sent
    /// to the provider as the token (a 401 at the first call of `--resume`).
    private Result<ClusterBootstrapConfig> readAndResolveConfig() {
        return readConfigFile().flatMap(ClusterApplyCommand::parseResolved);
    }

    static Result<ClusterBootstrapConfig> parseResolved(String rawToml) {
        return ConfigReferenceResolver.resolveAll(rawToml).flatMap(ClusterBootstrapConfigParser::parse);
    }

    /// The cluster stores the TOML as the operator sent it, references unresolved (resolved values are never sent to or kept by the
    /// cluster), so the stored config is resolved the same way as the desired one: the diff must compare like with like, and a
    /// stored source the wave removes is reached with its own credentials.
    private Result<ClusterBootstrapConfig> fetchAndParseStoredConfig() {
        return ClusterHttpClient.fetch(CLUSTER_CONFIG_GET)
                                .flatMap(MAPPER::readTree)
                                .flatMap(ClusterApplyCommand::extractStoredToml)
                                .flatMap(ClusterApplyCommand::parseResolved);
    }

    private static Result<String> extractStoredToml(JsonNode node) {
        var toml = node.path("tomlContent").asText("");

        return toml.isEmpty()
               ? new ApplyOrchestrator.ApplyAborted("No stored config found on cluster").result()
               : Result.success(toml);
    }

    private Result<String> readConfigFile() {
        return Result.lift(() -> Files.readString(configFile)).flatMap(raw -> withClusterNameOverride(raw,
                                                                                                      option(clusterTarget.clusterName())));
    }

    /// #1487 — `--cluster <name>` (from [ClusterTargetMixin]) both selects the target cluster and, mirroring
    /// `aether cluster bootstrap --cluster`, rewrites the TOML's `[cluster] name` to it before the config is sent
    /// (default path) or parsed and diffed (`--resume`). A cluster bootstrapped with `--cluster X`
    /// persists `X`; `cluster.name` is immutable, so applying the unmodified file (named `Y`) would otherwise be
    /// refused. Without `--cluster` (or with a blank one) the file is used unchanged.
    static Result<String> withClusterNameOverride(String rawToml, Option<String> override) {
        return override.filter(Verify.Is::notBlank)
                       .map(name -> ClusterNameToml.withClusterName(rawToml, name))
                       .or(success(rawToml));
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
