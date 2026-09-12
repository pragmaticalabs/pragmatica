// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;

import static org.pragmatica.aether.management.route.ManagementRoute.NODE_DRAIN;
import static org.pragmatica.aether.management.route.ManagementRoute.NODE_LIFECYCLE_GET;
import static org.pragmatica.aether.management.route.ManagementRoute.NODE_LIFECYCLE_LIST;
import static org.pragmatica.aether.management.route.ManagementRoute.NODE_SHUTDOWN;
import static org.pragmatica.lang.Option.option;


@Command(name = "destroy", description = "Destroy the active cluster (drain + shutdown all nodes)")
@SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01", "JBCT-SEQ-01"})
class ClusterDestroyCommand implements Callable<Integer> {
    /// Package-visible so a test asserts the announced ceiling against **the constant that enforces it**
    /// rather than against a restated literal — the same arrangement as
    /// [BootstrapCleanup#FIREWALL_DELETE_ATTEMPTS], and the reason #994's "servers are still detaching" is
    /// the cautionary case: an announcement that can drift from the code is a false diagnostic waiting to
    /// happen.
    static final int DRAIN_POLL_INTERVAL_MS = 2000;
    static final int DRAIN_TIMEOUT_SECONDS = 120;

    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    /// #994 verification finding SF-1 — carries `Result<Option<…>>` rather than `Option<…>`, so
    /// **an UNREADABLE ledger is distinguishable from an ABSENT one.** Under the old `Option` seam a torn
    /// `bootstrap-state.json` arrived as empty, which `cleanupCloudResources` read as "no bootstrap state
    /// — skipping resource cleanup", returned `true` for, and then removed the registry entry and exited 0
    /// over servers that were still billing. That is reachable by exactly the failure the incidents ended
    /// in: the operator killing bootstrap mid-write.
    ///
    /// `org.pragmatica.lang.Option` is spelled out because the simple name `Option` in this file is
    /// picocli's `@Option` annotation — the same reason the previous declaration was fully qualified.
    static Function<ClusterName, Result<org.pragmatica.lang.Option<BootstrapState>>> stateLoader = BootstrapStatePersistence::read;

    /// #997 — points at [#cleanupLedgerAroundVmSweep], not `BootstrapCleanup::cleanup`, so the
    /// cluster-labelled VM sweep runs INSIDE the ledger walk (between the last VM delete and the firewall
    /// delete) instead of after it. The seam's TYPE is unchanged, so every test that injects a
    /// `state -> Result` cleaner still does.
    static Function<BootstrapState, Result<Unit>> resourceCleaner = ClusterDestroyCommand::cleanupLedgerAroundVmSweep;

    /// #481 — cluster-scoped ssh-key sweep seam, called AFTER the state-based cleanup so recorded keys
    /// already deleted are tolerated. Deletes account keys named `aether-bootstrap-<cluster>-*` (the
    /// delimiter boundary) that `resourceCleaner` misses (reused / unrecorded keys). Injectable like
    /// `resourceCleaner`/`stateLoader` so tests exercise it without a real cloud call.
    static BiFunction<BootstrapState, ClusterName, Result<Unit>> sshKeySweeper = BootstrapCleanup::sweepClusterSshKeys;

    /// RFC-0017 stage 6 / C3 — cluster-scoped VM sweep seam. Reaps the cluster-labelled VMs the
    /// bootstrap state never recorded (stage-5 cluster-provisioned workers, auto-heal
    /// replacements); selector is built from the cluster name, never a bare filter.
    static BiFunction<BootstrapState, ClusterName, Result<Unit>> vmSweeper = BootstrapCleanup::sweepClusterVms;

    /// #521 — registry-removal seam, injectable like `resourceCleaner`/`stateLoader` so a test can assert
    /// that a FAILED cloud cleanup leaves the entry in place (the operator's only remaining handle on VMs
    /// that are still billing) without writing to the real `~/.aether/clusters.toml`.
    static BiFunction<ClusterRegistry, ClusterName, Result<ClusterRegistry>> registryRemover = ClusterDestroyCommand::removeRegistryEntry;

    @Option(names = "--yes", description = "Skip interactive confirmation")
    private boolean skipConfirmation;

    @Option(names = "--keep-resources", description = "Skip cloud resource termination (registry only)")
    private boolean keepResources;

    /// Raw picocli-bound text, deliberately NOT a [ClusterName]: picocli assigns it before any Aether
    /// code runs, so the parse happens in [#isOverrideAcceptable] / [#destroyCluster] where a rejection
    /// can be reported as a usage error.
    @Option(names = "--cluster", description = "Override active cluster — destroy named cluster instead (CLI > active-context)")
    private String clusterNameOverride;

    /// #998 — the opt-in for a destroy that CANNOT reach its own cluster. Enumeration failure used to be
    /// absorbed into an empty node list, which silently skipped DRAIN_NODES and SHUTDOWN_NODES and deleted
    /// the VMs anyway; an undrained destroy is now a refusal the operator has to override by name.
    @Option(names = "--force-undrained", description = "Destroy even if the cluster's nodes cannot be enumerated — VMs are deleted WITHOUT a graceful drain")
    private boolean forceUndrained;

    @Contract
    void setKeepResources(boolean value) {
        this.keepResources = value;
    }

    @Contract
    void setForceUndrained(boolean value) {
        this.forceUndrained = value;
    }

    @Contract
    void setClusterNameOverride(String value) {
        this.clusterNameOverride = value;
    }

    @Override
    public Integer call() {
        if (!isOverrideAcceptable()) {
            System.err.println("Invalid --cluster value: '" + clusterNameOverride
                              + "': must match ^[a-z]([a-z0-9-]{0,61}[a-z0-9])?$");

            return ExitCode.USAGE;
        }

        return ClusterRegistry.load()
                              .flatMap(this::executeDestroy)
                              .fold(ClusterDestroyCommand::onFailure, v -> v);
    }

