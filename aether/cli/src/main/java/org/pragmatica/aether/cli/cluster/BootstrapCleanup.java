// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;

import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.cloud.hetzner.HetznerClient;
import org.pragmatica.cloud.hetzner.HetznerConfig;
import org.pragmatica.cloud.hetzner.HetznerError;
import org.pragmatica.cloud.hetzner.api.Server;
import org.pragmatica.cloud.hetzner.api.SshKey;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
sealed interface BootstrapCleanup {
    record unused() implements BootstrapCleanup {}

    /// Handle credential alias for the cloud API token (see `BootstrapPhaseProvision.CREDENTIAL_FIELD_KEYS`).
    String API_TOKEN_KEY = "api_token";
    /// Provider identity for Hetzner cloud (matches `SourceCleanupHandle.provider()` and `CreatedResource.provider()`).
    String HETZNER_PROVIDER = "hetzner";
    /// #994 verification finding SF-4 — the `role` component of a label-swept VM's synthesized
    /// [CreatedResource.ProvisionedVm]. A swept VM is by definition one the ledger never recorded, so its
    /// role is genuinely unknown; this marks the absence instead of guessing a plausible value that an
    /// operator would read as recorded fact.
    String SWEPT_ROLE = "label-swept";

    /// RFC-0016 W4 (#439) — the injectable seams cleanup uses to resolve teardown credentials, so a
    /// timeout-triggered cleanup reaps VMs *and* ssh keys with the SAME credential the operator used to
    /// provision (re-derived from the persisted `SourceCleanupHandle`), never a hard-coded `HCLOUD_TOKEN`.
    ///
    /// - `cloudComputeFallback` / `hetznerClientFallback` — raw-env last resort (provider name → provider),
    ///   reached ONLY when no persisted handle exists (bootstrap did not record one).
    /// - `handleComputeResolver` — VM reaping via the persisted handle (already the right thing pre-W4).
    /// - `getenv` — env-var NAME → value, so the handle's recorded name (e.g. `HCLOUD_TOKEN_PROD`) is read.
    /// - `hetznerClientFactory` — token → `HetznerClient` (infallible build), the seam a test stubs so no
    ///   real cloud call runs; the env-read failure is handled before it is invoked.
    record CleanupResolvers(Fn1<Result<ComputeProvider>, String> cloudComputeFallback,
                            Fn1<Result<ComputeProvider>, SourceCleanupHandle> handleComputeResolver,
                            Fn1<Result<HetznerClient>, String> hetznerClientFallback,
                            Fn1<String, String> getenv,
                            Fn1<HetznerClient, String> hetznerClientFactory,
                            LongConsumer sleeper,
                            Fn2<Result<Unit>, ClusterName, CreatedResource> ledgerRecorder) {
        static CleanupResolvers cleanupResolvers() {
            return new CleanupResolvers(ProviderResolver::resolveCloudComputeForCleanup,
                                        ProviderResolver::resolveCloudComputeFromHandle,
                                        BootstrapCleanup::defaultHetznerClient,
                                        System::getenv,
                                        BootstrapCleanup::hetznerClientFromToken,
                                        BootstrapCleanup::sleepQuietly,
                                        BootstrapStatePersistence::appendResource);
        }

        /// Test seam: a no-op sleeper makes the retry loop instant.
        CleanupResolvers withSleeper(LongConsumer newSleeper) {
            return new CleanupResolvers(cloudComputeFallback,
                                        handleComputeResolver,
                                        hetznerClientFallback,
                                        getenv,
                                        hetznerClientFactory,
                                        newSleeper,
                                        ledgerRecorder);
        }

        CleanupResolvers withCloudComputeFallback(Fn1<Result<ComputeProvider>, String> resolver) {
            return new CleanupResolvers(resolver,
                                        handleComputeResolver,
                                        hetznerClientFallback,
                                        getenv,
                                        hetznerClientFactory,
                                        sleeper,
                                        ledgerRecorder);
        }

        CleanupResolvers withHandleComputeResolver(Fn1<Result<ComputeProvider>, SourceCleanupHandle> resolver) {
            return new CleanupResolvers(cloudComputeFallback,
                                        resolver,
                                        hetznerClientFallback,
                                        getenv,
                                        hetznerClientFactory,
                                        sleeper,
                                        ledgerRecorder);
        }

        CleanupResolvers withHetznerClientFallback(Fn1<Result<HetznerClient>, String> resolver) {
            return new CleanupResolvers(cloudComputeFallback,
                                        handleComputeResolver,
                                        resolver,
                                        getenv,
                                        hetznerClientFactory,
                                        sleeper,
                                        ledgerRecorder);
        }

        CleanupResolvers withGetenv(Fn1<String, String> lookup) {
            return new CleanupResolvers(cloudComputeFallback,
                                        handleComputeResolver,
                                        hetznerClientFallback,
                                        lookup,
                                        hetznerClientFactory,
                                        sleeper,
                                        ledgerRecorder);
        }

        CleanupResolvers withHetznerClientFactory(Fn1<HetznerClient, String> factory) {
            return new CleanupResolvers(cloudComputeFallback,
                                        handleComputeResolver,
                                        hetznerClientFallback,
                                        getenv,
                                        factory,
                                        sleeper,
                                        ledgerRecorder);
        }

        /// #1022 test seam. The default writes the REAL `~/.aether/clusters/<name>/bootstrap-state.json`,
        /// resolved from `user.home` at class load, so a sweep test running under a borrowed cluster name
        /// would append records to an operator's genuine ledger. Injecting the recorder keeps the
        /// assertion on WHAT the sweep offers the ledger without any test touching the owner's home.
        CleanupResolvers withLedgerRecorder(Fn2<Result<Unit>, ClusterName, CreatedResource> recorder) {
            return new CleanupResolvers(cloudComputeFallback,
                                        handleComputeResolver,
                                        hetznerClientFallback,
                                        getenv,
                                        hetznerClientFactory,
                                        sleeper,
                                        recorder);
        }
    }

    static Result<Unit> cleanup(BootstrapState state) {
        return cleanupWith(state, CleanupResolvers.cleanupResolvers());
    }

    /// #997 — teardown entry for `aether cluster destroy`, which must reap the cluster-labelled VMs the
    /// ledger never recorded BEFORE the firewall delete is attempted. See [#SWEEP_BEFORE_RANK].
    static Result<Unit> cleanupWithVmSweep(BootstrapState state, Fn0<Result<Unit>> preFirewallSweep) {
        return cleanupWith(state, CleanupResolvers.cleanupResolvers(), Option.some(preFirewallSweep));
    }

    static Result<Unit> cleanup(BootstrapState state, Fn1<Result<ComputeProvider>, String> cloudComputeResolver) {
        return cleanupWith(state,
                           CleanupResolvers.cleanupResolvers().withCloudComputeFallback(cloudComputeResolver));
    }

    static Result<Unit> cleanup(BootstrapState state,
                                Fn1<Result<ComputeProvider>, String> cloudComputeResolver,
                                Fn1<Result<HetznerClient>, String> hetznerClientResolver) {
        return cleanupWith(state,
                           CleanupResolvers.cleanupResolvers()
                                           .withCloudComputeFallback(cloudComputeResolver)
                                           .withHetznerClientFallback(hetznerClientResolver));
    }

