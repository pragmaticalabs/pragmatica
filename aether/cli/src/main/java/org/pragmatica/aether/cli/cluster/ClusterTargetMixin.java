// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.function.Supplier;

import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import picocli.CommandLine;

import static org.pragmatica.lang.Option.option;


public class ClusterTargetMixin {
    private static final String API_KEY_FILE_NAME = "api-key";
    static Supplier<Result<ClusterRegistry>> registryLoader = ClusterRegistry::load;
    static Function<String, Path> apiKeyPathResolver = ClusterTargetMixin::defaultApiKeyPath;

    @CommandLine.Option(names = "--cluster", description = "Override active cluster — target named cluster instead (CLI > active-context)")
    private String clusterName;

    @Contract
    void setClusterName(String value) {
        this.clusterName = value;
    }

    String clusterName() {
        return clusterName;
    }

    public Result<Unit> applyOverrides() {
        if (clusterName == null || clusterName.isBlank()) {
            return Result.unitResult();
        }

        if (!ClusterName.PATTERN.matcher(clusterName).matches()) {
            return new ClusterTargetError.InvalidClusterName(clusterName).result();
        }

        return registryLoader.get()
                             .mapError(c -> new ClusterTargetError.RegistryUnavailable(c.message()))
                             .flatMap(this::resolveAndInstall);
    }

    boolean isOverrideAcceptable() {
        if (clusterName == null || clusterName.isBlank()) {
            return true;
        }

        return ClusterName.PATTERN.matcher(clusterName).matches();
    }

    private Result<Unit> resolveAndInstall(ClusterRegistry registry) {
        return findEntry(registry, clusterName).flatMap(this::installOverrides);
    }

    private static Result<ClusterRegistry.ClusterEntry> findEntry(ClusterRegistry registry, String name) {
        return registry.entries()
                       .stream()
                       .filter(e -> e.name()
                                     .equals(name))
                       .findFirst()
                       .map(Result::success)
                       .orElseGet(() -> new ClusterTargetError.UnknownCluster(name).result());
    }

    private Result<Unit> installOverrides(ClusterRegistry.ClusterEntry entry) {
        return installEndpoint(entry).onSuccess(_ -> installApiKey(entry));
    }

    /// A blank `endpoint` used to return early and leave whatever `main` installed — the active
    /// context — in force, so `--cluster legacy` silently dialled another cluster (#584 review).
    private static Result<Unit> installEndpoint(ClusterRegistry.ClusterEntry entry) {
        if (entry.endpoint() == null || entry.endpoint().isBlank()) {
            return new ClusterTargetError.BlankEndpoint(entry.name()).result();
        }

        ClusterHttpClient.setEndpointOverride(entry.endpoint());

        return Result.unitResult();
    }

    @Contract
    private static void installApiKey(ClusterRegistry.ClusterEntry entry) {
        apiKeyOf(entry).onPresent(ClusterHttpClient::setApiKeyOverride);
    }

    /// #2133 — THE key of a registered cluster, for every path that dials that cluster: `--cluster X`,
    /// `cluster destroy --cluster X`, and the active context behind a plain `aether status` /
    /// `aether cluster topology`. The saved `~/.aether/clusters/<name>/api-key` comes first, then the env
    /// var the entry records in `api_key_env`.
    ///
    /// The file is first because it is what `cluster bootstrap` and `cluster rotate-key` write: a fresh
    /// shell after bootstrap has no `AETHER_<NAME>_API_KEY` exported, and after a rotation an exported
    /// one holds the revoked key. Before #2133 only `--cluster` read the file, so the plain commands sent
    /// no key at all and the operator got a bare 401.
    ///
    /// The name is parsed as a [ClusterName] before it becomes a path segment, so a hand-edited registry
    /// section name cannot point the read outside `~/.aether/clusters/`. Whether this key may travel at
    /// all is the caller's decision: it belongs only to this entry's own endpoint (#584).
    public static Option<String> apiKeyOf(ClusterRegistry.ClusterEntry entry) {
        return ClusterName.clusterName(entry.name())
                          .flatMap(name -> readApiKeyFile(name.value()))
                          .option()
                          .orElse(() -> recordedApiKey(entry));
    }

    private static Option<String> recordedApiKey(ClusterRegistry.ClusterEntry entry) {
        return entry.apiKeyEnv()
                    .flatMap(envName -> option(System.getenv(envName)));
    }

    @SuppressWarnings("JBCT-EX-01")
    static Result<String> readApiKeyFile(String name) {
        var path = apiKeyPathResolver.apply(name);

        if (!Files.exists(path)) {
            return new ClusterTargetError.ApiKeyMissing(path.toString()).result();
        }

        return Result.lift(e -> new ClusterTargetError.ApiKeyReadFailed(path.toString(),
                                                                        e.getMessage()),
                           () -> Files.readString(path).trim())
                     .flatMap(ClusterTargetMixin::ensureNonBlank);
    }

    private static Result<String> ensureNonBlank(String content) {
        return content.isBlank()
               ? ClusterTargetError.ApiKeyEmpty.INSTANCE.result()
               : Result.success(content);
    }

    private static Path defaultApiKeyPath(String name) {
        return Path.of(System.getProperty("user.home"), ".aether", "clusters", name, API_KEY_FILE_NAME);
    }

    public sealed interface ClusterTargetError extends Cause {
        record InvalidClusterName(String value) implements ClusterTargetError {
            @Override
            public String message() {
                return "Invalid --cluster value: '" + value + "': must match ^[a-z]([a-z0-9-]{0,61}[a-z0-9])?$";
            }
        }

        record BlankEndpoint(String name) implements ClusterTargetError {
            @Override
            public String message() {
                return "Cluster '" + name
                     + "' is registered without an endpoint; fix its entry in ~/.aether/clusters.toml or pass --connect";
            }
        }

        record UnknownCluster(String name) implements ClusterTargetError {
            @Override
            public String message() {
                return "Cluster '" + name
                     + "' not found in registry. Run 'aether cluster list' to see registered clusters.";
            }
        }

        record RegistryUnavailable(String detail) implements ClusterTargetError {
            @Override
            public String message() {
                return "Cannot read cluster registry: " + detail;
            }
        }

        record ApiKeyMissing(String path) implements ClusterTargetError {
            @Override
            public String message() {
                return "API key file not found: " + path;
            }
        }

        enum ApiKeyEmpty implements ClusterTargetError {
            INSTANCE;
            @Override
            public String message() {
                return "API key file is empty.";
            }
        }

        record ApiKeyReadFailed(String path, String detail) implements ClusterTargetError {
            @Override
            public String message() {
                return "Failed to read API key file " + path + ": " + detail;
            }
        }
    }
}
