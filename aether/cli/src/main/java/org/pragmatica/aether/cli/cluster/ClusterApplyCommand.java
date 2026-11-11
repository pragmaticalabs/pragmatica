// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.DiffPlan;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn4;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;
import tools.jackson.databind.JsonNode;

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

    @Mixin
    ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

    private final Fn4<Result<ApplyResult>, DiffPlan, ClusterBootstrapConfig, ClusterBootstrapConfig, ApplyRecorder> executor;

    ClusterApplyCommand() {
        this(WaveExecutor::execute);
    }

    /// Package-visible so a test can stand in for the waves (the orchestrator, the ledger and the commit are the real ones).
    ClusterApplyCommand(Fn4<Result<ApplyResult>, DiffPlan, ClusterBootstrapConfig, ClusterBootstrapConfig, ApplyRecorder> executor) {
        this.executor = executor;
    }

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
        return resume
               ? handleResume()
               : handleApply();
    }

    /// The default dispatch (#686): the plan is computed against the config the cluster holds and executed by the orchestrator under
    /// its ledger; the new config is committed to the cluster only after the waves.
    private int handleApply() {
        return readConfigFile().flatMap(this::planAndApply)
                             .fold(ClusterApplyCommand::onFailure, v -> v);
    }

    private Result<Integer> planAndApply(String rawToml) {
        return fetchStoredConfig().flatMap(stored -> parseResolved(rawToml).flatMap(desired -> applyOrDryRun(rawToml,
                                                                                                             desired,
                                                                                                             stored)));
    }

    private Result<Integer> applyOrDryRun(String rawToml, ClusterBootstrapConfig desired, StoredConfig stored) {
        return dryRun
               ? ApplyOrchestrator.dryRun(desired,
                                          stored.config())
                                  .map(this::printDryRun)
               : ApplyOrchestrator.apply(desired,
                                         stored.config(),
                                         skipConfirmation,
                                         stored.version(),
                                         ApplyCommit.toCluster(rawToml),
                                         executor)
                                  .map(this::printApplyResult);
    }

    private int printDryRun(String plan) {
        System.out.println(plan);
        System.out.println("Dry-run complete.");

        return ExitCode.SUCCESS;
    }

    private int handleResume() {
        return readConfigFile().flatMap(this::resumeApply)
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

    private Result<Integer> resumeApply(String rawToml) {
        return parseResolved(rawToml).flatMap(desired -> fetchStoredConfig().flatMap(stored -> ApplyOrchestrator.resume(desired,
                                                                                                                        stored.config(),
                                                                                                                        ApplyCommit.toCluster(rawToml))))
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

    /// The config the wave ACTS on is [#parseResolved]: references resolved from the operator's environment, exactly as `aether cluster
    /// bootstrap` resolves them. The scalar `credentials` is the provider's `api_token` verbatim, so an unresolved reference would be
    /// sent to the provider as the token (a 401 at the first call).
    static Result<ClusterBootstrapConfig> parseResolved(String rawToml) {
        return ConfigReferenceResolver.resolveAll(rawToml).flatMap(ClusterBootstrapConfigParser::parse);
    }

    /// The cluster stores the TOML as the operator sent it, references unresolved (resolved values are never sent to or kept by the
    /// cluster), so the stored config is resolved the same way as the desired one: the diff must compare like with like, and a
    /// stored source the wave removes is reached with its own credentials.
    private Result<StoredConfig> fetchStoredConfig() {
        return ClusterHttpClient.fetch(CLUSTER_CONFIG_GET)
                                .flatMap(MAPPER::readTree)
                                .flatMap(ClusterApplyCommand::storedConfig);
    }

    private static Result<StoredConfig> storedConfig(JsonNode node) {
        return extractStoredToml(node).flatMap(ClusterApplyCommand::parseResolved)
                                .map(config -> new StoredConfig(config,
                                                                node.path("configVersion").asLong(0)));
    }

    /// The cluster's stored config and the version a commit against it is fenced by.
    private record StoredConfig(ClusterBootstrapConfig config, long version) {}

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

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());

        return ExitCode.ERROR;
    }
}