    private boolean isOverrideAcceptable() {
        if (clusterNameOverride == null || clusterNameOverride.isBlank()) {
            return true;
        }

        return ClusterName.PATTERN.matcher(clusterNameOverride).matches();
    }

    private Result<Integer> executeDestroy(ClusterRegistry registry) {
        return resolveTarget(registry).flatMap(entry -> destroyCluster(registry, entry));
    }

    private Result<ClusterRegistry.ClusterEntry> resolveTarget(ClusterRegistry registry) {
        if (clusterNameOverride == null || clusterNameOverride.isBlank()) {
            return registry.current()
                           .toResult(ClusterHttpClient.HttpError.NO_ACTIVE_CLUSTER);
        }

        return Result.success(findOrSynthesizeEntry(registry, clusterNameOverride));
    }

    private static ClusterRegistry.ClusterEntry findOrSynthesizeEntry(ClusterRegistry registry, String name) {
        return registry.entries()
                       .stream()
                       .filter(e -> e.name()
                                     .equals(name))
                       .findFirst()
                       .orElseGet(() -> new ClusterRegistry.ClusterEntry(name,
                                                                         "",
                                                                         org.pragmatica.lang.Option.none()));
    }

    /// The registry entry's name is the LAST place a raw `String` cluster name survives on this path —
    /// `ClusterRegistry` is a persisted TOML index whose section keys are the names, and it stays
    /// `String`-typed. It is parsed HERE, immediately before it becomes a destroy selector, because
    /// everything downstream (`aether-cluster=<name>` VM sweep, `aether-bootstrap-<name>-` key sweep,
    /// the state-file path) is a scoping decision that must not run on an unparseable name.
    private Result<Integer> destroyCluster(ClusterRegistry registry, ClusterRegistry.ClusterEntry entry) {
        return ClusterName.clusterName(entry.name()).flatMap(clusterName -> destroyNamed(registry, entry, clusterName));
    }

    private Result<Integer> destroyNamed(ClusterRegistry registry,
                                         ClusterRegistry.ClusterEntry entry,
                                         ClusterName clusterName) {
        if (!skipConfirmation && !confirmDestruction(clusterName)) {
            System.out.println("Aborted.");

            return Result.success(ExitCode.ERROR);
        }

        var endpoint = installTargetOverrides(entry, clusterName);

        return performDestruction(registry, clusterName, endpoint);
    }

    /// #998 — points EVERY management call this destroy makes at the cluster being destroyed, and returns
    /// that target endpoint (or the refusal) so node enumeration can be skipped rather than misdirected.
    ///
    /// Both halves were cross-cluster leaks through [ClusterHttpClient]'s registry fallbacks, and the
    /// endpoint half is why fixing the missing port alone would NOT have made the observed command work:
    ///
    /// - **Endpoint.** A `--cluster X` whose registry entry is absent or blank got no override installed at
    ///   all, so `resolveEndpoint()` fell through to `registry.current()` — the ACTIVE cluster. Destroy
    ///   would then enumerate, DRAIN and SHUT DOWN a different, healthy cluster's nodes while reaping X's
    ///   cloud resources. `findOrSynthesizeEntry` exists so a cluster whose entry is already gone can still
    ///   have its resources reaped; it must not also borrow somebody else's nodes.
    /// - **API key.** `resolveApiKey()` reads `registry.current()`'s `api_key_env` too, so a
    ///   `--cluster X` destroy presented the ACTIVE cluster's credential to X and earned a 401. The target's
    ///   own key is installed here, file first (the same `~/.aether/clusters/<name>/api-key` path
    ///   [ClusterTargetMixin] installs) then its recorded `api_key_env`.
    ///
    /// Residual, stated rather than hidden: when the target has neither a key file nor an `api_key_env`,
    /// no override is installed and `resolveApiKey()` still falls back to the active cluster's key. That
    /// now surfaces as an enumeration failure and a refusal instead of a silently undrained destroy.
    private Result<String> installTargetOverrides(ClusterRegistry.ClusterEntry entry, ClusterName clusterName) {
        installTargetApiKey(entry, clusterName);

        return targetEndpoint(entry).onSuccess(ClusterHttpClient::setEndpointOverride);
    }

    private static Result<String> targetEndpoint(ClusterRegistry.ClusterEntry entry) {
        return option(entry.endpoint()).filter(endpoint -> !endpoint.isBlank())
                     .toResult(DestroyError.General.NO_TARGET_ENDPOINT);
    }

    @Contract
    private static void installTargetApiKey(ClusterRegistry.ClusterEntry entry, ClusterName clusterName) {
        ClusterTargetMixin.readApiKeyFile(clusterName.value())
                          .option()
                          .orElse(() -> recordedApiKey(entry))
                          .onPresent(ClusterHttpClient::setApiKeyOverride);
    }

    private static org.pragmatica.lang.Option<String> recordedApiKey(ClusterRegistry.ClusterEntry entry) {
        return entry.apiKeyEnv()
                    .flatMap(envName -> option(System.getenv(envName)));
    }