    /// RFC-0016 W4 — full-control entry for the money-path regression test: injects the handle-derived
    /// VM resolver, the env lookup, and the token → HetznerClient factory so both reaps are observable
    /// without a real cloud call. Raw-env fallbacks keep their production defaults (never reached when a
    /// handle is present).
    static Result<Unit> cleanup(BootstrapState state,
                                Fn1<Result<ComputeProvider>, SourceCleanupHandle> handleComputeResolver,
                                Fn1<String, String> getenv,
                                Fn1<HetznerClient, String> hetznerClientFactory) {
        return cleanupWith(state,
                           CleanupResolvers.cleanupResolvers()
                                           .withHandleComputeResolver(handleComputeResolver)
                                           .withGetenv(getenv)
                                           .withHetznerClientFactory(hetznerClientFactory));
    }

    /// Full-control entry: every resolver seam is caller-supplied, so a test can drive the demoted raw-env
    /// fallback (#521) — which the positional overloads above cannot inject alongside the handle seams —
    /// without any real cloud call.
    static Result<Unit> cleanupWith(BootstrapState state, CleanupResolvers resolvers) {
        return cleanupWith(state, resolvers, Option.none());
    }

    /// #997 — `preFirewallSweep` runs INSIDE the rank walk, exactly once, at [#SWEEP_BEFORE_RANK].
    ///
    /// It is a parameter rather than a [CleanupResolvers] field because that record is the credential
    /// seams, and it is ABSENT rather than a no-op on the bootstrap path for two separate reasons:
    ///
    /// - [#sweepClusterVms] REFUSES a cluster in [#PROTECTED_CLUSTERS], and a refusal is a `Result` failure.
    ///   Wiring the sweep in unconditionally would have made every BOOTSTRAP rollback of a protected cluster
    ///   fail on a sweep it never asked for — a new failure mode on the money path, to fix a destroy-path
    ///   ordering bug. Absence keeps that off the bootstrap path by construction, not by a guard.
    /// - An `Option` rather than a no-op `Fn0` because [VmAccounting] REPORTS whether a sweep ran. A no-op
    ///   that returns success is indistinguishable from a real sweep that succeeded, and the firewall
    ///   diagnostic then claims "the VM sweep ran and reported success" on a path where no sweep exists —
    ///   the #994 false-diagnostic class again. The absent case is [SweepVerdict#NOT_RUN].
    static Result<Unit> cleanupWith(BootstrapState state,
                                    CleanupResolvers resolvers,
                                    Option<Fn0<Result<Unit>>> preFirewallSweep) {
        System.out.println("Cleaning up resources for cluster '" + state.clusterName() + "'...");
        var resources = new ArrayList<>(state.createdResources());

        Collections.reverse(resources);
        var outcome = collectCleanupFailures(state, resources, resolvers, preFirewallSweep);

        return finishCleanup(state, outcome);
    }

    private static Result<Unit> runSweep(Option<Fn0<Result<Unit>>> preFirewallSweep) {
        return preFirewallSweep.map(Fn0::apply)
                               .or(Result.unitResult());
    }

    /// #481 — defensive cluster-scoped ssh-key sweep. `cleanup` above deletes only keys the state recorded
    /// as a `SshKeyResource` (exact id); a *reused* pre-existing key, or one the state never captured,
    /// orphans on the Hetzner account and gets re-matched by a same-name recreate's prefix listing. This
    /// sweep lists the account's keys and deletes those scoped to THIS cluster by the delimiter-bounded name
    /// prefix `aether-bootstrap-<cluster>-` — the SAME boundary `HetznerComputeProvider.isBootstrapKey` uses
    /// (post-#444), so a `prod` destroy never touches `production`'s keys. Runs AFTER the state-based cleanup,
    /// so an already-recorded-and-deleted key surfaces as a tolerated 404/`not_found`.
    static Result<Unit> sweepClusterSshKeys(BootstrapState state, ClusterName clusterName) {
        return sweepClusterSshKeys(state, clusterName, CleanupResolvers.cleanupResolvers());
    }

    /// Full-control entry for the #481 sweep test: injects the env lookup and the token → HetznerClient
    /// factory so the sweep is observable without a real cloud call. The Hetzner client is still resolved
    /// handle-first (from the persisted hetzner `SourceCleanupHandle`), never raw `HCLOUD_TOKEN`.
    static Result<Unit> sweepClusterSshKeys(BootstrapState state,
                                            ClusterName clusterName,
                                            Fn1<String, String> getenv,
                                            Fn1<HetznerClient, String> hetznerClientFactory) {
        return sweepClusterSshKeys(state,
                                   clusterName,
                                   CleanupResolvers.cleanupResolvers()
                                                   .withGetenv(getenv)
                                                   .withHetznerClientFactory(hetznerClientFactory));
    }

