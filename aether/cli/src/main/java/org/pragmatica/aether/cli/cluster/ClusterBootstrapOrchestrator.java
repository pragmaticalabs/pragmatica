// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.environment.NodeAddress;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.aether.cli.cluster.BootstrapState.PhaseStatus;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Result.success;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
public sealed interface ClusterBootstrapOrchestrator permits ClusterBootstrapOrchestrator.unused {
    record unused() implements ClusterBootstrapOrchestrator {}

    int API_KEY_BYTES = 32;
    int POLL_INTERVAL_MS = 5000;
    long DEFAULT_TIMEOUT_MS = 300_000;

    AtomicReference<Function<BootstrapState, Result<Unit>>> CLEANUP_HOOK = new AtomicReference<>(BootstrapCleanup::cleanup);

    static Function<BootstrapState, Result<Unit>> cleanupHook() {
        return CLEANUP_HOOK.get();
    }

    @Contract
    static void setCleanupHook(Function<BootstrapState, Result<Unit>> hook) {
        CLEANUP_HOOK.set(hook);
    }

    static Result<BootstrapResult> bootstrap(ClusterBootstrapConfig config) {
        return bootstrap(config, false, false, List.of(), false, "");
    }

    static Result<BootstrapResult> bootstrap(ClusterBootstrapConfig config, boolean resume, boolean fullCheck) {
        return bootstrap(config, resume, fullCheck, List.of(), false, "");
    }

    static Result<BootstrapResult> bootstrap(ClusterBootstrapConfig config,
                                             boolean resume,
                                             boolean fullCheck,
                                             List<SshPublicKey> sshPublicKeys) {
        return bootstrap(config, resume, fullCheck, sshPublicKeys, false, "");
    }

    static Result<BootstrapResult> bootstrap(ClusterBootstrapConfig config,
                                             boolean resume,
                                             boolean fullCheck,
                                             List<SshPublicKey> sshPublicKeys,
                                             boolean keepOnFailure) {
        return bootstrap(config, resume, fullCheck, sshPublicKeys, keepOnFailure, "");
    }

    static Result<BootstrapResult> bootstrap(ClusterBootstrapConfig config,
                                             boolean resume,
                                             boolean fullCheck,
                                             List<SshPublicKey> sshPublicKeys,
                                             boolean keepOnFailure,
                                             String rawTomlContent) {
        if (resume) {
            return resumeBootstrap(config, fullCheck, sshPublicKeys, keepOnFailure, rawTomlContent);
        }

        return freshBootstrap(config, fullCheck, sshPublicKeys, keepOnFailure, rawTomlContent);
    }

    /// #209: once the cluster secret is known (post-Validate for fresh, post-resume otherwise),
    /// install a trust store containing ONLY the CA derived from it, so all subsequent bootstrap HTTPS
    /// calls (node health, formation POSTs carrying the operator API key) verify the cluster's own
    /// auto-generated certificates and reject any other — a real MITM defense rather than trust-all.
    @Contract
    private static void configureClusterHttpClient(ClusterBootstrapConfig config, String clusterSecret) {
        if (config.operations().tls().autoGenerate()) {
            ClusterHttpClient.enableClusterTrust(clusterSecret);
        }
    }

    private static Result<BootstrapResult> freshBootstrap(ClusterBootstrapConfig config,
                                                          boolean fullCheck,
                                                          List<SshPublicKey> sshPublicKeys,
                                                          boolean keepOnFailure,
                                                          String rawTomlContent) {
        return BootstrapPhaseValidate.execute(config, fullCheck)
                                     .map(ctx -> ctx.withSshPublicKeys(sshPublicKeys))
                                     .map(ctx -> ctx.withRawTomlContent(rawTomlContent))
                                     .flatMap(ClusterBootstrapOrchestrator::runPhaseChain)
                                     .fold(cause -> Result.<BootstrapResult> failure(decorateAfterCleanup(config.cluster()
                                                                                                                .name(),
                                                                                                          cause,
                                                                                                          keepOnFailure)),
                                           Result::success);
    }

    private static Result<BootstrapResult> resumeBootstrap(ClusterBootstrapConfig config,
                                                           boolean fullCheck,
                                                           List<SshPublicKey> sshPublicKeys,
                                                           boolean keepOnFailure,
                                                           String rawTomlContent) {
        var clusterName = config.cluster().name();

        return BootstrapStatePersistence.load(clusterName)
                                        .toResult(new BootstrapError.ProvisionFailed(clusterName,
                                                                                     "No bootstrap state found for resume"))
                                        .flatMap(state -> validateResumeState(state, config))
                                        .flatMap(state -> resumeFromState(config, state, sshPublicKeys, rawTomlContent))
                                        .fold(cause -> Result.<BootstrapResult> failure(decorateAfterCleanup(clusterName,
                                                                                                             cause,
                                                                                                             keepOnFailure)),
                                              Result::success);
    }