    /// #995 — against a healthy 3-node cloud cluster this emitted NOTHING for ~2.5 minutes and deleted
    /// nothing, and the operator killed it with three paid servers still running. Nothing here was
    /// announced: the first statement is a node-list HTTP request that can block for the whole
    /// [ClusterHttpClient#REQUEST_TIMEOUT] (130s by default) and whose failure was discarded by
    /// `.or(List.of())`; with an empty node list the drain and shutdown loops then print nothing either.
    /// So the command's entire observable output could begin more than two minutes in.
    ///
    /// Every phase now announces itself BEFORE it blocks, names the ceiling it may wait for, and reports
    /// its own failure. Silence is what invited the intervention that produced a half-destroyed cluster;
    /// an operator must be able to tell "working" from "wedged" without reading this file.
    ///
    /// #994 verification finding SF-3 — package-visible because **no test reached this method at all.**
    /// The three phase methods were each driven individually and every test entering through `call()`
    /// returned early (invalid `--cluster`, or an aborted confirmation), so deleting
    /// `announceDestroyPlan(clusterName)` from here — #995's entire "say so before the wait begins"
    /// deliverable — left all 724 tests green (measured, probe V3). The PHASE SEQUENCE was unpinned for the
    /// same reason: the order these five run in is the property the announcement describes, and nothing
    /// checked it.
    ///
    /// #998 — enumeration is now a GATE, not a best-effort first step. A failure routes to
    /// [#onEnumerationFailed] instead of being flattened into an empty node list, because the two states it
    /// used to merge have opposite consequences: a cluster that genuinely has no nodes needs no drain, and a
    /// cluster that cannot be reached needs one it cannot get.
    /// #1023 — the bootstrap ledger is read HERE, before the first management request, because both of the
    /// two observed failures are answered by data it already holds: every node's address (the endpoint
    /// SPOF) and the cluster secret (the trust anchor). Neither is new state; destroy already loaded this
    /// same ledger, but only in [#cleanupCloudResources], three phases too late to reach the cluster with.
    Result<Integer> performDestruction(ClusterRegistry registry, ClusterName clusterName, Result<String> endpoint) {
        announceDestroyPlan(clusterName);
        var state = recordedState(clusterName);

        prepareClusterTrust(endpoint, state);

        return fetchNodeIds(endpoint, siblingEndpoints(endpoint, state)).fold(_ -> onEnumerationFailed(registry,
                                                                                                       clusterName,
                                                                                                       endpoint),
                                                                              nodeIds -> destroyEnumerated(registry,
                                                                                                           clusterName,
                                                                                                           nodeIds));
    }

    /// An unreadable ledger arrives here as ABSENT, which is deliberate and is NOT a softening of #994's
    /// SF-1: [#cleanupCloudResources] re-reads the same file and still refuses to report cleanup done when
    /// it cannot be read. The two decisions are different. "I cannot derive a trust anchor or a fallback
    /// address" costs an enumeration attempt and ends in the #998 refusal; "I cannot tell which resources
    /// are still billing" must never end in a removed registry entry.
    private static org.pragmatica.lang.Option<BootstrapState> recordedState(ClusterName clusterName) {
        return stateLoader.apply(clusterName)
                          .or(org.pragmatica.lang.Option.none());
    }

    private Result<Integer> destroyEnumerated(ClusterRegistry registry, ClusterName clusterName, List<String> nodeIds) {
        var drainResults = drainAllNodes(nodeIds);
        var shutdownResults = shutdownAllNodes(nodeIds);
        var cleanupOk = cleanupCloudResources(clusterName);

        return finalizeDestruction(registry, clusterName, cleanupOk, nodeIds, drainResults, shutdownResults);
    }

    /// #998 expectation 2 — an enumeration failure stops the destroy BEFORE anything is deleted, so the
    /// cluster, its VMs and its registry entry all survive for a retry. `--force-undrained` is the operator
    /// saying the undrained teardown is what they want; it is not the default, because the default used to
    /// delete three paid VMs without draining any of them and report `Drains succeeded: 0/0`.
    private Result<Integer> onEnumerationFailed(ClusterRegistry registry,
                                                ClusterName clusterName,
                                                Result<String> endpoint) {
        if (forceUndrained) {
            System.out.println("  --force-undrained: continuing with an EMPTY node list. Drain and shutdown are"
                              + " skipped and the VMs are deleted without a graceful drain, as requested.");

            return destroyEnumerated(registry, clusterName, List.of());
        }

        return Result.success(refuseUndrainedDestroy(clusterName, endpoint));
    }

    private static int refuseUndrainedDestroy(ClusterName clusterName, Result<String> endpoint) {
        System.err.printf("  REFUSING to destroy '%s': its nodes could not be enumerated, so nothing can be"
                         + " drained or shut down. NOTHING has been deleted and the registry entry is kept —"
                         + " no VM, firewall, key or ledger was touched.%n",
                          clusterName);
        endpointDiagnostic(endpoint).onPresent(hint -> System.err.println("  " + hint));
        System.err.printf("  Repair the endpoint and re-run, or accept an undrained teardown explicitly:"
                         + " aether cluster destroy --cluster %s --yes --force-undrained%n",
                          clusterName);

        return ExitCode.ERROR;
    }

    /// #995 expectation 3 — "if it can take minutes, say so before the wait begins". The figures are read
    /// from the constants that actually bound the waits, so the estimate cannot drift away from the code.
    @Contract
    private void announceDestroyPlan(ClusterName clusterName) {
        System.out.printf("Destroying cluster '%s' in %d phases. This can take minutes: node enumeration"
                         + " waits up to %ds, each node's drain up to %ds, and cloud resource deletion is"
                         + " paced by the provider.%n",
                          clusterName,
                          DestroyPhase.values().length,
                          requestTimeoutSeconds(),
                          DRAIN_TIMEOUT_SECONDS);
    }

    /// #995 — the destroy pipeline's phases, printed in the same `[Phase n/m: NAME]` shape
    /// [ClusterBootstrapOrchestrator#logPhase] uses for bootstrap, so an operator reading a bootstrap
    /// transcript and a destroy transcript reads one format rather than two.
    enum DestroyPhase {
        ENUMERATE_NODES,
        DRAIN_NODES,
        SHUTDOWN_NODES,
        CLOUD_CLEANUP,
        REGISTRY
    }