    // The blank-name skip that used to open this method is gone: `clusterName` is a `ClusterName`,
    // so an unscopeable sweep is unrepresentable here rather than guarded against.
    @SuppressWarnings("JBCT-PAT-01")
    static Result<Unit> sweepClusterSshKeys(BootstrapState state, ClusterName clusterName, CleanupResolvers resolvers) {
        var handle = state.sources()
                          .values()
                          .stream()
                          .filter(candidate -> HETZNER_PROVIDER.equals(candidate.provider()))
                          .findFirst()
                          .orElse(null);

        if (handle == null) {
            System.out.println("  Skipping SSH-key sweep: no persisted Hetzner source handle for cluster '" + clusterName
                              + "' (nothing cloud-scoped to sweep).");

            return Result.unitResult();
        }

        var prefix = BootstrapPhaseSshKey.HETZNER_KEY_NAME_PREFIX + "-" + clusterName + "-";

        System.out.println("Sweeping orphaned Hetzner SSH keys scoped to cluster '" + clusterName
                          + "' (prefix '" + prefix
                          + "')...");

        return hetznerClientFromHandle(handle, resolvers).flatMap(client -> sweepWithClient(client, prefix, clusterName));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<Unit> sweepWithClient(HetznerClient client, String prefix, ClusterName clusterName) {
        return client.listSshKeys()
                     .await()
                     .flatMap(keys -> deleteMatchingKeys(client, keys, prefix, clusterName));
    }

    private static Result<Unit> deleteMatchingKeys(HetznerClient client,
                                                   List<SshKey> keys,
                                                   String prefix,
                                                   ClusterName clusterName) {
        var matches = keys.stream().filter(key -> keyNameMatches(key, prefix)).toList();

        if (matches.isEmpty()) {
            System.out.println("  No cluster-scoped SSH keys found to sweep.");

            return Result.unitResult();
        }

        var failures = collectSweepFailures(client, matches);

        return finishSweep(clusterName, matches.size(), failures);
    }

    private static boolean keyNameMatches(SshKey key, String prefix) {
        return key.name() != null && key.name()
                                        .startsWith(prefix);
    }

    /// #994 verification finding SF-4 — failures are [ReapFailure]s over a real [CreatedResource], not
    /// joined strings, so the key sweep enumerates what it left behind exactly as the ledger-driven cleanup
    /// does. [CreatedResource.SshKeyResource] is an exact fit for a swept key: provider, id and name are all
    /// known, so nothing here is synthesized or approximated.
    private static List<ReapFailure> collectSweepFailures(HetznerClient client, List<SshKey> matches) {
        var failures = new ArrayList<ReapFailure>();

        for (var key : matches) {
            var result = deleteSweptKey(client, key);
            var _ = result.onFailure(cause -> failures.add(new ReapFailure(sweptKeyResource(key), cause)));
        }

        return List.copyOf(failures);
    }

    private static CreatedResource sweptKeyResource(SshKey key) {
        return CreatedResource.SshKeyResource.sshKeyResource(HETZNER_PROVIDER, key.id(), key.name());
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<Unit> deleteSweptKey(HetznerClient client, SshKey key) {
        System.out.printf("  Sweeping orphaned SSH key %d (%s)...%n", key.id(), key.name());

        return client.deleteSshKey(key.id())
                     .await()
                     .fold(cause -> tolerateAlreadyGone(cause, key),
                           _ -> logSwept(key));
    }

    private static Result<Unit> logSwept(SshKey key) {
        System.out.printf("    deleted SSH key %d.%n", key.id());

        return Result.unitResult();
    }

    private static Result<Unit> tolerateAlreadyGone(Cause cause, SshKey key) {
        if (isAlreadyGone(cause)) {
            System.out.printf("    SSH key %d already gone — tolerated.%n", key.id());

            return Result.unitResult();
        }

        System.err.printf("    WARN: failed to delete SSH key %d (%s): %s%n", key.id(), key.name(), cause.message());

        return cause.result();
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static boolean isAlreadyGone(Cause cause) {
        return switch (cause) {
            case HetznerError.ApiError apiError -> apiError.statusCode() == 404 || "not_found".equalsIgnoreCase(apiError.code());
            default -> false;
        };
    }

    /// RFC-0017 stage 6 / C3 — clusters whose VMs `aether cluster destroy` must NEVER sweep, no
    /// matter what an operator types. Mirrors `tools/cloud-reaper.sh`'s `PROTECTED_CLUSTERS`
    /// (added after #572, when a bare reap deleted the standing test-pg PostgreSQL VM): protection
    /// lives in the TOOL, not the call site.
    static final Set<String> PROTECTED_CLUSTERS = Set.of("test-pg");

    /// RFC-0017 stage 6 / C3 — defensive cluster-scoped VM sweep. The state-based cleanup above
    /// terminates only VMs the bootstrap state RECORDED; cluster-provisioned nodes (stage-5
    /// workers, auto-heal replacements) are not in that state and would orphan as PAID VMs no
    /// destroy could find. This sweep lists servers by `aether-cluster=<name>` — the selector is
    /// built from the cluster name, scoped BY CONSTRUCTION, never a caller-supplied filter (#572
    /// is what a bare reap costs) — prints the inventory it is about to delete, then deletes,
    /// tolerating 404s for VMs the state-based pass already removed.
    ///
    /// Ordering is load-bearing: this runs AFTER the state-based cleanup, so the CORES die first —
    /// which kills the leader's worker reconciler — and nothing re-provisions the workers this
    /// sweep reaps. Run before core death, a live leader would see a worker deficit and replace
    /// them mid-destroy.
    static Result<Unit> sweepClusterVms(BootstrapState state, ClusterName clusterName) {
        return sweepClusterVms(state, clusterName, CleanupResolvers.cleanupResolvers());
    }

    /// Full-control entry for tests: injects the env lookup, the token → HetznerClient factory and the
    /// ledger recorder, mirroring the #481 SSH-key sweep seams. Handle-first credential resolution —
    /// never raw `HCLOUD_TOKEN`.
    ///
    /// #1022 — `ledgerRecorder` is a REQUIRED parameter here rather than defaulting, because the default
    /// writes the real `~/.aether/clusters/<name>/bootstrap-state.json` (`AETHER_DIR` is resolved from
    /// `user.home` at class load and cannot be redirected). These tests sweep under borrowed cluster
    /// names like `prod`; a default would let a green test append records to an operator's genuine
    /// ledger on any machine that happens to have one. Making it explicit removes that by construction
    /// instead of by nobody having such a ledger today.
    static Result<Unit> sweepClusterVms(BootstrapState state,
                                        ClusterName clusterName,
                                        Fn1<String, String> getenv,
                                        Fn1<HetznerClient, String> hetznerClientFactory,
                                        Fn2<Result<Unit>, ClusterName, CreatedResource> ledgerRecorder) {
        return sweepClusterVms(state,
                               clusterName,
                               CleanupResolvers.cleanupResolvers()
                                               .withGetenv(getenv)
                                               .withHetznerClientFactory(hetznerClientFactory)
                                               .withLedgerRecorder(ledgerRecorder));
    }

    // The blank-name skip that used to open this method is gone: `clusterName` is a `ClusterName`,
    // so an unscopeable sweep — the one that would have swept the whole account — is unrepresentable
    // here rather than guarded against.
    @SuppressWarnings("JBCT-PAT-01")
    static Result<Unit> sweepClusterVms(BootstrapState state, ClusterName clusterName, CleanupResolvers resolvers) {
        if (PROTECTED_CLUSTERS.contains(clusterName.value())) {
            return Causes.cause("Refusing to sweep VMs of protected cluster '" + clusterName
                               + "' — it hosts long-lived shared infrastructure (see tools/cloud-reaper.sh"
                               + " PROTECTED_CLUSTERS and incident #572). Remove its resources manually if you"
                               + " really mean it.").result();
        }

        var handle = state.sources()
                          .values()
                          .stream()
                          .filter(candidate -> HETZNER_PROVIDER.equals(candidate.provider()))
                          .findFirst()
                          .orElse(null);

        if (handle == null) {
            System.out.println("  Skipping VM sweep: no persisted Hetzner source handle for cluster '" + clusterName
                              + "' (nothing cloud-scoped to sweep).");

            return Result.unitResult();
        }

        var selector = "aether-cluster=" + clusterName;

        System.out.println("Sweeping cluster-labelled VMs (selector '" + selector + "')...");

        return hetznerClientFromHandle(handle, resolvers).flatMap(client -> sweepVmsWithClient(client,
                                                                                               selector,
                                                                                               clusterName,
                                                                                               recordedVmIds(state),
                                                                                               resolvers));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<Unit> sweepVmsWithClient(HetznerClient client,
                                                   String selector,
                                                   ClusterName clusterName,
                                                   Set<String> recordedVmIds,
                                                   CleanupResolvers resolvers) {
        return client.listServers(selector)
                     .await()
                     .flatMap(servers -> deleteSweptServers(client, servers, clusterName, recordedVmIds, resolvers));
    }

    /// The provider-assigned ids the ledger ALREADY names, so the sweep can tell a bootstrap-minted VM
    /// (recorded at creation by `BootstrapPhaseProvision.recordProvisionedVm`) from one that reached the
    /// cloud account by a route the ledger never saw. Read from the in-memory teardown snapshot, which is
    /// the state `ClusterDestroyCommand` loaded from the file and is not mutated by the cleanup walk.
    private static Set<String> recordedVmIds(BootstrapState state) {
        return state.createdResources()
                    .stream()
                    .filter(CreatedResource.ProvisionedVm.class::isInstance)
                    .map(CreatedResource.ProvisionedVm.class::cast)
                    .map(CreatedResource.ProvisionedVm::resourceId)
                    .collect(Collectors.toUnmodifiableSet());
    }

    /// #994 verification finding SF-4 — failures are [ReapFailure]s, so the VM sweep's leftovers are
    /// enumerated by type and id like the ledger-driven cleanup's. This is the sharper half of that finding:
    /// the sweep exists precisely to catch **billable VMs the ledger never recorded**, which is #994's whole
    /// theme, and its failure message used to be a joined string with no type and no id.
    private static Result<Unit> deleteSweptServers(HetznerClient client,
                                                   List<Server> servers,
                                                   ClusterName clusterName,
                                                   Set<String> recordedVmIds,
                                                   CleanupResolvers resolvers) {
        if (servers.isEmpty()) {
            System.out.println("  No cluster-labelled VMs found to sweep.");

            return Result.unitResult();
        }

        printVmInventory(servers);
        recordSweptVms(servers, clusterName, recordedVmIds, resolvers);
        var failures = new ArrayList<ReapFailure>();

        for (var server : servers) {
            var result = deleteSweptServer(client, server);
            var _ = result.onFailure(cause -> failures.add(new ReapFailure(sweptVmResource(server, clusterName), cause)));
        }

        return finishVmSweep(clusterName, servers.size(), List.copyOf(failures));
    }

    /// A label-swept VM is a [CreatedResource.ProvisionedVm] the ledger never held, so two of the record's
    /// four components have no recorded value: the role is literally unknown here (that absence is WHY this
    /// sweep exists) and is marked [#SWEPT_ROLE] rather than guessed, and the source slot carries the cluster
    /// the selector scoped to. The id is the component that matters — it is what `hcloud server delete` and
    /// `tools/cloud-reaper.sh` act on — and the server's NAME is already on the adjacent per-server WARN line
    /// plus the sweep inventory, so nothing an operator needs is only in one place.
    private static CreatedResource sweptVmResource(Server server, ClusterName clusterName) {
        return CreatedResource.ProvisionedVm.provisionedVm(HETZNER_PROVIDER,
                                                           String.valueOf(server.id()),
                                                           clusterName.value(),
                                                           SWEPT_ROLE);
    }

    /// #1022 — write the VMs the ledger does NOT already name into it, BEFORE any of them is deleted.
    ///
    /// **The gap this closes is structural, not a missed call site.** A CTM auto-heal replacement is
    /// created by the cluster LEADER, in `aether-deployment`, running on a VM in the cloud; the ledger is
    /// `~/.aether/clusters/<name>/bootstrap-state.json` on the OPERATOR'S machine, written by `aether/cli`
    /// — a different process on a different host, in a module `cli` depends on but which cannot depend on
    /// `cli` back. Auto-heal therefore cannot append to the ledger at creation time by ANY wiring, so no
    /// missing call is the defect: the ledger is structurally incapable of naming a replacement. Observed
    /// 2026-09-11 — five replacements the ledger never held, four more VMs than teardown knew about.
    ///
    /// So the record is made at the first moment an operator-side process can make it, and that is LATE.
    /// Saying so is part of the fix: this does not give the operator a live inventory while the cluster
    /// runs, and a cluster reaped by any route that does not run this sweep still leaves the ledger
    /// silent. What it does buy is #994's property extended to auto-healed VMs — **if this teardown then
    /// fails part-way, the ledger NAMES the servers that are still billing**, which is exactly the state
    /// in which the ledger is the only handle an operator has left. [#finishCleanup] deletes the state
    /// file on a fully clean teardown, so on the success path these records live only as long as they
    /// could be useful.
    ///
    /// Per-VM rather than one batched write, so a crash mid-loop still leaves every earlier id on disk.
    /// A failure to record never fails the sweep: the server is billing either way, and refusing to
    /// delete it because we could not write a note about it would turn a full disk into an un-reapable
    /// cluster.
    @Contract
    private static void recordSweptVms(List<Server> servers,
                                       ClusterName clusterName,
                                       Set<String> recordedVmIds,
                                       CleanupResolvers resolvers) {
        var unrecorded = servers.stream()
                                .filter(server -> !recordedVmIds.contains(String.valueOf(server.id())))
                                .toList();

        if (unrecorded.isEmpty()) {
            System.out.println("  Every swept VM is already named in the cleanup ledger.");

            return;
        }

        System.out.printf("  Recording %d swept VM(s) the ledger never held:%n", unrecorded.size());
        for (var server : unrecorded) {
            recordSweptVm(server, clusterName, resolvers);
        }
    }

    @Contract
    private static void recordSweptVm(Server server, ClusterName clusterName, CleanupResolvers resolvers) {
        var _ = resolvers.ledgerRecorder()
                         .apply(clusterName,
                                sweptVmResource(server, clusterName))
                         .onSuccess(_ -> System.out.printf("    + %s (id=%d)%n",
                                                           server.name(),
                                                           server.id()))
                         .onFailure(cause -> warnSweptVmNotRecorded(server,
                                                                    clusterName,
                                                                    cause.message()));
    }

    /// Mirrors [BootstrapPhaseProvision#warnVmNotRecorded]: with the ledger unwritable, this line is the
    /// only place the server is named at all, so it goes to stderr rather than a log level nobody reads.
    @Contract
    private static void warnSweptVmNotRecorded(Server server, ClusterName clusterName, String reason) {
        System.err.printf("  WARN: swept VM %d (%s) was NOT recorded in the cleanup ledger — %s.%n",
                          server.id(),
                          server.name(),
                          reason);
        System.err.printf("  The delete below is about to be its only disposal; if that delete fails, this id"
                         + " is in no file. Remove it with 'tools/cloud-reaper.sh --cluster %s --destroy', or"
                         + " directly by id %d.%n",
                          clusterName,
                          server.id());
    }

    /// The inventory print is part of the contract, not decoration: an operator reading the destroy
    /// transcript must be able to see EXACTLY what the sweep deleted — #572's lesson is that a
    /// dry-run inventory is an inventory of what you can LOSE.
    @Contract
    private static void printVmInventory(List<Server> servers) {
        System.out.printf("  Sweep inventory (%d VM(s)):%n", servers.size());
        for (var server : servers) {
            System.out.printf("    - %s (id=%d)%n", server.name(), server.id());
        }
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<Unit> deleteSweptServer(HetznerClient client, Server server) {
        return client.deleteServer(server.id())
                     .await()
                     .fold(cause -> tolerateServerAlreadyGone(cause, server),
                           _ -> logSweptServer(server));
    }

    private static Result<Unit> logSweptServer(Server server) {
        System.out.printf("    deleted VM %d (%s).%n", server.id(), server.name());

        return Result.unitResult();
    }

    private static Result<Unit> tolerateServerAlreadyGone(Cause cause, Server server) {
        if (isAlreadyGone(cause)) {
            System.out.printf("    VM %d already gone — tolerated.%n", server.id());

            return Result.unitResult();
        }

        System.err.printf("    WARN: failed to delete VM %d (%s): %s%n", server.id(), server.name(), cause.message());

        return cause.result();
    }

    private static Result<Unit> finishVmSweep(ClusterName clusterName, int matchCount, List<ReapFailure> failures) {
        if (!failures.isEmpty()) {
            System.err.printf("  VM sweep: %d of %d deletion(s) failed.%n", failures.size(), matchCount);
            printLeftBehind(clusterName, "VM sweep", failures);

            return new VmSweepFailed(joinDescriptions(failures), joinEnumeration(failures)).result();
        }

        System.out.printf("  VM sweep complete (%d swept).%n", matchCount);

        return Result.unitResult();
    }

    private static Result<Unit> finishSweep(ClusterName clusterName, int matchCount, List<ReapFailure> failures) {
        if (!failures.isEmpty()) {
            System.err.printf("  SSH-key sweep: %d of %d deletion(s) failed.%n", failures.size(), matchCount);
            printLeftBehind(clusterName, "SSH-key sweep", failures);

            return new SshKeySweepFailed(joinDescriptions(failures), joinEnumeration(failures)).result();
        }

        System.out.printf("  SSH-key sweep: %d cluster-scoped key(s) removed.%n", matchCount);

        return Result.unitResult();
    }

    /// #994 — one reap attempt that did not succeed, kept as the RESOURCE plus its cause rather than as a
    /// flattened string, because a partially-failed cleanup has to enumerate what it left behind by type
    /// and id. "orphan resources may remain" is not actionable; the thing an operator needs is the list.
    record ReapFailure(CreatedResource resource, Cause cause) {
        String describe() {
            return resource.description() + ": " + cause.message();
        }

        String enumerate() {
            return "[" + resource.getClass()
                                 .getSimpleName()
                 + "] id=" + resource.resourceId()
                 + " provider=" + resource.provider()
                 + " (" + resource.description()
                 + ")";
        }
    }

    /// The cleanup run's observed result: what failed (with the resource, for enumeration) and what was
    /// actually reaped. `reaped` is load-bearing rather than decorative — the firewall arm reads it to
    /// state how many of the ledger's VMs this run really deleted, instead of asserting a cause.
    /// `sweep` is the outcome of the #997 pre-firewall VM sweep — [Result#unitResult] on the bootstrap path,
    /// where no sweep is wired. It is kept separate from `failures` because the sweep already enumerates its
    /// own leftovers through [#printLeftBehind], so folding it in would print them twice.
    record CleanupOutcome(List<ReapFailure> failures, List<CreatedResource> reaped, Result<Unit> sweep) {}

    /// #997 — the sweep fires before the first resource at [#SWEEP_BEFORE_RANK] or above, and after the loop
    /// if the walk never reached that rank. The trailing call is not belt-and-braces: a ledger holding only
    /// VMs has no rank-3 resource at all, and dropping the sweep there would skip it for exactly the cluster
    /// whose firewall was recorded by something else.
    private static CleanupOutcome collectCleanupFailures(BootstrapState state,
                                                         List<CreatedResource> resources,
                                                         CleanupResolvers resolvers,
                                                         Option<Fn0<Result<Unit>>> preFirewallSweep) {
        var failures = new ArrayList<ReapFailure>();
        var reaped = new ArrayList<CreatedResource>();
        var sweep = Result.unitResult();
        var reachedSweepRank = false;

        for (var resource : inDestructionOrder(resources)) {
            if (!reachedSweepRank && destructionRank(resource) >= SWEEP_BEFORE_RANK) {
                reachedSweepRank = true;
                sweep = runSweep(preFirewallSweep);
            }

            var result = destroyResource(state,
                                         resource,
                                         resolvers,
                                         List.copyOf(reaped),
                                         sweepVerdict(preFirewallSweep, reachedSweepRank, sweep));

            logResourceResult(result, resource);
            var _ = result.onSuccess(_ -> reaped.add(resource))
                          .onFailure(cause -> failures.add(new ReapFailure(resource, cause)));
        }

        return new CleanupOutcome(List.copyOf(failures),
                                  List.copyOf(reaped),
                                  reachedSweepRank
                                  ? sweep
                                  : runSweep(preFirewallSweep));
    }

    /// #997 — whether the cluster-labelled VM sweep ran before the firewall delete now being attempted, and
    /// how it ended. [VmAccounting] reports it because the reorder made its old wording false: with an empty
    /// ledger it said "this cleanup has issued no server delete", which a sweep that had just deleted three
    /// unrecorded VMs contradicts. That is the #994 class — a diagnostic asserting an action instead of
    /// reporting an observation — so the observation had to reach it.
    enum SweepVerdict {
        NOT_RUN,
        SUCCEEDED,
        FAILED
    }

    private static SweepVerdict sweepVerdict(Option<Fn0<Result<Unit>>> preFirewallSweep,
                                             boolean reachedSweepRank,
                                             Result<Unit> sweep) {
        if (preFirewallSweep.isEmpty() || !reachedSweepRank) {
            return SweepVerdict.NOT_RUN;
        }

        return sweep.isSuccess()
               ? SweepVerdict.SUCCEEDED
               : SweepVerdict.FAILED;
    }

    /// Hetzner refuses to delete a firewall still applied to a live server, so VMs must go first.
    /// Reverse-of-creation (applied by `cleanupWith`) already yields that today, because the firewall
    /// phase runs BEFORE provision — this makes the guarantee independent of record order rather than
    /// a consequence of phase ordering, so a later producer that records a firewall AFTER provisioning
    /// (e.g. applying a rule change once #578 lands) cannot silently break teardown. The sort is
    /// stable, so equal-rank resources keep the reversed order they arrived in.
    ///
    /// #994 — the rank sort is pinned by `BootstrapCleanupTest#cleanup_deletesVmBeforeFirewall_...`
    /// against a ledger that records the VM FIRST, so reverse-of-creation alone would issue the firewall
    /// delete first: remove or invert the sort and that test reddens. The pin matters because the 422
    /// `resource_in_use` failure this order prevents is indistinguishable, from the cleanup's side, from
    /// the ledger simply not knowing about the servers — which is the defect #994 actually was.
    private static List<CreatedResource> inDestructionOrder(List<CreatedResource> resources) {
        return resources.stream()
                        .sorted(Comparator.comparingInt(BootstrapCleanup::destructionRank))
                        .toList();
    }

    /// #997 — the rank the VM sweep is inserted at. Everything a firewall can still be attached to ranks
    /// BELOW it and the firewall itself ranks AT it, so a sweep here lands after the last VM-rank delete
    /// (cores first, so the reconciler that would re-provision the swept workers is already dead) and before
    /// the first firewall delete (nothing the provider can call `resource_in_use` survives it).
    ///
    /// Package-visible, and asserted by a test against `destructionRank(CloudFirewall)` rather than against a
    /// restated literal — the same arrangement as [#FIREWALL_DELETE_ATTEMPTS]. Re-rank the firewall and the
    /// pin reddens instead of the sweep silently drifting to the wrong side of it.
    static final int SWEEP_BEFORE_RANK = 3;

    @SuppressWarnings("JBCT-PAT-01")
    static int destructionRank(CreatedResource resource) {
        return switch (resource) {
            case CreatedResource.SshDeployedConfig ignored -> 0;
            case CreatedResource.ProvisionedVm ignored -> 1;
            case CreatedResource.DockerContainer ignored -> 1;
            case CreatedResource.FloatingIpAssignment ignored -> 2;
            case CreatedResource.CloudFirewall ignored -> 3;
            case CreatedResource.SshKeyResource ignored -> 4;
        };
    }

    @Contract
    private static void logResourceResult(Result<Unit> result, CreatedResource resource) {
        var _ = result.onSuccess(_ -> System.out.println("  Cleaned up " + resource.description()))
                      .onFailure(cause -> System.err.println("  WARN: Failed to cleanup " + resource.description()
                                                            + ": " + cause.message()));
    }

    /// #997 — a failed VM sweep is a failed cleanup, so the ledger file is KEPT. That is the point of
    /// folding it in rather than reporting it beside: the ledger used to be deleted while the sweep failure
    /// merely kept the registry entry, and a second `destroy` then found no state, printed "No bootstrap
    /// state — skipping resource cleanup", removed the entry and exited 0 over VMs that were still billing.
    /// Keeping it makes the retry the idempotent operation it is already advertised as.
    private static Result<Unit> finishCleanup(BootstrapState state, CleanupOutcome outcome) {
        if (!outcome.failures().isEmpty()) {
            printLeftBehind(state.clusterName(), "cleanup", outcome.failures());

            return new CleanupError(joinDescriptions(outcome.failures()), joinEnumeration(outcome.failures())).result();
        }

        if (outcome.sweep().isFailure()) {
            return outcome.sweep();
        }

        return BootstrapStatePersistence.delete(state.clusterName());
    }

    /// #994 — a cleanup that cannot fully reap names EVERY resource it is leaving behind, with its type
    /// and id. The previous top-level message was "orphan resources may remain", which tells an operator
    /// that something may be billing without telling them what to delete; on 2026-09-11 the something was
    /// two running `ccx23` servers and a firewall, and the list had to be reconstructed by hand from
    /// `hcloud server list`.
    ///
    /// #994 verification finding SF-4 — `actor` exists because this block is shared by all THREE teardown
    /// paths now. The enumeration was scoped to the ledger-driven `cleanupWith` only, which made the
    /// unqualified claim "every unreaped resource is enumerated" false of the command as a whole — and the
    /// paths it missed (the label-scoped VM sweep, the ssh-key sweep) are exactly where **unrecorded
    /// billable VMs** live, which is #994's own theme.
    @Contract
    private static void printLeftBehind(ClusterName clusterName, String actor, List<ReapFailure> failures) {
        System.err.printf("  NOT REAPED — %d resource(s) this %s could not delete, which may still be billing:%n",
                          failures.size(),
                          actor);
        for (var failure : failures) {
            System.err.println("    - " + failure.enumerate());
        }

        System.err.printf("  Finish teardown with: tools/cloud-reaper.sh --cluster %s --destroy%n", clusterName);
    }

    private static String joinDescriptions(List<ReapFailure> failures) {
        return String.join("; ",
                           failures.stream().map(ReapFailure::describe).toList());
    }

    private static String joinEnumeration(List<ReapFailure> failures) {
        return String.join("; ",
                           failures.stream().map(ReapFailure::enumerate).toList());
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static Result<Unit> destroyResource(BootstrapState state,
                                                CreatedResource resource,
                                                CleanupResolvers resolvers,
                                                List<CreatedResource> reapedSoFar,
                                                SweepVerdict sweep) {
        return switch (resource) {
            case CreatedResource.ProvisionedVm vm -> destroyVm(state, vm, resolvers);
            case CreatedResource.CloudFirewall firewall -> deleteCloudFirewall(state,
                                                                               firewall,
                                                                               resolvers,
                                                                               reapedSoFar,
                                                                               sweep);
            case CreatedResource.FloatingIpAssignment ip -> detachFloatingIp(ip);
            case CreatedResource.DockerContainer container -> removeContainer(container);
            case CreatedResource.SshDeployedConfig config -> removeRemoteConfig(config);
            case CreatedResource.SshKeyResource key -> deleteSshKey(state, key, resolvers);
        };
    }

    /// Deletes the standalone firewall [ComputeProvider#openIngress] created for a source.
    ///
    /// This used to print "Deleting firewall rule ..." and return success WITHOUT issuing any call,
    /// so `logResourceResult` reported "Cleaned up ..." for a firewall that still existed and still
    /// cost money. Nothing produced a firewall resource at the time, which is why the lie was
    /// invisible; wiring a producer to that stub would have leaked every firewall Aether created.
    ///
    /// Deletion is scoped by the recorded id ALONE — never by a label sweep. An unscoped reap is
    /// exactly what destroyed the standing `test-pg` VM and its firewall on 2026-08-03 (#572).
    ///
    /// Retries because server deletion is ASYNCHRONOUS: `deleteServer` returns before Hetzner has
    /// finished detaching the server from its firewall, so the immediately-following delete fails
    /// `422 resource_in_use` even though teardown is proceeding correctly. Observed on the live
    /// 2026-08-05 run — the very next attempt succeeded once the servers had drained. A single
    /// attempt therefore reports failure for a firewall that is about to be deletable, leaving the
    /// operator to re-run destroy by hand.
    ///
    /// The numeric conversion is [FirewallId#asNumeric], not a local parse: the recorded id is
    /// provider-opaque so every provider can record what it created, and Hetzner's own API takes a
    /// number. Keeping the conversion (and its refusal) on the type gives it ONE home — a non-numeric
    /// id under `hetzner` means the ledger was written by something else, and guessing an id here
    /// would delete someone else's firewall.
    @SuppressWarnings("JBCT-EX-01")
    private static Result<Unit> deleteCloudFirewall(BootstrapState state,
                                                    CreatedResource.CloudFirewall firewall,
                                                    CleanupResolvers resolvers,
                                                    List<CreatedResource> reapedSoFar,
                                                    SweepVerdict sweep) {
        System.out.printf("  Deleting firewall %s (id=%s)...%n", firewall.name(), firewall.firewallId());

        return resolveComputeFor(state, firewall, resolvers).flatMap(compute -> disposeWithRetry(compute,
                                                                                                 firewall,
                                                                                                 resolvers,
                                                                                                 vmAccounting(state,
                                                                                                              firewall,
                                                                                                              reapedSoFar,
                                                                                                              sweep)));
    }

    /// #994 — the OBSERVED state behind a firewall that will not delete, assembled only from facts this
    /// cleanup already holds: how many VMs the bootstrap ledger records for the firewall's own source,
    /// and how many of those this run actually deleted before reaching the firewall. Destruction order
    /// makes the second number meaningful — every VM attempt precedes the firewall (see
    /// [#destructionRank]), so at this point the VM outcomes are final, not pending.
    ///
    /// This exists to replace the retry line *"servers are still detaching; retrying..."*, which
    /// asserted a process that was NOT running. On 2026-09-11 the ledger held ZERO VMs, so no server
    /// delete had been issued and nothing was detaching; the message sent an operator to look at server
    /// shutdown while the actual problem was an incomplete ledger. A diagnostic may report what it has
    /// observed. It may not narrate a mechanism it never started.
    /// #994 verification NOTE-5 — `deleted` counts what entered `reapedSoFar`, and `tolerateAlreadyGone`
    /// puts an ALREADY-ABSENT VM there too, so the full-count branch below says **"accounted for"** rather
    /// than "deleted": this cleanup may have issued a delete that returned `InstanceNotFound` for a server
    /// somebody else had already removed. Saying "deleted all N" of a server it never deleted is small, but
    /// it is the same class as #994's "servers are still detaching" — a diagnostic asserting an action
    /// instead of reporting an observation — and this record exists to end that class.
    /// #997 — `sweep` is the fourth component because the reorder changed what this record can truthfully
    /// say. The label sweep now runs BEFORE this delete, so "this cleanup has issued no server delete" is
    /// false whenever the sweep deleted something, and "check for unrecorded VMs" is advice the cleanup has
    /// already acted on. Both now read off the verdict.
    record VmAccounting(String sourceName, int recorded, int deleted, SweepVerdict sweep) {
        String describe() {
            if (recorded == 0) {
                return "the bootstrap ledger records NO VMs for source '" + sourceName
                     + "', so this cleanup has issued no LEDGER-DRIVEN server delete for it" + sweepClause()
                     + ". Check for unrecorded VMs with 'hcloud server list -l aether-cluster=<cluster>'";
            }

            if (deleted < recorded) {
                return "the bootstrap ledger records " + recorded
                     + " VM(s) for source '" + sourceName
                     + "' and this cleanup deleted " + deleted
                     + " of them, so " + (recorded - deleted)
                     + " recorded VM(s) were NOT deleted (their own failures are reported above)" + sweepClause();
            }

            return "this cleanup accounted for all " + recorded
                 + " VM(s) the bootstrap ledger records for source '" + sourceName
                 + "' (deleted, or already gone and reported as such above)" + sweepClause()
                 + ", and the provider still reports the firewall in use";
        }

        /// Reports the sweep as an observation, never as an explanation of the refusal.
        private String sweepClause() {
            return switch (sweep) {
                case NOT_RUN -> ", and no cluster-labelled VM sweep ran before this delete";
                case SUCCEEDED -> ", and the cluster-labelled VM sweep ran before this delete and reported" + " success (its own inventory is above)";
                case FAILED -> ", and the cluster-labelled VM sweep ran before this delete and FAILED, so a" + " VM it could not remove may still hold the firewall (its leftovers are" + " enumerated above)";
            };
        }
    }

    private static VmAccounting vmAccounting(BootstrapState state,
                                             CreatedResource.CloudFirewall firewall,
                                             List<CreatedResource> reapedSoFar,
                                             SweepVerdict sweep) {
        var sourceName = firewall.sourceName().value();

        return new VmAccounting(sourceName,
                                countVmsFor(state.createdResources(), sourceName),
                                countVmsFor(reapedSoFar, sourceName),
                                sweep);
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static int countVmsFor(List<CreatedResource> resources, String sourceName) {
        return (int) resources.stream()
                              .filter(resource -> resource instanceof CreatedResource.ProvisionedVm vm && vm.sourceName()
                                                                                                            .equals(sourceName))
                              .count();
    }

    /// Attempts bounded by [#FIREWALL_DELETE_ATTEMPTS]; the LAST failure is what surfaces, so a
    /// genuinely undeletable firewall still fails loudly rather than being swallowed.
    int FIREWALL_DELETE_ATTEMPTS = 6;
    long FIREWALL_DELETE_RETRY_MILLIS = 5_000L;

    /// Provider-agnostic: the id is destroyed through [ComputeProvider#disposeIngress], so a provider
    /// that can CREATE an ingress resource can always reclaim it. This used to branch on `hetzner` and
    /// refuse everything else, which is how the teardown path would have silently stranded every AWS
    /// security group as a billable orphan the moment AWS gained `openIngress`.
    @SuppressWarnings("JBCT-PAT-01")
    private static Result<Unit> disposeWithRetry(ComputeProvider compute,
                                                 CreatedResource.CloudFirewall firewall,
                                                 CleanupResolvers resolvers,
                                                 VmAccounting accounting) {
        var attempt = 1;

        while (true) {
            var result = compute.disposeIngress(firewall.firewallId()).await();

            if (result.isSuccess() || attempt >= FIREWALL_DELETE_ATTEMPTS) {
                return result;
            }

            logFirewallRefusal(firewall, attempt, accounting, result);
            resolvers.sleeper().accept(FIREWALL_DELETE_RETRY_MILLIS);
            attempt++;
        }
    }

    /// #994 — reports the provider's OWN refusal verbatim plus [VmAccounting]'s observed counts, and
    /// asserts nothing about why. The line it replaces claimed "servers are still detaching" on every
    /// attempt, including the observed run where no server delete had been issued at all.
    @Contract
    private static void logFirewallRefusal(CreatedResource.CloudFirewall firewall,
                                           int attempt,
                                           VmAccounting accounting,
                                           Result<Unit> result) {
        System.out.printf("  Firewall %s NOT deleted (attempt %d/%d) — provider refused: %s%n",
                          firewall.firewallId(),
                          attempt,
                          FIREWALL_DELETE_ATTEMPTS,
                          result.fold(Cause::message, _ -> ""));
        System.out.printf("    observed: %s. Retrying in %dms.%n", accounting.describe(), FIREWALL_DELETE_RETRY_MILLIS);
    }

    /// Teardown-only pause between firewall delete attempts. Interruption is restored and treated as
    /// "stop waiting" — the next attempt still runs, so an interrupted teardown fails on the API's
    /// verdict rather than on the interrupt.
    @Contract
    @SuppressWarnings("JBCT-EX-01")
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Result<Unit> deleteSshKey(BootstrapState state,
                                             CreatedResource.SshKeyResource key,
                                             CleanupResolvers resolvers) {
        System.out.printf("  Deleting SSH key %d (%s) from %s...%n", key.sshKeyId(), key.name(), key.provider());
        if (!"hetzner".equals(key.provider())) {
            return new UnsupportedSshKeyProvider(key.provider()).result();
        }

        return resolveHetznerClientFor(state, key.provider(), resolvers).flatMap(client -> client.deleteSshKey(key.sshKeyId())
                                                                                                 .await());
    }

    /// RFC-0016 W4 — reaping routes through the persisted handle first (the credential the token
    /// that provisioned uses), so a token supplied under a non-default env-var name still reaps its own
    /// resources. Only when NO handle names this provider does it fall back — loudly — to the raw-env resolver.
    @SuppressWarnings("JBCT-PAT-01")
    private static Result<HetznerClient> resolveHetznerClientFor(BootstrapState state,
                                                                 String provider,
                                                                 CleanupResolvers resolvers) {
        var handle = state.sources()
                          .values()
                          .stream()
                          .filter(candidate -> provider.equals(candidate.provider()))
                          .findFirst()
                          .orElse(null);

        if (handle == null) {
            System.err.println("  WARN: no persisted cleanup handle names provider '" + provider
                              + "'; falling back to raw HCLOUD_TOKEN. A token that provisioned SHOULD be able to reap"
                              + " its own resources — a missing handle means bootstrap did not persist one.");

            return resolvers.hetznerClientFallback()
                            .apply(provider);
        }

        return hetznerClientFromHandle(handle, resolvers);
    }

    /// #521 — a handle whose `credentialEnvVars` names NO api-token env var expresses no credential intent
    /// at all (bootstrap mined nothing from the TOML), so it is informationally equivalent to having no
    /// handle: fall back to the raw-env resolver, LOUDLY, as the same demoted last resort. This does NOT
    /// weaken W4/#439 — a handle that DOES name an env var is still authoritative, and an unset one still
    /// hard-fails rather than silently reaping with some other account's token.
    private static Result<HetznerClient> hetznerClientFromHandle(SourceCleanupHandle handle,
                                                                 CleanupResolvers resolvers) {
        var envVarName = handle.credentialEnvVars().get(API_TOKEN_KEY);

        if (envVarName == null || envVarName.isBlank()) {
            System.err.println("  WARN: persisted cleanup handle for provider '" + handle.provider()
                              + "' has no '" + API_TOKEN_KEY
                              + "' credential env-var mapping; falling back to raw provider env credentials."
                              + " Bootstrap recorded the handle but mined no ${env:NAME} credential from the"
                              + " [source.*] stanza — reaping proceeds so resources are never stranded.");

            return resolvers.hetznerClientFallback()
                            .apply(handle.provider());
        }

        var token = resolvers.getenv().apply(envVarName);

        if (token == null || token.isBlank()) {
            return new HetznerCleanupTokenEnvUnset(envVarName).result();
        }

        return Result.success(resolvers.hetznerClientFactory().apply(token));
    }

    private static HetznerClient hetznerClientFromToken(String token) {
        return HetznerClient.hetznerClient(HetznerConfig.hetznerConfig(token));
    }

    private static Result<HetznerClient> defaultHetznerClient(String providerName) {
        if (!"hetzner".equals(providerName)) {
            return new UnsupportedSshKeyProvider(providerName).result();
        }

        var token = System.getenv("HCLOUD_TOKEN");

        if (token == null || token.isBlank()) {
            return HetznerCredentialsMissing.INSTANCE.result();
        }

        return Result.success(hetznerClientFromToken(token));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<Unit> destroyVm(BootstrapState state,
                                          CreatedResource.ProvisionedVm vm,
                                          CleanupResolvers resolvers) {
        System.out.printf("  Destroying VM %s (provider: %s)...%n", vm.resourceId(), vm.provider());

        return resolveComputeForVm(state, vm, resolvers).flatMap(compute -> terminateInstance(compute, vm.resourceId()));
    }

    /// RFC-0016 W4 — VM reaping resolves compute from the persisted handle; the raw-env resolver is a loud
    /// last resort reached only when bootstrap recorded no handle for the VM's source, or (#521) recorded
    /// one that names no credential env var at all. In the latter case the handle-derived config would
    /// carry an EMPTY credentials map and the provider factory would reject it ("credentials missing"),
    /// stranding a running VM even though the correctly-named env var is present in the operator's shell.
    private static Result<ComputeProvider> resolveComputeForVm(BootstrapState state,
                                                               CreatedResource.ProvisionedVm vm,
                                                               CleanupResolvers resolvers) {
        var handle = state.sources().get(vm.sourceName());

        if (handle == null) {
            return fallbackCompute(vm, NO_HANDLE_REASON, resolvers);
        }

        if (handle.credentialEnvVars().isEmpty()) {
            return fallbackCompute(vm, UNMAPPED_HANDLE_REASON, resolvers);
        }

        return resolvers.handleComputeResolver()
                        .apply(handle);
    }

    /// The compute provider that can reclaim this firewall, resolved the same way the VM path resolves
    /// its own: the persisted per-source cleanup handle when there is one, else the raw provider-env
    /// fallback. Keeping the two paths identical matters because a firewall and the VMs it protected
    /// belong to the SAME source — if one resolves credentials and the other does not, teardown reclaims
    /// half the source and strands the rest.
    private static Result<ComputeProvider> resolveComputeFor(BootstrapState state,
                                                             CreatedResource.CloudFirewall firewall,
                                                             CleanupResolvers resolvers) {
        var handle = state.sources().get(firewall.sourceName().value());

        if (handle == null || handle.credentialEnvVars().isEmpty()) {
            System.err.println("  WARN: no usable persisted cleanup handle for source '" + firewall.sourceName().value()
                              + "'; falling back to raw " + firewall.provider()
                              + " env credentials to reclaim firewall " + firewall.name()
                              + ". A token that provisioned SHOULD be able to reap, so reaping proceeds rather"
                              + " than stranding a paid resource.");

            return resolvers.cloudComputeFallback()
                            .apply(firewall.provider());
        }

        return resolvers.handleComputeResolver()
                        .apply(handle);
    }

    String NO_HANDLE_REASON = "bootstrap persisted no cleanup handle";

    String UNMAPPED_HANDLE_REASON = "the persisted cleanup handle names no credential env var"
                                  + " (no credentials = \"${env:NAME}\" was mined from the source stanza)";

    private static Result<ComputeProvider> fallbackCompute(CreatedResource.ProvisionedVm vm,
                                                           String reason,
                                                           CleanupResolvers resolvers) {
        System.err.println("  WARN: " + reason
                          + " for source '" + vm.sourceName()
                          + "'; falling back to raw " + vm.provider()
                          + " env credentials. A token that provisioned SHOULD be able to reap, so reaping"
                          + " proceeds rather than stranding paid resources.");

        return resolvers.cloudComputeFallback()
                        .apply(vm.provider());
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<Unit> terminateInstance(ComputeProvider compute, String resourceId) {
        return InstanceId.instanceId(resourceId).flatMap(id -> tolerateAlreadyGone(compute.terminate(id).await(),
                                                                                   id));
    }

    private static Result<Unit> tolerateAlreadyGone(Result<Unit> result, InstanceId id) {
        return result.fold(cause -> alreadyGone(cause, id), Result::success);
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static Result<Unit> alreadyGone(Cause cause, InstanceId id) {
        if (cause instanceof EnvironmentError.InstanceNotFound) {
            System.out.printf("  VM %s already gone — treating as destroyed%n", id.value());

            return Result.unitResult();
        }

        return cause.result();
    }

    private static Result<Unit> detachFloatingIp(CreatedResource.FloatingIpAssignment ip) {
        System.out.printf("  Detaching floating IP %s from %s...%n", ip.floatingIp(), ip.targetNodeId());

        return Result.unitResult();
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<Unit> removeContainer(CreatedResource.DockerContainer container) {
        System.out.printf("  Removing container %s...%n", container.containerId());

        return ProviderResolver.resolveDockerCompute().flatMap(compute -> terminateInstance(compute,
                                                                                            container.containerId()));
    }

    private static Result<Unit> removeRemoteConfig(CreatedResource.SshDeployedConfig config) {
        System.out.printf("  Removing config %s from %s...%n", config.remotePath(), config.host());

        return Result.unitResult();
    }

    /// #994 — `notReaped` is the enumeration, not a restatement of `detail`: it carries type + id for every
    /// resource left behind, so `BootstrapError.BootstrapFailedWithOrphans`'s top-level message names what
    /// is still billing instead of saying "orphan resources may remain".
    record CleanupError(String detail, String notReaped) implements Cause {
        @Override
        public String message() {
            return notReaped.isEmpty()
                   ? "Cleanup completed with failures: " + detail
                   : "Cleanup completed with failures: " + detail + " | NOT REAPED: " + notReaped;
        }
    }

    /// #994 verification finding SF-4 — two fields for the same reason [CleanupError] has two: `detail` is
    /// the transcript, `notReaped` is the machine-shaped enumeration (type + id per resource) that survives
    /// into `ClusterDestroyCommand`'s exit message after the transcript has scrolled away. A swept key that
    /// could not be deleted is an orphaned credential on the account, not merely a failed call.
    record SshKeySweepFailed(String detail, String notReaped) implements Cause {
        @Override
        public String message() {
            return notReaped.isEmpty()
                   ? "SSH-key sweep completed with failures: " + detail
                   : "SSH-key sweep completed with failures: " + detail + " | NOT REAPED: " + notReaped;
        }
    }

    /// #994 verification finding SF-4 — the VM sweep's failure used to be a bare `Causes.cause` holding a
    /// joined string with no type and no id, on the one teardown path whose whole purpose is **billable VMs
    /// the ledger never recorded.** Named, and carrying the enumeration, like every other reap failure.
    record VmSweepFailed(String detail, String notReaped) implements Cause {
        @Override
        public String message() {
            return notReaped.isEmpty()
                   ? "VM sweep completed with failures: " + detail
                   : "VM sweep completed with failures: " + detail + " | NOT REAPED: " + notReaped;
        }
    }

    record UnsupportedFirewallProvider(String provider) implements Cause {
        @Override
        public String message() {
            return "Unsupported firewall provider for cleanup: '" + provider + "'";
        }
    }

    record UnsupportedSshKeyProvider(String provider) implements Cause {
        @Override
        public String message() {
            return "Unsupported SSH key provider for cleanup: '" + provider + "'";
        }
    }

    enum HetznerCredentialsMissing implements Cause {
        INSTANCE;
        @Override
        public String message() {
            return "Hetzner credentials missing for SSH key cleanup: set HCLOUD_TOKEN env var";
        }
    }

    record HetznerCleanupTokenEnvUnset(String envVarName) implements Cause {
        @Override
        public String message() {
            return "Hetzner SSH-key cleanup: credential env var '" + envVarName
                 + "' (recorded in the persisted"
                 + " source handle at bootstrap) is unset — a token that provisioned must be able to reap its own"
                 + " ssh keys";
        }
    }
}
