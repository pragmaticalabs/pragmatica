// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Functions.Fn1;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
public sealed interface SshKeyResolver {
    record unused() implements SshKeyResolver {}

    String AETHER_SSH_KEY_ENV = "AETHER_SSH_KEY";

    static Result<List<SshPublicKey>> resolve(ClusterBootstrapConfig config, Option<String> cliPublicKeyPath) {
        return resolve(config, cliPublicKeyPath, System::getenv);
    }

    static Result<List<SshPublicKey>> resolve(ClusterBootstrapConfig config,
                                              Option<String> cliPublicKeyPath,
                                              Fn1<String, String> envLookup) {
        var paths = resolvePaths(config, cliPublicKeyPath, envLookup);

        if (paths.isEmpty()) {
            return Result.success(List.of());
        }

        return readAndParse(paths);
    }

    static Result<List<SshPublicKey>> resolveOrFailIfCloud(ClusterBootstrapConfig config,
                                                           Option<String> cliPublicKeyPath) {
        return resolveOrFailIfCloud(config, cliPublicKeyPath, System::getenv);
    }

    static Result<List<SshPublicKey>> resolveOrFailIfCloud(ClusterBootstrapConfig config,
                                                           Option<String> cliPublicKeyPath,
                                                           Fn1<String, String> envLookup) {
        return resolve(config, cliPublicKeyPath, envLookup).flatMap(keys -> guardCloudHasKeys(config, keys));
    }

    private static Result<List<SshPublicKey>> guardCloudHasKeys(ClusterBootstrapConfig config,
                                                                List<SshPublicKey> keys) {
        if (!keys.isEmpty()) {
            return Result.success(keys);
        }

        if (!hasCloudSource(config)) {
            return Result.success(keys);
        }

        return new MissingSshKey().result();
    }

    private static boolean hasCloudSource(ClusterBootstrapConfig config) {
        return config.sources()
                     .values()
                     .stream()
                     .anyMatch(s -> s.type() == SourceType.CLOUD);
    }

    private static List<String> resolvePaths(ClusterBootstrapConfig config,
                                             Option<String> cliPublicKeyPath,
                                             Fn1<String, String> envLookup) {
        var fromCli = cliPublicKeyPath.filter(s -> !s.isBlank()).map(s -> List.of(s));

        if (fromCli.isPresent()) {
            return fromCli.unwrap();
        }

        var fromToml = config.infrastructure().ssh().map(s -> s.publicKeyFiles()).filter(list -> !list.isEmpty());

        if (fromToml.isPresent()) {
            return fromToml.unwrap();
        }

        return envFallback(envLookup);
    }

    private static List<String> envFallback(Fn1<String, String> envLookup) {
        var envValue = envLookup.apply(AETHER_SSH_KEY_ENV);

        if (envValue == null || envValue.isBlank()) {
            return List.of();
        }

        return List.of(envValue + ".pub");
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<List<SshPublicKey>> readAndParse(List<String> paths) {
        var lines = new ArrayList<String>();

        for (var p : paths) {
            var read = readPublicKeyFile(p);

            if (read.isFailure()) {
                return read.map(_ -> List.<SshPublicKey> of());
            }

            var _ = read.onSuccess(lines::add);
        }

        return SshPublicKey.parseAll(List.copyOf(lines));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<String> readPublicKeyFile(String path) {
        return Result.lift(e -> new ReadFailed(path, e),
                           () -> Files.readString(Path.of(expandHome(path))));
    }

    private static String expandHome(String path) {
        if (path.startsWith("~/")) {
            return System.getProperty("user.home") + path.substring(1);
        }

        return path;
    }

    record ReadFailed(String path, Throwable cause) implements Cause {
        @Override
        public String message() {
            return "Failed to read SSH public key file '" + path + "': " + cause.getMessage();
        }
    }

    record MissingSshKey() implements Cause {
        @Override
        public String message() {
            return "Cluster has cloud source(s) but no SSH public key could be resolved. "
                 + "Provide one of: (1) --ssh-public-key <path> CLI flag, "
                 + "(2) [infrastructure.ssh] public_key_file = \"<path>\" in TOML, "
                 + "(3) AETHER_SSH_KEY env var (uses $AETHER_SSH_KEY.pub). "
                 + "Without a key, provisioned VMs will be unreachable via SSH.";
        }
    }
}