    @Contract
    static void logPhase(DestroyPhase phase, String message) {
        System.out.printf("[Phase %d/%d: %s] %s%n",
                          phase.ordinal() + 1,
                          DestroyPhase.values().length,
                          phase.name(),
                          message);
    }

    /// The real ceiling on a single management request, read from [ClusterHttpClient#REQUEST_TIMEOUT]
    /// rather than restated: an announced timeout that does not match the one in force is the same class
    /// of false diagnostic as #994's "servers are still detaching".
    private static long requestTimeoutSeconds() {
        return option(ClusterHttpClient.REQUEST_TIMEOUT.get()).map(Duration::toSeconds)
                     .or(0L);
    }

    /// #521 — registry honesty. The registry entry is the operator's only handle on a cluster whose VMs may
    /// still be billing, so it is removed ONLY once cloud cleanup has actually succeeded; a failed cleanup
    /// keeps it so `aether cluster destroy` can simply be re-run. `--keep-resources` routes `cleanupOk` to
    /// true by design — skipping termination is the explicitly acknowledged path there, and removing the
    /// entry is correct.
    static Result<Integer> finalizeDestruction(ClusterRegistry registry,
                                               ClusterName clusterName,
                                               boolean cleanupOk,
                                               List<String> nodeIds,
                                               List<NodeResult> drainResults,
                                               List<NodeResult> shutdownResults) {
        if (!cleanupOk) {
            logPhase(DestroyPhase.REGISTRY,
                     "Keeping the registry entry — cloud cleanup failed, and the entry is the operator's"
                    + " remaining handle on resources that may still be billing");

            return Result.success(printSummary(clusterName, nodeIds, drainResults, shutdownResults, false, false));
        }

        logPhase(DestroyPhase.REGISTRY, "Removing the registry entry for '" + clusterName + "'");

        return registryRemover.apply(registry, clusterName)
                              .map(_ -> printSummary(clusterName, nodeIds, drainResults, shutdownResults, true, true));
    }

    boolean cleanupCloudResources(ClusterName clusterName) {
        logPhase(DestroyPhase.CLOUD_CLEANUP,
                 "Deleting cloud resources recorded at bootstrap, then sweeping cluster-labelled VMs and"
                + " SSH keys. Each delete is reported as it is issued; a firewall still in use is retried"
                + " up to " + BootstrapCleanup.FIREWALL_DELETE_ATTEMPTS
                + " times, " + (BootstrapCleanup.FIREWALL_DELETE_RETRY_MILLIS / 1000)
                + "s apart");
        if (keepResources) {
            System.out.println("--keep-resources: skipping cloud resource termination.");

            return true;
        }

        return stateLoader.apply(clusterName)
                          .map(state -> state.fold(() -> warnNoState(clusterName),
                                                   this::runCleanup))
                          .onFailure(cause -> warnUnreadableState(clusterName, cause))
                          .or(false);
    }

    /// #994 verification finding SF-1 — an unreadable ledger is a cleanup FAILURE, not an empty cluster.
    /// Returning `false` is what keeps the registry entry (#521's property: the entry is the operator's
    /// remaining handle on resources that may still be billing) and exits non-zero, so `destroy` can be
    /// re-run once the file is repaired or the reaper has finished the job. The alternative — the previous
    /// behaviour — was "destroyed successfully", exit 0, entry gone, servers running.
    @Contract
    private static void warnUnreadableState(ClusterName clusterName, Cause cause) {
        System.err.printf("  WARN: the bootstrap state file for cluster '%s' exists but cannot be read: %s%n",
                          clusterName,
                          cause.message());
        System.err.printf("  REFUSING to report cleanup as done: an unreadable ledger is NOT an empty one, and every"
                         + " resource it recorded may still be billing. The registry entry is kept so this can be"
                         + " retried. Finish teardown with: tools/cloud-reaper.sh --cluster %s --destroy%n",
                          clusterName);
    }

    private static boolean warnNoState(ClusterName clusterName) {
        System.out.printf("No bootstrap state for cluster '%s' — skipping resource cleanup.%n", clusterName);

        return true;
    }

    /// #997 — the VM sweep is no longer a step that follows the ledger cleanup; it runs INSIDE it, between
    /// the last VM-rank delete and the firewall delete (see [#cleanupLedgerAroundVmSweep]). It therefore no
    /// longer contributes its own boolean here: a sweep failure fails the cleanup it is part of.
    private boolean runCleanup(BootstrapState state) {
        var cleanupOk = runResourceCleanup(state);
        var sweepOk = runSshKeySweep(state);

        return cleanupOk && sweepOk;
    }

    /// #997 — the ledger walk with the cluster-labelled VM sweep wired into it, which is the whole fix:
    /// `resourceCleaner` deleted the firewall and the sweep ran afterwards, so a VM the ledger never
    /// recorded held the firewall through all 6 delete attempts, failed them, and was then swept away —
    /// stranding the firewall, the one resource the ordering existed to protect.
    ///
    /// RFC-0017 stage 6 / C3's constraint is preserved and is the reason the hook fires where it does
    /// rather than first: the ledger's VMs (the CORES among them) are deleted before it, so the leader's
    /// worker reconciler is already dead and cannot re-provision what the sweep reaps. Order is now
    /// cores -> label-swept VMs -> firewall -> keys, satisfying both constraints at once.
    private static Result<Unit> cleanupLedgerAroundVmSweep(BootstrapState state) {
        return BootstrapCleanup.cleanupWithVmSweep(state, () -> sweepClusterLabelledVms(state));
    }

    private static Result<Unit> sweepClusterLabelledVms(BootstrapState state) {
        return vmSweeper.apply(state,
                               state.clusterName())
                        .onFailure(c -> System.err.println("VM sweep failed: " + c.message()))
                        .onSuccess(_ -> System.out.println("VM sweep complete."));
    }