    private static Result<BootstrapState> validateResumeState(BootstrapState state, ClusterBootstrapConfig config) {
        var currentHash = computeConfigHash(config);

        if (!currentHash.equals(state.configHash())) {
            return new BootstrapError.ProvisionFailed(state.clusterName(),
                                                      "Config has changed since last bootstrap (hash mismatch). Use fresh bootstrap or restore the original config.").result();
        }

        return success(state);
    }

    private static Result<BootstrapResult> resumeFromState(ClusterBootstrapConfig config,
                                                           BootstrapState state,
                                                           List<SshPublicKey> sshPublicKeys,
                                                           String rawTomlContent) {
        System.out.println("Resuming bootstrap for cluster '" + state.clusterName() + "' from persisted state");
        var resumedSecret = state.clusterSecret().isEmpty()
                            ? generateClusterSecret()
                            : state.clusterSecret();
        var resumedState = state.clusterSecret().isEmpty()
                           ? state.withClusterSecret(resumedSecret)
                           : state;
        var ctx = BootstrapContext.bootstrapContext(config,
                                                    resumedState,
                                                    List.of(),
                                                    List.of())
                                  .withSshPublicKeys(sshPublicKeys)
                                  .withClusterSecret(resumedSecret)
                                  .withRawTomlContent(rawTomlContent);

        return runPhaseChain(ctx);
    }

