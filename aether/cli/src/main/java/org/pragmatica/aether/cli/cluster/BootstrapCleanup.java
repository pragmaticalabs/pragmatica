// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.cloud.hetzner.HetznerClient;
import org.pragmatica.cloud.hetzner.HetznerConfig;
import org.pragmatica.cloud.hetzner.HetznerError;
import org.pragmatica.cloud.hetzner.api.SshKey;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Functions.Fn1;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
sealed interface BootstrapCleanup {
    record unused() implements BootstrapCleanup {}

    /// Handle credential alias for the cloud API token (see `BootstrapPhaseProvision.CREDENTIAL_FIELD_KEYS`).
    String API_TOKEN_KEY = "api_token";
    /// Provider identity for Hetzner cloud (matches `SourceCleanupHandle.provider()` and `CreatedResource.provider()`).
    String HETZNER_PROVIDER = "hetzner";

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
                            Fn1<HetznerClient, String> hetznerClientFactory) {
        static CleanupResolvers cleanupResolvers() {
            return new CleanupResolvers(ProviderResolver::resolveCloudComputeForCleanup,
                                        ProviderResolver::resolveCloudComputeFromHandle,
                                        BootstrapCleanup::defaultHetznerClient,
                                        System::getenv,
                                        BootstrapCleanup::hetznerClientFromToken);
        }

        CleanupResolvers withCloudComputeFallback(Fn1<Result<ComputeProvider>, String> resolver) {
            return new CleanupResolvers(resolver,
                                        handleComputeResolver,
                                        hetznerClientFallback,
                                        getenv,
                                        hetznerClientFactory);
        }

        CleanupResolvers withHandleComputeResolver(Fn1<Result<ComputeProvider>, SourceCleanupHandle> resolver) {
            return new CleanupResolvers(cloudComputeFallback,
                                        resolver,
                                        hetznerClientFallback,
                                        getenv,
                                        hetznerClientFactory);
        }

        CleanupResolvers withHetznerClientFallback(Fn1<Result<HetznerClient>, String> resolver) {
            return new CleanupResolvers(cloudComputeFallback,
                                        handleComputeResolver,
                                        resolver,
                                        getenv,
                                        hetznerClientFactory);
        }

        CleanupResolvers withGetenv(Fn1<String, String> lookup) {
            return new CleanupResolvers(cloudComputeFallback,
                                        handleComputeResolver,
                                        hetznerClientFallback,
                                        lookup,
                                        hetznerClientFactory);
        }

        CleanupResolvers withHetznerClientFactory(Fn1<HetznerClient, String> factory) {
            return new CleanupResolvers(cloudComputeFallback,
                                        handleComputeResolver,
                                        hetznerClientFallback,
                                        getenv,
                                        factory);
        }
    }

    static Result<Unit> cleanup(BootstrapState state) {
        return cleanupWith(state, CleanupResolvers.cleanupResolvers());
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
        System.out.println("Cleaning up resources for cluster '" + state.clusterName() + "'...");
        var resources = new ArrayList<>(state.createdResources());

        Collections.reverse(resources);
        var failures = collectCleanupFailures(state, resources, resolvers);

        return finishCleanup(state, failures);
    }

    /// #481 — defensive cluster-scoped ssh-key sweep. `cleanup` above deletes only keys the state recorded
    /// as a `SshKeyResource` (exact id); a *reused* pre-existing key, or one the state never captured,
    /// orphans on the Hetzner account and gets re-matched by a same-name recreate's prefix listing. This
    /// sweep lists the account's keys and deletes those scoped to THIS cluster by the delimiter-bounded name
    /// prefix `aether-bootstrap-<cluster>-` — the SAME boundary `HetznerComputeProvider.isBootstrapKey` uses
    /// (post-#444), so a `prod` destroy never touches `production`'s keys. Runs AFTER the state-based cleanup,
    /// so an already-recorded-and-deleted key surfaces as a tolerated 404/`not_found`.
    static Result<Unit> sweepClusterSshKeys(BootstrapState state, String clusterName) {
        return sweepClusterSshKeys(state, clusterName, CleanupResolvers.cleanupResolvers());
    }

    /// Full-control entry for the #481 sweep test: injects the env lookup and the token → HetznerClient
    /// factory so the sweep is observable without a real cloud call. The Hetzner client is still resolved
    /// handle-first (from the persisted hetzner `SourceCleanupHandle`), never raw `HCLOUD_TOKEN`.
    static Result<Unit> sweepClusterSshKeys(BootstrapState state,
                                            String clusterName,
                                            Fn1<String, String> getenv,
                                            Fn1<HetznerClient, String> hetznerClientFactory) {
        return sweepClusterSshKeys(state,
                                   clusterName,
                                   CleanupResolvers.cleanupResolvers()
                                                   .withGetenv(getenv)
                                                   .withHetznerClientFactory(hetznerClientFactory));
    }

    // RET-06: `clusterName` may be absent/blank when the bootstrap state never captured it;
    // the guarded skip is defensive scoping, not a business optional.
    @SuppressWarnings({"JBCT-PAT-01", "JBCT-RET-06"})
    static Result<Unit> sweepClusterSshKeys(BootstrapState state, String clusterName, CleanupResolvers resolvers) {
        if (clusterName == null || clusterName.isBlank()) {
            System.out.println("  Skipping SSH-key sweep: cluster name is blank (cannot scope the sweep).");

            return Result.unitResult();
        }

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

        return hetznerClientFromHandle(handle, resolvers).flatMap(client -> sweepWithClient(client, prefix));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<Unit> sweepWithClient(HetznerClient client, String prefix) {
        return client.listSshKeys()
                     .await()
                     .flatMap(keys -> deleteMatchingKeys(client, keys, prefix));
    }

    private static Result<Unit> deleteMatchingKeys(HetznerClient client, List<SshKey> keys, String prefix) {
        var matches = keys.stream().filter(key -> keyNameMatches(key, prefix)).toList();

        if (matches.isEmpty()) {
            System.out.println("  No cluster-scoped SSH keys found to sweep.");

            return Result.unitResult();
        }

        var failures = collectSweepFailures(client, matches);

        return finishSweep(matches.size(), failures);
    }

    private static boolean keyNameMatches(SshKey key, String prefix) {
        return key.name() != null && key.name()
                                        .startsWith(prefix);
    }

    private static List<String> collectSweepFailures(HetznerClient client, List<SshKey> matches) {
        var failures = new ArrayList<String>();

        for (var key : matches) {
            var result = deleteSweptKey(client, key);
            var _ = result.onFailure(cause -> failures.add(key.name() + " (id=" + key.id() + "): " + cause.message()));
        }

        return List.copyOf(failures);
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

    private static Result<Unit> finishSweep(int matchCount, List<String> failures) {
        if (!failures.isEmpty()) {
            System.err.printf("  SSH-key sweep: %d of %d deletion(s) failed.%n", failures.size(), matchCount);

            return new SshKeySweepFailed(String.join("; ", failures)).result();
        }

        System.out.printf("  SSH-key sweep: %d cluster-scoped key(s) removed.%n", matchCount);

        return Result.unitResult();
    }

    private static List<String> collectCleanupFailures(BootstrapState state,
                                                       List<CreatedResource> resources,
                                                       CleanupResolvers resolvers) {
        var failures = new ArrayList<String>();

        for (var resource : resources) {
            var result = destroyResource(state, resource, resolvers);

            logResourceResult(result, resource);
            var _ = result.onFailure(cause -> failures.add(resource.description() + ": " + cause.message()));
        }

        return List.copyOf(failures);
    }

    @Contract
    private static void logResourceResult(Result<Unit> result, CreatedResource resource) {
        var _ = result.onSuccess(_ -> System.out.println("  Cleaned up " + resource.description()))
                      .onFailure(cause -> System.err.println("  WARN: Failed to cleanup " + resource.description()
                                                            + ": " + cause.message()));
    }

    private static Result<Unit> finishCleanup(BootstrapState state, List<String> failures) {
        if (!failures.isEmpty()) {
            return new CleanupError(String.join("; ", failures)).result();
        }

        return BootstrapStatePersistence.delete(state.clusterName());
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static Result<Unit> destroyResource(BootstrapState state,
                                                CreatedResource resource,
                                                CleanupResolvers resolvers) {
        return switch (resource) {
            case CreatedResource.ProvisionedVm vm -> destroyVm(state, vm, resolvers);
            case CreatedResource.FirewallRule rule -> deleteFirewallRule(rule);
            case CreatedResource.FloatingIpAssignment ip -> detachFloatingIp(ip);
            case CreatedResource.DockerContainer container -> removeContainer(container);
            case CreatedResource.SshDeployedConfig config -> removeRemoteConfig(config);
            case CreatedResource.SshKeyResource key -> deleteSshKey(state, key, resolvers);
        };
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<Unit> deleteSshKey(BootstrapState state,
                                             CreatedResource.SshKeyResource key,
                                             CleanupResolvers resolvers) {
        System.out.printf("  Deleting SSH key %d (%s) from %s...%n", key.sshKeyId(), key.name(), key.provider());
        if (!"hetzner".equals(key.provider())) {
            return new UnsupportedSshKeyProvider(key.provider()).result();
        }

        return resolveHetznerClient(state, key, resolvers).flatMap(client -> client.deleteSshKey(key.sshKeyId())
                                                                                   .await());
    }

    /// RFC-0016 W4 — SSH-key reaping routes through the persisted handle first (the credential the token
    /// that provisioned uses), so a token supplied under a non-default env-var name still reaps its own
    /// keys. Only when NO handle names this provider does it fall back — loudly — to the raw-env resolver.
    @SuppressWarnings("JBCT-PAT-01")
    private static Result<HetznerClient> resolveHetznerClient(BootstrapState state,
                                                              CreatedResource.SshKeyResource key,
                                                              CleanupResolvers resolvers) {
        var handle = state.sources()
                          .values()
                          .stream()
                          .filter(candidate -> key.provider()
                                                  .equals(candidate.provider()))
                          .findFirst()
                          .orElse(null);

        if (handle == null) {
            System.err.println("  WARN: no persisted cleanup handle names SSH-key provider '" + key.provider()
                              + "'; falling back to raw HCLOUD_TOKEN. A token that provisioned SHOULD be able to reap"
                              + " its own ssh keys — a missing handle means bootstrap did not persist one.");

            return resolvers.hetznerClientFallback()
                            .apply(key.provider());
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
        return InstanceId.instanceId(resourceId).flatMap(id -> compute.terminate(id)
                                                                      .await());
    }

    private static Result<Unit> deleteFirewallRule(CreatedResource.FirewallRule rule) {
        System.out.printf("  Deleting firewall rule %s...%n", rule.resourceId());

        return Result.unitResult();
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

    record CleanupError(String detail) implements Cause {
        @Override
        public String message() {
            return "Cleanup completed with failures: " + detail;
        }
    }

    record SshKeySweepFailed(String detail) implements Cause {
        @Override
        public String message() {
            return "SSH-key sweep completed with failures: " + detail;
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
