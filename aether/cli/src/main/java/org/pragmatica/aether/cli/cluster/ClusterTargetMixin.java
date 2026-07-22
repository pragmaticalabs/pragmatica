// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import picocli.CommandLine.Option;


public class ClusterTargetMixin {
    static final Pattern CLUSTER_NAME_PATTERN = Pattern.compile("^[a-z]([a-z0-9-]{0,61}[a-z0-9])?$");
    private static final String API_KEY_FILE_NAME = "api-key";
    static Supplier<Result<ClusterRegistry>> registryLoader = ClusterRegistry::load;
    static Function<String, Path> apiKeyPathResolver = ClusterTargetMixin::defaultApiKeyPath;

    @Option(names = "--cluster", description = "Override active cluster — target named cluster instead (CLI > active-context)")
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

        if (!CLUSTER_NAME_PATTERN.matcher(clusterName).matches()) {
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

        return CLUSTER_NAME_PATTERN.matcher(clusterName).matches();
    }

    private Result<Unit> resolveAndInstall(ClusterRegistry registry) {
        return findEntry(registry, clusterName).map(this::installOverrides);
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

    private Unit installOverrides(ClusterRegistry.ClusterEntry entry) {
        installEndpoint(entry);
        installApiKey(entry.name());

        return Unit.unit();
    }

    @Contract
    private static void installEndpoint(ClusterRegistry.ClusterEntry entry) {
        if (entry.endpoint() == null || entry.endpoint().isBlank()) {
            return;
        }

        ClusterHttpClient.setEndpointOverride(entry.endpoint());
    }

    @Contract
    private static void installApiKey(String name) {
        readApiKeyFile(name).onSuccess(ClusterHttpClient::setApiKeyOverride);
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
