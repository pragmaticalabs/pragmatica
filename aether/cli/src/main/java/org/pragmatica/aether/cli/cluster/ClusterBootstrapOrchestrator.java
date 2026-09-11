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
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.FirewallId;
import org.pragmatica.aether.environment.NodeAddress;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.aether.environment.SourceName;
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

        return BootstrapStatePersistence.read(clusterName)
                                        .flatMap(state -> state.toResult(new BootstrapError.ProvisionFailed(clusterName.value(),
                                                                                                            "No bootstrap state found for resume")))
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
            return new BootstrapError.ProvisionFailed(state.clusterName().value(),
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

        saveState(inProgress.state(), phase);

        return phaseFunc.apply(inProgress)
                        .map(result -> markPhaseCompleted(result, phase))
                        .onFailure(cause -> markPhaseFailed(inProgress.state(),
                                                            phase));
    }

    private static BootstrapContext markPhaseCompleted(BootstrapContext result, BootstrapPhase phase) {
        var completed = result.withState(result.state().withPhaseStatus(phase, PhaseStatus.COMPLETED));

        saveState(completed.state(), phase);

        return completed;
    }

    /// #994 verification finding SF-1 — **the whole ledger mechanism rests on this write, and its `Result`
    /// used to be discarded.** `BootstrapPhaseProvision.recordProvisionedVm` loads the state FILE, appends
    /// the VM and saves; when the file is absent the load is empty and the append is a silent no-op. What
    /// guarantees it is not absent is exactly this pre-phase save. If it fails and nobody says so, every
    /// paid VM of the PROVISION phase is dropped from the ledger and #994 recurs with no diagnostic at all.
    ///
    /// It WARNS rather than aborting, deliberately. Aborting would fail closed at the wrong moment: a
    /// bootstrap that could have succeeded is turned into a hard failure by a full disk in `~/.aether`,
    /// and the phase has created nothing yet, so there is no money at stake to protect. What the operator
    /// needs instead is to learn now, and `recordProvisionedVm` additionally prints every server id it
    /// could not record — so the ids reach the transcript even when the ledger cannot hold them.
    @Contract
    private static void saveState(BootstrapState state, BootstrapPhase phase) {
        var _ = BootstrapStatePersistence.save(state)
                                         .onFailure(cause -> warnStateNotPersisted(state.clusterName(), phase, cause));
    }

    @Contract
    private static void warnStateNotPersisted(ClusterName clusterName, BootstrapPhase phase, Cause cause) {
        System.err.printf("  WARN: could not persist bootstrap state for cluster '%s' at phase %s: %s%n",
                          clusterName,
                          phase,
                          cause.message());
        System.err.printf("  The state file (%s) is teardown's ONLY record of created cloud resources. While it"
                         + " cannot be written, resources this run creates will not be reapable by"
                         + " 'aether cluster destroy' — every server id is printed as it is created so it can be"
                         + " removed with tools/cloud-reaper.sh.%n",
                          BootstrapStatePersistence.statePath(clusterName));
    }

    /// #994 — the FAILED marker must not ERASE what the failing phase already recorded. `preSnapshot` is
    /// the state as it was BEFORE the phase ran, and PROVISION now appends each paid VM to the persisted
    /// ledger as the provider reports it created (see `BootstrapPhaseProvision.recordProvisionedVm`), so
    /// saving the snapshot would discard exactly the records teardown needs — which is how a
    /// mid-PROVISION quota refusal stranded two running `ccx23` servers on 2026-09-11.
    ///
    /// The state FILE is the authority for created resources, so re-load it and mark the phase FAILED on
    /// THAT, falling back to the snapshot only when nothing is persisted. Takes a `BootstrapState` rather
    /// than a `BootstrapContext` so the preservation property is testable without a full config fixture.
    ///
    /// #994 verification finding SF-1 — **the fallback reads through
    /// [BootstrapStatePersistence#read], not `load`, because "nothing persisted" and "unreadable" are
    /// different facts and only one of them is safe to overwrite.** `load` collapses both to empty, and
    /// over a TORN file the snapshot save then replaces the only record of paid VMs with a VM-less one
    /// that is valid JSON — undetectable afterwards, and #994's outcome by another route. On an unreadable
    /// ledger this now refuses to write at all: the bytes stay on disk for recovery and the operator is
    /// told. There is no trade to weigh on this path — it runs only after the bootstrap has ALREADY
    /// failed, so failing closed here cannot cost a run that would otherwise have succeeded.
    @Contract
    static void markPhaseFailed(BootstrapState preSnapshot, BootstrapPhase phase) {
        var _ = BootstrapStatePersistence.read(preSnapshot.clusterName())
                                         .onFailure(cause -> refuseToOverwriteUnreadableLedger(preSnapshot.clusterName(),
                                                                                               phase,
                                                                                               cause))
                                         .onSuccess(persisted -> saveState(persisted.or(preSnapshot)
                                                                                    .withPhaseStatus(phase, PhaseStatus.FAILED),
                                                                            phase));
    }

    @Contract
    private static void refuseToOverwriteUnreadableLedger(ClusterName clusterName, BootstrapPhase phase, Cause cause) {
        System.err.printf("  WARN: the bootstrap state file for cluster '%s' exists but cannot be read: %s%n",
                          clusterName,
                          cause.message());
        System.err.printf("  REFUSING to mark %s as FAILED, because writing would replace the only record of this"
                         + " run's created resources with a pre-phase snapshot that has none — and the result would"
                         + " be valid JSON, so nothing afterwards could tell. The unreadable file is left at %s for"
                         + " recovery.%n",
                          phase,
                          BootstrapStatePersistence.statePath(clusterName));
        System.err.printf("  Reap whatever this run created with: tools/cloud-reaper.sh --cluster %s --destroy%n",
                          clusterName);
    }

    static Cause decorateAfterCleanup(ClusterName clusterName, Cause cause, boolean keepOnFailure) {
        if (keepOnFailure) {
            warnKeepOnFailure(clusterName, cause);

            return cause;
        }

        return cleanupOnFailure(clusterName).fold(cleanupCause -> wrapWithOrphans(cause, cleanupCause), _ -> cause);
    }

    private static Cause wrapWithOrphans(Cause originalCause, Cause cleanupCause) {
        return new BootstrapError.BootstrapFailedWithOrphans(originalCause, cleanupCause.message());
    }

    /// #994 verification finding SF-1 — reads through [BootstrapStatePersistence#read] so an UNPARSEABLE
    /// ledger fails the cleanup instead of reading as "no resources were created". Under `load` the two
    /// were the same empty `Option`, and a torn file therefore produced a silent, successful, zero-delete
    /// teardown over resources that are still billing.
    private static Result<Unit> cleanupOnFailure(ClusterName clusterName) {
        return BootstrapStatePersistence.read(clusterName)
                                        .mapError(cause -> new BootstrapError.LedgerUnreadable(clusterName, cause))
                                        .flatMap(ClusterBootstrapOrchestrator::cleanupRecordedResources);
    }

    private static Result<Unit> cleanupRecordedResources(Option<BootstrapState> state) {
        return state.filter(persisted -> !persisted.createdResources()
                                                  .isEmpty())
                    .map(persisted -> cleanupHook().apply(persisted))
                    .or(Result.unitResult());
    }

    /// `--keep-on-failure` reports from the ledger, so an unreadable ledger must be reported AS unreadable:
    /// the counts it would otherwise print are zeros nobody measured, and this text is the operator's whole
    /// basis for deciding what is still running (#994 verification finding SF-1, the same
    /// absent-vs-unreadable conflation).
    @Contract
    private static void warnKeepOnFailure(ClusterName clusterName, Cause cause) {
        var read = BootstrapStatePersistence.read(clusterName)
                                            .onFailure(parseCause -> warnLedgerUnreadableOnKeep(clusterName, parseCause));
        var state = read.or(none());
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

    @Contract
    private static void warnLedgerUnreadableOnKeep(ClusterName clusterName, Cause cause) {
        System.err.printf("[--keep-on-failure] WARNING: the state file is present but unreadable (%s), so the"
                         + " counts below are NOT measured — they are what an empty ledger reports. Resources may"
                         + " exist that nothing here can name; finish teardown with 'tools/cloud-reaper.sh"
                         + " --cluster %s --destroy'.%n",
                          cause.message(),
                          clusterName);
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

    static String deriveApiKeyEnvName(ClusterName clusterName) {
        return "AETHER_" + clusterName.value()
                                      .toUpperCase()
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

    record BootstrapResult(ClusterName clusterName,
                           String endpoint,
                           String apiKey,
                           List<ProvisionedNode> nodes,
                           String apiKeyEnvName) {
        static BootstrapResult bootstrapResult(ClusterName clusterName,
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
                            Map<SourceName, List<FirewallId>> firewallIdsBySource,
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

        BootstrapContext withFirewallIds(SourceName sourceName, List<FirewallId> ids) {
            var merged = new HashMap<SourceName, List<FirewallId>>(firewallIdsBySource);

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
        List<FirewallId> firewallIdsFor(SourceName sourceName) {
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

        /// `observed` is the member count last READ from the cluster's own `/api/v1/health` view, or
        /// [#UNOBSERVED] when that view was never readable at all — a 401, an unreachable endpoint, or
        /// an unparseable body.
        ///
        /// The distinction is the point. This error used to be constructed with a hardcoded `0`, so a
        /// cluster that had formed perfectly and merely could not be QUERIED reported
        /// "0/2 nodes healthy" — a count nobody had measured, indistinguishable from genuine total
        /// failure. Measured 2026-09-10 on a live 3-node cluster whose own log read
        /// `Quorum established — consensus available` at the moment this error was raised.
        record QuorumNotEstablished(int observed, int required) implements BootstrapError {
            /// The cluster view was never successfully read, so no count exists to report.
            public static final int UNOBSERVED = -1;

            @Override
            public String message() {
                return observed == UNOBSERVED
                       ? "Quorum not established: the cluster health view at /api/v1/health was never"
                        + " readable (unreachable, unauthorized, or unparseable), so the number of"
                        + " healthy nodes is UNKNOWN — not zero. Required: " + required
                       : "Quorum not established: " + observed + "/" + required + " nodes healthy";
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

        /// #994 — `cleanupDetail` now carries `BootstrapCleanup.CleanupError`'s enumeration (type + id per
        /// resource), so this message names what was left behind rather than saying "orphan resources may
        /// remain" and leaving the operator to reconstruct the list from `hcloud server list`.
        record BootstrapFailedWithOrphans(Cause originalCause, String cleanupDetail) implements BootstrapError {
            @Override
            public String message() {
                return originalCause.message() + " — cleanup failed, resources were left behind: " + cleanupDetail;
            }
        }

        /// #994 verification finding SF-1 — the failure-path cleanup could not run because the state file
        /// is present but unparseable. Distinct from "no state file", which is a success: there, nothing
        /// was created that needs reaping. Here, resources may exist and their ids are in bytes nothing can
        /// read, so this must surface as a cleanup FAILURE and reach the operator through
        /// [BootstrapFailedWithOrphans] rather than letting a torn file read as a clean teardown.
        record LedgerUnreadable(ClusterName clusterName, Cause origin) implements BootstrapError, Cause.Wrapped {
            @Override
            public String message() {
                return "the bootstrap state file for cluster '" + clusterName
                     + "' could not be read (" + origin.message()
                     + "), so cleanup could not name a single resource to reap — run"
                     + " 'tools/cloud-reaper.sh --cluster " + clusterName
                     + " --destroy' to finish teardown";
            }
        }
    }
}