    private boolean runResourceCleanup(BootstrapState state) {
        if (state.createdResources().isEmpty()) {
            // #997 — the sweep still runs. An empty ledger is EXACTLY the state in which unrecorded VMs are
            // billing (#994's observed failure), so skipping the label sweep here would skip it in the one
            // case it was built for. There is no firewall in an empty ledger to order it against.
            System.out.println("Bootstrap state has no created resources — nothing to clean up from the"
                              + " ledger; the cluster-labelled VM sweep still runs.");

            return sweepClusterLabelledVms(state).isSuccess();
        }

        System.out.printf("Cleaning up %d created resources from bootstrap state...%n",
                          state.createdResources().size());

        return resourceCleaner.apply(state)
                              .onFailure(c -> System.err.println("Resource cleanup failed: " + c.message()))
                              .onSuccess(_ -> System.out.println("Resource cleanup complete."))
                              .isSuccess();
    }

    private boolean runSshKeySweep(BootstrapState state) {
        return sshKeySweeper.apply(state,
                                   state.clusterName())
                            .onFailure(c -> System.err.println("SSH-key sweep failed: " + c.message()))
                            .onSuccess(_ -> System.out.println("SSH-key sweep complete."))
                            .isSuccess();
    }

    private static boolean confirmDestruction(ClusterName clusterName) {
        System.out.printf("This will destroy cluster '%s' and shut down all nodes.%n", clusterName);
        var input = new org.pragmatica.aether.cli.Prompt().prompt("Type the cluster name to confirm", "");

        return clusterName.value()
                          .equals(input);
    }

    /// #1023 failure 2 — `SSLHandshakeException … PKIX path building failed`, observed live on a
    /// `[operations.tls] auto_generate = true` cluster, so drain and shutdown could not run at all.
    ///
    /// TRACED, not inferred: [ClusterHttpClient#enableClusterTrust] had exactly ONE caller,
    /// [ClusterBootstrapOrchestrator#configureClusterHttpClient], on the bootstrap path. Destroy never
    /// installed any `SSLContext`, so every request it made used the JDK default trust store — and the
    /// cluster's leaf certificates are signed by a CA derived from `cluster_secret` via HKDF
    /// ([ClusterTrust], [SelfSignedCertificateProvider]), which is not an anchor in that store. The thing
    /// that REFUSES the connection is the default `X509TrustManager`, not the cluster: there is no path
    /// from the presented leaf to any anchor it holds.
    ///
    /// The fix installs the SAME anchor bootstrap installs — the cluster's own CA and nothing else. This is
    /// not a relaxation: verification is strictly narrower than the JDK default, not wider, and
    /// [ClusterHttpClient#enableTlsSkipVerify] (trust-all) is deliberately NOT used here. #209 removed that
    /// shortcut from the bootstrap path for the same reason it must not reappear on this one — destroy
    /// presents the operator API key and issues shutdown commands over this channel.
    @Contract
    static void prepareClusterTrust(Result<String> endpoint, org.pragmatica.lang.Option<BootstrapState> state) {
        httpsEndpoint(endpoint).onPresent(_ -> installTrustForHttps(state));
    }

    /// `https` is the signal, and it is the one bootstrap itself writes:
    /// [BootstrapPhasePost#managementScheme] returns `https` exactly when `auto_generate` is true. An
    /// `http` endpoint needs no anchor, so the whole decision is skipped rather than guessed at.
    private static org.pragmatica.lang.Option<String> httpsEndpoint(Result<String> endpoint) {
        return endpoint.option()
                       .filter(value -> "https".equals(ClusterHttpClient.schemeOf(value)));
    }

    @Contract
    private static void installTrustForHttps(org.pragmatica.lang.Option<BootstrapState> state) {
        recordedClusterSecret(state).onPresent(ClusterDestroyCommand::installDerivedTrust)
                             .onEmpty(ClusterDestroyCommand::warnNoRecordedClusterSecret);
    }

    private static org.pragmatica.lang.Option<String> recordedClusterSecret(org.pragmatica.lang.Option<BootstrapState> state) {
        return state.map(BootstrapState::clusterSecret)
                    .filter(secret -> !secret.isBlank());
    }

    @Contract
    private static void installDerivedTrust(String clusterSecret) {
        System.out.println("  Trusting this cluster's own CA, derived from the cluster_secret recorded at"
                          + " bootstrap — the same derivation the nodes used to sign their certificates."
                          + " No other issuer is trusted for this connection.");
        ClusterHttpClient.enableClusterTrust(clusterSecret);
    }

    /// The honest half, and the reason this is a NOTE rather than a silent fallback: with no recorded
    /// secret there is no anchor to derive, and the alternative — trusting whatever certificate is
    /// presented — is what #209 removed. An operator reading a bare `PKIX path building failed` cannot
    /// tell those two states apart, so this names which one they are in BEFORE the request fails.
    @Contract
    private static void warnNoRecordedClusterSecret() {
        System.err.println("  NOTE: this cluster's endpoint is https, but no cluster_secret is recorded in its"
                          + " bootstrap state, so the CA that signed its certificates cannot be derived. If"
                          + " TLS was auto-generated, the next request will fail to build a trust path"
                          + " (PKIX). Certificate verification is deliberately NOT disabled to work around"
                          + " this.");
    }