    private static Result<BootstrapResult> runPhaseChain(BootstrapContext ctx) {
        configureClusterHttpClient(ctx.config(), ctx.clusterSecret());

        return executePhase(ctx, BootstrapPhase.UPLOAD_SSH_KEYS, BootstrapPhaseSshKey::execute).flatMap(c -> executePhase(c,
                                                                                                                          BootstrapPhase.CREATE_FIREWALL,
                                                                                                                          BootstrapPhaseFirewall::execute))
                           .flatMap(c -> executePhase(c, BootstrapPhase.PROVISION, BootstrapPhaseProvision::execute))
                           .flatMap(c -> executePhase(c,
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
        if (ctx.state().phases().get(phase) == PhaseStatus.COMPLETED) {
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

        return phaseFunc.apply(inProgress)
                        .map(result -> markPhaseCompleted(result, phase))
                        .onFailure(cause -> markPhaseFailed(inProgress, phase));
    }

    private static BootstrapContext markPhaseCompleted(BootstrapContext result, BootstrapPhase phase) {
        var completed = result.withState(result.state().withPhaseStatus(phase, PhaseStatus.COMPLETED));

        BootstrapStatePersistence.save(completed.state());

        return completed;
    }

    @Contract
    private static void markPhaseFailed(BootstrapContext ctx, BootstrapPhase phase) {
        var failed = ctx.withState(ctx.state().withPhaseStatus(phase, PhaseStatus.FAILED));

        BootstrapStatePersistence.save(failed.state());
    }

    static Cause decorateAfterCleanup(String clusterName, Cause cause, boolean keepOnFailure) {
        if (keepOnFailure) {
            warnKeepOnFailure(clusterName, cause);

            return cause;
        }

        return cleanupOnFailure(clusterName).fold(cleanupCause -> wrapWithOrphans(cause, cleanupCause), _ -> cause);
    }

    private static Cause wrapWithOrphans(Cause originalCause, Cause cleanupCause) {
        return new BootstrapError.BootstrapFailedWithOrphans(originalCause, cleanupCause.message());
    }

    private static Result<Unit> cleanupOnFailure(String clusterName) {
        return BootstrapStatePersistence.load(clusterName)
                                        .filter(state -> !state.createdResources()
                                                               .isEmpty())
                                        .map(state -> cleanupHook().apply(state))
                                        .or(Result.unitResult());
    }

    @Contract
    private static void warnKeepOnFailure(String clusterName, Cause cause) {
        var state = BootstrapStatePersistence.load(clusterName);
        var resources = state.map(BootstrapState::createdResources).or(List.of());
        var vmCount = resources.stream().filter(r -> r instanceof CreatedResource.ProvisionedVm).count();
        var keyCount = resources.stream().filter(r -> r instanceof CreatedResource.SshKeyResource).count();
        var failedPhase = state.map(ClusterBootstrapOrchestrator::resolveFailedPhase).or("UNKNOWN");

        System.err.printf("[--keep-on-failure] Bootstrap of cluster '%s' failed at phase %s (%s).%n",
                          clusterName,
                          failedPhase,
                          cause.message());
        System.err.printf("[--keep-on-failure] Keeping %d provisioned VM(s) and %d SSH key(s); state retained at %s.%n",
                          vmCount,
                          keyCount,
                          BootstrapStatePersistence.statePath(clusterName));
        System.err.println("[--keep-on-failure] To inspect: ssh aether@<vm-ip> (or root@). "
                          + "To clean up later: aether cluster destroy --cluster " + clusterName
                          + " --yes.");
    }

    private static String resolveFailedPhase(BootstrapState state) {
        return state.phases()
                    .entrySet()
                    .stream()
                    .filter(e -> e.getValue() == PhaseStatus.FAILED || e.getValue() == PhaseStatus.IN_PROGRESS)
                    .map(e -> e.getKey()
                               .name())
                    .findFirst()
                    .orElse("UNKNOWN");
    }

    @SuppressWarnings("JBCT-EX-01")
    static String computeConfigHash(ClusterBootstrapConfig config) {
        return Result.lift(() -> sha256(config.toString())).or(Integer.toHexString(config.hashCode()));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static String sha256(String input) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        var hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));

        return HexFormat.of().formatHex(hash);
    }

    static String generateApiKey() {
        var bytes = new byte[API_KEY_BYTES];

        new SecureRandom().nextBytes(bytes);

        return Base64.getUrlEncoder()
                     .withoutPadding()
                     .encodeToString(bytes);
    }

    static String generateClusterSecret() {
        var bytes = new byte[API_KEY_BYTES];

        new SecureRandom().nextBytes(bytes);

        return Base64.getUrlEncoder()
                     .withoutPadding()
                     .encodeToString(bytes);
    }

    static String deriveApiKeyEnvName(String clusterName) {
        return "AETHER_" + clusterName.toUpperCase()
                                      .replace('-', '_') + "_API_KEY";
    }

    @Contract
    static void logPhase(BootstrapPhase phase, String message) {
        System.out.printf("[Phase %d/%d: %s] %s%n",
                          phase.ordinal() + 1,
                          BootstrapPhase.values().length,
                          phase.name(),
                          message);
    }

    @Contract
    static void logPhase(BootstrapPhase phase, String format, Object arg) {
        logPhase(phase, String.format(format, arg));
    }

    static Result<String> httpPost(String url, String body) {
        return ClusterHttpClient.postDirect(url, body);
    }

    static Result<String> httpPost(String url, String body, Option<String> apiKey) {
        return ClusterHttpClient.postDirect(url, body, apiKey);
    }

    static Result<String> httpGet(String url) {
        return ClusterHttpClient.getDirect(url);
    }

    static Result<String> httpGet(String url, Option<String> apiKey) {
        return ClusterHttpClient.getDirect(url, apiKey);
    }

    @SuppressWarnings("JBCT-EX-01")
    @Contract
    static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static long parseDurationMs(String duration) {
        if (duration.endsWith("s")) {
            return parseNumericPrefix(duration) * 1000;
        }

        if (duration.endsWith("m")) {
            return parseNumericPrefix(duration) * 60_000;
        }

        if (duration.endsWith("h")) {
            return parseNumericPrefix(duration) * 3_600_000;
        }

        return DEFAULT_TIMEOUT_MS;
    }

    private static long parseNumericPrefix(String duration) {
        return Result.lift(() -> Long.parseLong(duration.substring(0, duration.length() - 1))).or(DEFAULT_TIMEOUT_MS / 1000);
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
                            Option<String> apiKey,
                            List<SshPublicKey> sshPublicKeys,
                            Map<String, List<Long>> sshKeyIdsByProvider,
                            Map<String, List<String>> firewallIdsBySource,
                            String clusterSecret,
                            String rawTomlContent) {
        static BootstrapContext bootstrapContext(ClusterBootstrapConfig config,
                                                 BootstrapState state,
                                                 List<ProvisionedNode> nodes,
                                                 List<NodeAddress> addresses) {
            return new BootstrapContext(config,
                                        state,
                                        List.copyOf(nodes),
                                        List.copyOf(addresses),
                                        none(),
                                        List.of(),
                                        Map.of(),
                                        Map.of(),
                                        "",
                                        "");
        }

        BootstrapContext withNodes(List<ProvisionedNode> newNodes) {
            return new BootstrapContext(config,
                                        state,
                                        List.copyOf(newNodes),
                                        addresses,
                                        apiKey,
                                        sshPublicKeys,
                                        sshKeyIdsByProvider,
                                        firewallIdsBySource,
                                        clusterSecret,
                                        rawTomlContent);
        }

        BootstrapContext withAddresses(List<NodeAddress> newAddresses) {
            return new BootstrapContext(config,
                                        state,
                                        nodes,
                                        List.copyOf(newAddresses),
                                        apiKey,
                                        sshPublicKeys,
                                        sshKeyIdsByProvider,
                                        firewallIdsBySource,
                                        clusterSecret,
                                        rawTomlContent);
        }

        BootstrapContext withApiKey(String key) {
            return new BootstrapContext(config,
                                        state,
                                        nodes,
                                        addresses,
                                        Option.some(key),
                                        sshPublicKeys,
                                        sshKeyIdsByProvider,
                                        firewallIdsBySource,
                                        clusterSecret,
                                        rawTomlContent);
        }

        BootstrapContext withState(BootstrapState newState) {
            return new BootstrapContext(config,
                                        newState,
                                        nodes,
                                        addresses,
                                        apiKey,
                                        sshPublicKeys,
                                        sshKeyIdsByProvider,
                                        firewallIdsBySource,
                                        clusterSecret,
                                        rawTomlContent);
        }

        BootstrapContext withSshPublicKeys(List<SshPublicKey> keys) {
            return new BootstrapContext(config,
                                        state,
                                        nodes,
                                        addresses,
                                        apiKey,
                                        List.copyOf(keys),
                                        sshKeyIdsByProvider,
                                        firewallIdsBySource,
                                        clusterSecret,
                                        rawTomlContent);
        }

        BootstrapContext withSshKeyIds(String provider, List<Long> ids) {
            var merged = new HashMap<String, List<Long>>(sshKeyIdsByProvider);

            merged.put(provider, List.copyOf(ids));

            return new BootstrapContext(config,
                                        state,
                                        nodes,
                                        addresses,
                                        apiKey,
                                        sshPublicKeys,
                                        Map.copyOf(merged),
                                        firewallIdsBySource,
                                        clusterSecret,
                                        rawTomlContent);
        }

        BootstrapContext withClusterSecret(String secret) {
            return new BootstrapContext(config,
                                        state,
                                        nodes,
                                        addresses,
                                        apiKey,
                                        sshPublicKeys,
                                        sshKeyIdsByProvider,
                                        firewallIdsBySource,
                                        secret,
                                        rawTomlContent);
        }

        BootstrapContext withRawTomlContent(String toml) {
            return new BootstrapContext(config,
                                        state,
                                        nodes,
                                        addresses,
                                        apiKey,
                                        sshPublicKeys,
                                        sshKeyIdsByProvider,
                                        firewallIdsBySource,
                                        clusterSecret,
                                        toml);
        }

        List<Long> sshKeyIdsFor(String provider) {
            return sshKeyIdsByProvider.getOrDefault(provider, List.of());
        }

        BootstrapContext withFirewallIds(String sourceName, List<String> ids) {
            var merged = new HashMap<String, List<String>>(firewallIdsBySource);

            merged.put(sourceName, List.copyOf(ids));

            return new BootstrapContext(config,
                                        state,
                                        nodes,
                                        addresses,
                                        apiKey,
                                        sshPublicKeys,
                                        sshKeyIdsByProvider,
                                        Map.copyOf(merged),
                                        clusterSecret,
                                        rawTomlContent);
        }

        /// Ingress-firewall ids created for `sourceName` by [BootstrapPhaseFirewall], threaded into
        /// server-create so the rules are in force BEFORE the instance exists (§6.2). Keyed by SOURCE,
        /// not provider (unlike ssh keys): firewall rules are declared per source.
        List<String> firewallIdsFor(String sourceName) {
            return firewallIdsBySource.getOrDefault(sourceName, List.of());
        }
    }

    sealed interface BootstrapError extends Cause {
        record ProvisionFailed(String sourceName, String detail) implements BootstrapError {
            @Override
            public String message() {
                return "Provisioning failed for source '" + sourceName + "': " + detail;
            }
        }

        record AddressCollectionFailed(String sourceName, String detail) implements BootstrapError {
            @Override
            public String message() {
                return "Address collection failed for source '" + sourceName + "': " + detail;
            }
        }

        record DeploymentFailed(String nodeId, String detail) implements BootstrapError {
            @Override
            public String message() {
                return "Runtime deployment failed for node '" + nodeId + "': " + detail;
            }
        }

        record QuorumNotEstablished(int healthy, int required) implements BootstrapError {
            @Override
            public String message() {
                return "Quorum not established: " + healthy + "/" + required + " nodes healthy";
            }
        }

        record FormationWriteFailed(String operation, int attempts, long elapsedMs, String lastError) implements BootstrapError {
            @Override
            public String message() {
                return "Cluster formation write failed: " + operation
                     + " (after " + attempts
                     + " attempts over " + (elapsedMs / 1000)
                     + "s) — " + lastError;
            }
        }

        record BootstrapFailedWithOrphans(Cause originalCause, String cleanupDetail) implements BootstrapError {
            @Override
            public String message() {
                return originalCause.message() + " — cleanup failed, orphan resources may remain: " + cleanupDetail;
            }
        }
    }
}
