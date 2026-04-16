// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.environment.NodeAddress;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;

import static org.pragmatica.aether.cli.cluster.BootstrapState.PhaseStatus;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Result.success;


/// Six-phase cluster bootstrap orchestrator. Section 8.
///
/// Phases:
/// 1. Validate -- static config validation (already done before entry, re-confirmed here)
/// 2. Provision -- create infrastructure per source type
/// 3. Collect Addresses -- gather node addresses from all sources
/// 4. Deploy Runtime -- install and start Aether on each node
/// 5. Cluster Formation -- wait for quorum, generate API key, store config
/// 6. Post-Bootstrap -- activate LBs, register locally, print info
///
/// State is persisted after each phase transition to support resume on failure.
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"}) public sealed interface ClusterBootstrapOrchestrator permits ClusterBootstrapOrchestrator.unused {
    record unused() implements ClusterBootstrapOrchestrator{}

    int API_KEY_BYTES = 32;

    int POLL_INTERVAL_MS = 5000;

    long DEFAULT_TIMEOUT_MS = 300_000;

    static Result<BootstrapResult> bootstrap(ClusterBootstrapConfig config) {
        return bootstrap(config, false, false);
    }

    static Result<BootstrapResult> bootstrap(ClusterBootstrapConfig config, boolean resume, boolean fullCheck) {
        if (resume) {return resumeBootstrap(config, fullCheck);}
        return freshBootstrap(config, fullCheck);
    }

    private static Result<BootstrapResult> freshBootstrap(ClusterBootstrapConfig config, boolean fullCheck) {
        return BootstrapPhaseValidate.execute(config, fullCheck).flatMap(ClusterBootstrapOrchestrator::runPhaseChain)
                                             .onFailure(cause -> cleanupOnFailure(config.cluster().name(),
                                                                                  cause));
    }

    private static Result<BootstrapResult> resumeBootstrap(ClusterBootstrapConfig config, boolean fullCheck) {
        var clusterName = config.cluster().name();
        return BootstrapStatePersistence.load(clusterName).toResult(new BootstrapError.ProvisionFailed(clusterName,
                                                                                                       "No bootstrap state found for resume"))
                                             .flatMap(state -> validateResumeState(state, config))
                                             .flatMap(state -> resumeFromState(config, state))
                                             .onFailure(cause -> cleanupOnFailure(clusterName, cause));
    }

    private static Result<BootstrapState> validateResumeState(BootstrapState state, ClusterBootstrapConfig config) {
        var currentHash = computeConfigHash(config);
        if (!currentHash.equals(state.configHash())) {return new BootstrapError.ProvisionFailed(state.clusterName(),
                                                                                                "Config has changed since last bootstrap (hash mismatch). Use fresh bootstrap or restore the original config.").result();}
        return success(state);
    }

    private static Result<BootstrapResult> resumeFromState(ClusterBootstrapConfig config, BootstrapState state) {
        System.out.println("Resuming bootstrap for cluster '" + state.clusterName() + "' from persisted state");
        var ctx = BootstrapContext.bootstrapContext(config, state, List.of(), List.of());
        return runPhaseChain(ctx);
    }

    private static Result<BootstrapResult> runPhaseChain(BootstrapContext ctx) {
        return executePhase(ctx, BootstrapPhase.PROVISION, BootstrapPhaseProvision::execute).flatMap(c -> executePhase(c,
                                                                                                                       BootstrapPhase.COLLECT_ADDRESSES,
                                                                                                                       BootstrapPhaseCollect::execute))
                           .flatMap(c -> executePhase(c, BootstrapPhase.DEPLOY_RUNTIME, BootstrapPhaseDeploy::execute))
                           .flatMap(c -> executePhase(c,
                                                      BootstrapPhase.CLUSTER_FORMATION,
                                                      BootstrapPhaseFormation::execute))
                           .flatMap(BootstrapPhasePost::execute);
    }

    private static Result<BootstrapContext> executePhase(BootstrapContext ctx,
                                                         BootstrapPhase phase,
                                                         Function<BootstrapContext, Result<BootstrapContext>> phaseFunc) {
        if (ctx.state().phases()
                     .get(phase) == PhaseStatus.COMPLETED) {
            logPhase(phase, "Skipping (already completed)");
            return success(ctx);
        }
        return markAndExecutePhase(ctx, phase, phaseFunc);
    }

    private static Result<BootstrapContext> markAndExecutePhase(BootstrapContext ctx,
                                                                BootstrapPhase phase,
                                                                Function<BootstrapContext, Result<BootstrapContext>> phaseFunc) {
        var inProgress = ctx.withState(ctx.state().withPhaseStatus(phase, PhaseStatus.IN_PROGRESS));
        BootstrapStatePersistence.save(inProgress.state());
        return phaseFunc.apply(inProgress).map(result -> markPhaseCompleted(result, phase))
                              .onFailure(cause -> markPhaseFailed(inProgress, phase));
    }

    private static BootstrapContext markPhaseCompleted(BootstrapContext result, BootstrapPhase phase) {
        var completed = result.withState(result.state().withPhaseStatus(phase, PhaseStatus.COMPLETED));
        BootstrapStatePersistence.save(completed.state());
        return completed;
    }

    @Contract private static void markPhaseFailed(BootstrapContext ctx, BootstrapPhase phase) {
        var failed = ctx.withState(ctx.state().withPhaseStatus(phase, PhaseStatus.FAILED));
        BootstrapStatePersistence.save(failed.state());
    }

    @Contract private static void cleanupOnFailure(String clusterName, Cause cause) {
        BootstrapStatePersistence.load(clusterName).filter(state -> !state.createdResources().isEmpty())
                                      .onPresent(BootstrapCleanup::cleanup);
    }

    @SuppressWarnings("JBCT-EX-01") static String computeConfigHash(ClusterBootstrapConfig config) {
        return Result.lift(() -> sha256(config.toString())).or(Integer.toHexString(config.hashCode()));
    }

    @SuppressWarnings("JBCT-EX-01") private static String sha256(String input) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        var hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    static String generateApiKey() {
        var bytes = new byte[API_KEY_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding()
                                   .encodeToString(bytes);
    }

    static String generateClusterSecret() {
        var bytes = new byte[API_KEY_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding()
                                   .encodeToString(bytes);
    }

    static String deriveApiKeyEnvName(String clusterName) {
        return "AETHER_" + clusterName.toUpperCase().replace('-', '_') + "_API_KEY";
    }

    @Contract static void logPhase(BootstrapPhase phase, String message) {
        System.out.printf("[Phase %d/%d: %s] %s%n",
                          phase.ordinal() + 1,
                          BootstrapPhase.values().length,
                          phase.name(),
                          message);
    }

    @Contract static void logPhase(BootstrapPhase phase, String format, Object arg) {
        logPhase(phase, String.format(format, arg));
    }

    static Result<String> httpPost(String url, String body) {
        return ClusterHttpClient.postDirect(url, body);
    }

    static Result<String> httpGet(String url) {
        return ClusterHttpClient.getDirect(url);
    }

    @SuppressWarnings("JBCT-EX-01") @Contract static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static long parseDurationMs(String duration) {
        if (duration.endsWith("s")) {return parseNumericPrefix(duration) * 1000;}
        if (duration.endsWith("m")) {return parseNumericPrefix(duration) * 60_000;}
        if (duration.endsWith("h")) {return parseNumericPrefix(duration) * 3_600_000;}
        return DEFAULT_TIMEOUT_MS;
    }

    private static long parseNumericPrefix(String duration) {
        return Result.lift(() -> Long.parseLong(duration.substring(0,
                                                                   duration.length() - 1)))
        .or(DEFAULT_TIMEOUT_MS / 1000);
    }

    record BootstrapResult(String clusterName,
                           String endpoint,
                           String apiKey,
                           List<ProvisionedNode> nodes,
                           String apiKeyEnvName) {
        static BootstrapResult bootstrapResult(String clusterName,
                                               String endpoint,
                                               String apiKey,
                                               List<ProvisionedNode> nodes,
                                               String apiKeyEnvName) {
            return new BootstrapResult(clusterName, endpoint, apiKey, List.copyOf(nodes), apiKeyEnvName);
        }
    }

    record BootstrapContext(ClusterBootstrapConfig config,
                            BootstrapState state,
                            List<ProvisionedNode> nodes,
                            List<NodeAddress> addresses,
                            Option<String> apiKey) {
        static BootstrapContext bootstrapContext(ClusterBootstrapConfig config,
                                                 BootstrapState state,
                                                 List<ProvisionedNode> nodes,
                                                 List<NodeAddress> addresses) {
            return new BootstrapContext(config, state, List.copyOf(nodes), List.copyOf(addresses), none());
        }

        BootstrapContext withNodes(List<ProvisionedNode> newNodes) {
            return new BootstrapContext(config, state, List.copyOf(newNodes), addresses, apiKey);
        }

        BootstrapContext withAddresses(List<NodeAddress> newAddresses) {
            return new BootstrapContext(config, state, nodes, List.copyOf(newAddresses), apiKey);
        }

        BootstrapContext withApiKey(String key) {
            return new BootstrapContext(config, state, nodes, addresses, Option.some(key));
        }

        BootstrapContext withState(BootstrapState newState) {
            return new BootstrapContext(config, newState, nodes, addresses, apiKey);
        }
    }

    sealed interface BootstrapError extends Cause {
        record ProvisionFailed(String sourceName, String detail) implements BootstrapError {
            @Override public String message() {
                return "Provisioning failed for source '" + sourceName + "': " + detail;
            }
        }

        record AddressCollectionFailed(String sourceName, String detail) implements BootstrapError {
            @Override public String message() {
                return "Address collection failed for source '" + sourceName + "': " + detail;
            }
        }

        record DeploymentFailed(String nodeId, String detail) implements BootstrapError {
            @Override public String message() {
                return "Runtime deployment failed for node '" + nodeId + "': " + detail;
            }
        }

        record QuorumNotEstablished(int healthy, int required) implements BootstrapError {
            @Override public String message() {
                return "Quorum not established: " + healthy + "/" + required + " nodes healthy";
            }
        }
    }
}