    /// #1023 failure 1 — the registry records ONE node's address as the cluster endpoint
    /// ([BootstrapPhasePost#managementEndpoint] takes `addresses().getFirst()`), and teardown is exactly
    /// when that node is most likely to be the one that is already gone. Observed live on 2026-09-11: the
    /// entry named a node the operator had deliberately killed, enumeration got a `ConnectException`, and
    /// destroy refused — while three healthy nodes were serving the very same route.
    ///
    /// The addresses are NOT a new field. [BootstrapPhaseCollect] already records every provisioned node's
    /// public IP in `BootstrapState.collectedAddresses`, and destroy already loads that ledger. Only the
    /// scheme and port are borrowed from the recorded endpoint, so a deliberately port-less or proxied
    /// entry is REPRODUCED rather than second-guessed — #998's reason for refusing to default a port
    /// applies unchanged to the siblings built from it.
    ///
    /// Any live node can answer, and this is checked at the code that enforces it rather than at a
    /// docstring: [ManagementRoute#NODE_LIFECYCLE_LIST] is `LEADER`-targeted, and
    /// `ManagementServer.tryForwardIfNotLeader` FORWARDS the request when the receiving node is not the
    /// leader. `NODE_DRAIN` and `NODE_SHUTDOWN` forward the same way, which is why the endpoint that
    /// answers is kept as the override for the rest of the destroy.
    static List<String> siblingEndpoints(Result<String> endpoint, org.pragmatica.lang.Option<BootstrapState> state) {
        return endpoint.option()
                       .map(primary -> hostSubstitutedEndpoints(primary,
                                                                collectedAddresses(state)))
                       .or(List.of());
    }

    private static List<String> collectedAddresses(org.pragmatica.lang.Option<BootstrapState> state) {
        return state.map(BootstrapState::collectedAddresses)
                    .or(List.of());
    }

    private static List<String> hostSubstitutedEndpoints(String primary, List<String> addresses) {
        return parseEndpoint(primary).filter(ClusterDestroyCommand::hasHttpScheme)
                            .map(uri -> substituteHosts(uri, primary, addresses))
                            .or(List.of());
    }

    private static org.pragmatica.lang.Option<URI> parseEndpoint(String endpoint) {
        return Result.lift(Causes::fromThrowable,
                           () -> URI.create(endpoint))
                     .option();
    }

    private static boolean hasHttpScheme(URI uri) {
        return "http".equals(uri.getScheme()) || "https".equals(uri.getScheme());
    }

    private static List<String> substituteHosts(URI uri, String primary, List<String> addresses) {
        return addresses.stream()
                        .filter(address -> !address.isBlank())
                        .map(address -> endpointForHost(uri, address))
                        .filter(candidate -> !candidate.equals(primary))
                        .distinct()
                        .toList();
    }

    private static String endpointForHost(URI uri, String address) {
        return uri.getPort() < 0
               ? uri.getScheme() + "://" + address
               : uri.getScheme() + "://" + address + ":" + uri.getPort();
    }

    /// #995 — this is the call that produced the observed silence: one management request, up to
    /// [#requestTimeoutSeconds] before it gives up, and its failure was swallowed by `.or(List.of())`
    /// with no message at all. It now says what it is about to wait for and for how long, and reports a
    /// failure instead of continuing as if the cluster had no nodes.
    /// #998 — takes the destroy TARGET's endpoint rather than re-reading
    /// [ClusterHttpClient#resolveEndpoint], so the endpoint announced is the one the request will use and a
    /// target with no recorded endpoint short-circuits here instead of silently querying the active
    /// cluster. The `Result` is returned rather than flattened by `.or(List.of())`: that flattening is what
    /// made an unreachable cluster indistinguishable from an empty one.
    Result<List<String>> fetchNodeIds(Result<String> endpoint) {
        return fetchNodeIds(endpoint, List.of());
    }

    /// #1023 — the recorded endpoint first, then each sibling address in turn.
    Result<List<String>> fetchNodeIds(Result<String> endpoint, List<String> siblings) {
        logPhase(DestroyPhase.ENUMERATE_NODES, enumerationAnnouncement(endpoint, siblings));

        return firstSuccessfulEnumeration(endpoint, siblings).onFailure(cause -> warnNodeEnumerationFailed(cause,
                                                                                                           endpoint))
                                         .onSuccess(ClusterDestroyCommand::reportNodesFound);
    }

    /// The first candidate that answers KEEPS the endpoint override, so DRAIN and SHUTDOWN follow the node
    /// that proved reachable instead of the one the registry happens to name. Enumerating from a live node
    /// and then draining against a dead one would be a fix in name only.
    ///
    /// When every candidate fails, the PRIMARY endpoint's failure is the one returned and reported: it is
    /// the endpoint the operator recorded, and the siblings are a recovery path, not a redefinition of the
    /// target. This is also what keeps the #998 diagnostics — the port-less note, and the refusal itself —
    /// attached to the endpoint they are about.
    private static Result<List<String>> firstSuccessfulEnumeration(Result<String> endpoint, List<String> siblings) {
        var primary = endpoint.flatMap(_ -> listNodes());

        return primary.isSuccess() || siblings.isEmpty()
               ? primary
               : enumerateFromSiblings(primary, endpoint, siblings);
    }

    private static Result<List<String>> enumerateFromSiblings(Result<List<String>> primary,
                                                              Result<String> endpoint,
                                                              List<String> siblings) {
        announceSiblingFallback(primary, siblings);
        for (var candidate : siblings) {
            var attempt = enumerateFrom(candidate);

            if (attempt.isSuccess()) {
                return attempt;
            }
        }

        restorePrimaryEndpoint(endpoint);

        return primary;
    }

    private static Result<List<String>> enumerateFrom(String candidate) {
        System.out.printf("  Trying recorded node address %s...%n", candidate);
        ClusterHttpClient.setEndpointOverride(candidate);

        return listNodes().onSuccess(_ -> reportSiblingSucceeded(candidate))
                        .onFailure(cause -> reportSiblingFailed(candidate, cause));
    }

    @Contract
    private static void announceSiblingFallback(Result<List<String>> primary, List<String> siblings) {
        System.out.printf("  The recorded endpoint did not answer (%s). Trying %d other node address(es)"
                         + " recorded at bootstrap — any live node forwards this request to the leader.%n",
                          primary.fold(Cause::message, _ -> "no failure"),
                          siblings.size());
    }

    @Contract
    private static void reportSiblingSucceeded(String candidate) {
        System.out.printf("  %s answered. The rest of this destroy — drain and shutdown — will use it"
                         + " instead of the recorded endpoint.%n",
                          candidate);
    }

    @Contract
    private static void reportSiblingFailed(String candidate, Cause cause) {
        System.err.printf("  %s did not answer: %s%n", candidate, cause.message());
    }

    /// Every sibling failed, so the target goes back to what the operator recorded. Leaving the override
    /// pointing at the last address tried would make the refusal describe an endpoint nobody chose.
    @Contract
    private static void restorePrimaryEndpoint(Result<String> endpoint) {
        endpoint.onSuccess(ClusterHttpClient::setEndpointOverride);
    }

    private static String enumerationAnnouncement(Result<String> endpoint, List<String> siblings) {
        return siblings.isEmpty()
               ? String.format("Listing cluster nodes from %s (one request, timeout %ds)",
                               endpoint.or("<no endpoint resolved>"),
                               requestTimeoutSeconds())
               : String.format("Listing cluster nodes from %s (timeout %ds), falling back to %d other node"
                              + " address(es) recorded at bootstrap if it does not answer",
                               endpoint.or("<no endpoint resolved>"),
                               requestTimeoutSeconds(),
                               siblings.size());
    }

    private static Result<List<String>> listNodes() {
        return ClusterHttpClient.fetch(NODE_LIFECYCLE_LIST)
                                .flatMap(MAPPER::readTree)
                                .map(ClusterDestroyCommand::extractNodeIds);
    }

    @Contract
    private static void reportNodesFound(List<String> nodeIds) {
        System.out.printf("  %d node(s) reported by the cluster.%n", nodeIds.size());
    }

    /// Names the consequence, not just the error: with no node list the drain and shutdown phases have
    /// nothing to act on, so continuing would destroy the nodes WITHOUT a graceful drain.
    ///
    /// #998 — it no longer says "Proceeding to cloud resource cleanup", because that is no longer what
    /// happens: the caller refuses unless `--force-undrained` was given. A diagnostic that narrates the
    /// wrong next step is the class #994 ended.
    @Contract
    private static void warnNodeEnumerationFailed(Cause cause, Result<String> endpoint) {
        System.err.println("  WARN: could not list cluster nodes: " + cause.message());
        System.err.println("  Without a node list there is nothing to drain and nothing to shut down, so"
                          + " continuing would delete this cluster's VMs without a graceful drain.");
        endpointDiagnostic(endpoint).onPresent(hint -> System.err.println("  " + hint));
    }

    /// #998 — the OBSERVED shape of the endpoint, offered only when it is genuinely port-less. An endpoint
    /// with no explicit port sends the request to the scheme default — 443 under `https`, 80 under `http` —
    /// and an Aether management API listens on `operations.ports.management`, 8080 by default. Every entry
    /// written before the [BootstrapPhasePost#managementEndpoint] fix is port-less, so this is the line that
    /// turns a bare `ConnectException` into something an operator can act on. It reports what the endpoint
    /// IS and does not assert that the port is the cause.
    private static org.pragmatica.lang.Option<String> endpointDiagnostic(Result<String> endpoint) {
        return endpoint.option()
                       .flatMap(ClusterDestroyCommand::portlessEndpointNote);
    }

    private static org.pragmatica.lang.Option<String> portlessEndpointNote(String endpoint) {
        return Result.lift(Causes::fromThrowable,
                           () -> URI.create(endpoint).getPort())
                     .option()
                     .filter(port -> port < 0)
                     .map(_ -> "NOTE: the endpoint '" + endpoint
                              + "' carries NO explicit port, so the request"
                              + " went to this scheme's default (443 for https, 80 for http). An Aether"
                              + " management API listens on operations.ports.management, 8080 by default."
                              + " Clusters registered before the #998 fix have a port-less entry: add the port"
                              + " to ~/.aether/clusters.toml and re-run.");
    }

    private static List<String> extractNodeIds(JsonNode root) {
        var result = new ArrayList<String>();

        if (!root.isArray()) {
            return List.of();
        }

        for (var node : root) {
            var nodeId = node.path("nodeId").asText("");

            if (!nodeId.isEmpty()) {
                result.add(nodeId);
            }
        }

        return List.copyOf(result);
    }

    List<NodeResult> drainAllNodes(List<String> nodeIds) {
        logPhase(DestroyPhase.DRAIN_NODES, drainAnnouncement(nodeIds.size()));
        var results = new ArrayList<NodeResult>();

        for (var nodeId : nodeIds) {
            System.out.printf("Draining node %s (waiting up to %ds for DECOMMISSIONED, polling every %dms)...%n",
                              nodeId,
                              DRAIN_TIMEOUT_SECONDS,
                              DRAIN_POLL_INTERVAL_MS);
            var result = drainSingleNode(nodeId);

            results.add(result);
        }

        return List.copyOf(results);
    }

    private static String drainAnnouncement(int nodeCount) {
        return nodeCount == 0
               ? "Nothing to drain — the node list is empty"
               : String.format("Draining %d node(s), up to %ds each (worst case %ds total)",
                               nodeCount,
                               DRAIN_TIMEOUT_SECONDS,
                               (long) nodeCount * DRAIN_TIMEOUT_SECONDS);
    }

    private NodeResult drainSingleNode(String nodeId) {
        var drainResult = ClusterHttpClient.post(NODE_DRAIN, List.of(nodeId), "{}");

        if (drainResult.isFailure()) {
            System.err.printf("  Failed to drain %s: %s%n", nodeId, drainResult.fold(Cause::message, v -> v));

            return new NodeResult(nodeId, false);
        }

        var success = waitForDecommissioned(nodeId);

        if (success) {
            System.out.printf("  Node %s decommissioned.%n", nodeId);
        } else {
            System.err.printf("  Node %s did not decommission in time.%n", nodeId);
        }

        return new NodeResult(nodeId, success);
    }

    private static boolean waitForDecommissioned(String nodeId) {
        var deadline = System.currentTimeMillis() + (long) DRAIN_TIMEOUT_SECONDS * 1000;

        while (System.currentTimeMillis() < deadline) {
            var state = ClusterHttpClient.fetch(NODE_LIFECYCLE_GET,
                                                List.of(nodeId))
                                         .flatMap(MAPPER::readTree)
                                         .map(node -> node.path("state")
                                                          .asText("UNKNOWN"))
                                         .or("UNKNOWN");

            if ("DECOMMISSIONED".equals(state)) {
                return true;
            }

            sleepQuietly();
        }

        return false;
    }

    List<NodeResult> shutdownAllNodes(List<String> nodeIds) {
        logPhase(DestroyPhase.SHUTDOWN_NODES,
                 nodeIds.isEmpty()
                 ? "Nothing to shut down — the node list is empty"
                 : String.format("Shutting down %d node(s)", nodeIds.size()));
        var results = new ArrayList<NodeResult>();

        for (var nodeId : nodeIds) {
            System.out.printf("Shutting down node %s...%n", nodeId);
            var result = ClusterHttpClient.post(NODE_SHUTDOWN, List.of(nodeId), "{}");
            var success = result.isSuccess();

            if (!success) {
                System.err.printf("  Failed to shutdown %s.%n", nodeId);
            }

            results.add(new NodeResult(nodeId, success));
        }

        return List.copyOf(results);
    }

    private static Result<ClusterRegistry> removeRegistryEntry(ClusterRegistry registry, ClusterName name) {
        if (!registryContains(registry, name.value())) {
            return Result.success(registry);
        }

        return registry.remove(name.value())
                       .flatMap(updated -> updated.save()
                                                  .map(_ -> updated));
    }

    private static boolean registryContains(ClusterRegistry registry, String name) {
        return registry.entries()
                       .stream()
                       .anyMatch(e -> e.name()
                                       .equals(name));
    }

    private static int printSummary(ClusterName clusterName,
                                    List<String> nodeIds,
                                    List<NodeResult> drainResults,
                                    List<NodeResult> shutdownResults,
                                    boolean cleanupSucceeded,
                                    boolean registryEntryRemoved) {
        System.out.println();
        System.out.printf("Cluster '%s' destruction summary:%n", clusterName);
        System.out.printf("  Nodes processed: %d%n", nodeIds.size());
        System.out.printf("  Drains succeeded: %d/%d%s%n",
                          countSuccesses(drainResults),
                          drainResults.size(),
                          skippedNote(nodeIds));
        System.out.printf("  Shutdowns succeeded: %d/%d%s%n",
                          countSuccesses(shutdownResults),
                          shutdownResults.size(),
                          skippedNote(nodeIds));
        System.out.printf("  Cloud resource cleanup: %s%n",
                          cleanupSucceeded
                          ? "ok"
                          : "failed");
        System.out.printf("  Registry entry: %s%n",
                          registryEntryRemoved
                          ? "removed"
                          : "KEPT (cloud cleanup failed — retry 'aether cluster destroy --cluster " + clusterName
                           + " --yes')");
        var drainShutdownOk = countSuccesses(drainResults) == drainResults.size() && countSuccesses(shutdownResults) == shutdownResults.size();

        if (!cleanupSucceeded) {
            System.err.println("Warning: cloud resource cleanup failed; orphan resources may remain. "
                              + "Run 'tools/cloud-reaper.sh --cluster " + clusterName
                              + " --destroy' to clean up.");

            return ExitCode.CLEANUP_FAILED;
        }

        if (!drainShutdownOk) {
            System.err.println("Warning: some drain/shutdown operations failed. Check output above.");

            return ExitCode.ERROR;
        }

        System.out.printf("Cluster '%s' destroyed successfully.%n", clusterName);

        return ExitCode.SUCCESS;
    }

    /// #1023 — `Drains succeeded: 0/0` was the ENTIRE observable outcome of a destroy that drained nothing,
    /// and a ratio whose denominator is zero reads like a pass. It is a SKIP. The number is unchanged and
    /// still honest; what was missing is which of the two it describes, so the summary now says so rather
    /// than leaving the reader to notice that `0/0` is not `3/3`.
    private static String skippedNote(List<String> nodeIds) {
        return nodeIds.isEmpty()
               ? "  (SKIPPED — no nodes were enumerated, so nothing was drained or shut down)"
               : "";
    }

    private static long countSuccesses(List<NodeResult> results) {
        return results.stream()
                      .filter(NodeResult::success)
                      .count();
    }

    @SuppressWarnings("JBCT-EX-01")
    private static void sleepQuietly() {
        try {
            Thread.sleep(DRAIN_POLL_INTERVAL_MS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());

        return ExitCode.ERROR;
    }

    record NodeResult(String nodeId, boolean success) {}

    /// #998 — destroy's own refusals, as causes rather than as printed text, so the enumeration gate can
    /// FAIL for a reason instead of producing an empty list that looks like a healthy empty cluster.
    sealed interface DestroyError extends Cause {
        enum General implements DestroyError {
            NO_TARGET_ENDPOINT("no endpoint is recorded for this cluster, so its nodes cannot be enumerated."
                              + " The active cluster's endpoint is deliberately NOT used as a fallback:"
                              + " draining and shutting down a different cluster's nodes is worse than not"
                              + " draining this one's");
            private final String message;
            General(String message) {
                this.message = message;
            }
            @Override
            public String message() {
                return message;
            }
        }
    }
}
