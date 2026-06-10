// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.LoadBalancerMode;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.pragmatica.lang.Result.success;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
sealed interface PreflightChecker {
    record unused() implements PreflightChecker {}

    int SSH_CONNECT_TIMEOUT_MS = 5000;
    int DEFAULT_SSH_PORT = 22;

    static Result<ClusterBootstrapConfig> runDefault(ClusterBootstrapConfig config) {
        config.sources().forEach(PreflightChecker::checkCloudCredentialsQuietly);

        return success(config);
    }

    static Result<ClusterBootstrapConfig> runFull(ClusterBootstrapConfig config) {
        var results = new ArrayList<PreflightResult>();

        config.sources().forEach((name, source) -> runSourceChecks(name, source, results));
        printResults(results);

        return evaluateResults(config, results);
    }

    private static void runSourceChecks(String name, SourceProfile source, List<PreflightResult> results) {
        switch (source.type()) {
            case CLOUD -> runCloudChecks(name, source, results);
            case SSH -> runSshChecks(name, source, results);
            case FORGE -> runForgeChecks(name, results);
            case DOCKER -> runDockerChecks(name, results);
        }

        checkFloatingIpIfElected(name, source, results);
    }

    @Contract
    private static void checkCloudCredentialsQuietly(String name, SourceProfile source) {
        if (source.type() != SourceType.CLOUD) {
            return;
        }

        ProviderResolver.resolveCloudCompute(source).onFailure(cause -> System.out.println("  WARN: " + name
                                                                                          + ": Cloud credential check failed: " + cause.message()));
    }

    @Contract
    private static void runCloudChecks(String name, SourceProfile source, List<PreflightResult> results) {
        ProviderResolver.resolveCloudCompute(source).onSuccess(compute -> results.add(preflightResult(name,
                                                                                                      "PF-03",
                                                                                                      CheckStatus.PASS,
                                                                                                      "Cloud provider resolved successfully"))).onFailure(cause -> results.add(preflightResult(name,
                                                                                                                                                                                               "PF-03",
                                                                                                                                                                                               CheckStatus.FAIL,
                                                                                                                                                                                               "Cloud provider resolution failed: " + cause.message())));
    }

    @Contract
    private static void runSshChecks(String name, SourceProfile source, List<PreflightResult> results) {
        var port = source.sshPort().or(DEFAULT_SSH_PORT);

        source.roles().values().forEach(sub -> sub.hosts()
                                                  .onPresent(hosts -> hosts.forEach(host -> checkSshHost(name,
                                                                                                         host,
                                                                                                         port,
                                                                                                         results))));
    }

    @Contract
    private static void checkSshHost(String name, String host, int port, List<PreflightResult> results) {
        if (isTcpReachable(host, port)) {
            results.add(preflightResult(name, "PF-05", CheckStatus.PASS, "Host " + host + " reachable on port " + port));
        } else {
            results.add(preflightResult(name, "PF-05", CheckStatus.FAIL, "Host " + host + " unreachable on port " + port));
        }
    }

    @Contract
    private static void runForgeChecks(String name, List<PreflightResult> results) {
        checkDockerCli(name, "PF-07", results);
    }

    @Contract
    private static void runDockerChecks(String name, List<PreflightResult> results) {
        checkDockerCli(name, "PF-07", results);
    }

    @Contract
    private static void checkDockerCli(String name, String checkId, List<PreflightResult> results) {
        if (isCommandAvailable("docker", "version")) {
            results.add(preflightResult(name, checkId, CheckStatus.PASS, "Docker CLI available"));
        } else {
            results.add(preflightResult(name, checkId, CheckStatus.FAIL, "Docker CLI not available or not responding"));
        }
    }

    @Contract
    private static void checkFloatingIpIfElected(String name, SourceProfile source, List<PreflightResult> results) {
        if (source.loadBalancer() != LoadBalancerMode.ELECTED) {
            return;
        }

        ProviderResolver.resolveFloatingIpProvider(source).onSuccess(fip -> results.add(preflightResult(name,
                                                                                                        "PF-12",
                                                                                                        CheckStatus.PASS,
                                                                                                        "Floating IP provider resolved"))).onFailure(cause -> results.add(preflightResult(name,
                                                                                                                                                                                          "PF-12",
                                                                                                                                                                                          CheckStatus.WARN,
                                                                                                                                                                                          "Floating IP provider not available: " + cause.message())));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static boolean isTcpReachable(String host, int port) {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), SSH_CONNECT_TIMEOUT_MS);

            return true;
        } catch (IOException _) {
            return false;
        }
    }

    @SuppressWarnings("JBCT-EX-01")
    private static boolean isCommandAvailable(String... command) {
        try {
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            var exitCode = process.waitFor();

            return exitCode == 0;
        } catch (IOException | InterruptedException _) {
            return false;
        }
    }

    @Contract
    private static void printResults(List<PreflightResult> results) {
        results.forEach(PreflightChecker::printSingleResult);
    }

    @Contract
    private static void printSingleResult(PreflightResult result) {
        System.out.printf("  [%s] %s: %s %s%n",
                          result.status().label(),
                          result.sourceName(),
                          result.checkId(),
                          result.message());
    }

    private static Result<ClusterBootstrapConfig> evaluateResults(ClusterBootstrapConfig config,
                                                                  List<PreflightResult> results) {
        var failures = results.stream().filter(PreflightResult::isFail).toList();

        if (failures.isEmpty()) {
            return success(config);
        }

        return new PreflightError.Failed(formatFailures(failures)).result();
    }

    private static String formatFailures(List<PreflightResult> failures) {
        return failures.stream()
                       .map(PreflightResult::summary)
                       .collect(Collectors.joining("; "));
    }

    private static PreflightResult preflightResult(String sourceName,
                                                   String checkId,
                                                   CheckStatus status,
                                                   String message) {
        return new PreflightResult(sourceName, checkId, status, message);
    }

    record PreflightResult(String sourceName, String checkId, CheckStatus status, String message) {
        boolean isFail() {
            return status == CheckStatus.FAIL;
        }

        String summary() {
            return sourceName + "/" + checkId + ": " + message;
        }
    }

    enum CheckStatus {
        PASS("PASS"),
        WARN("WARN"),
        FAIL("FAIL");
        private final String label;
        CheckStatus(String label) {
            this.label = label;
        }
        String label() {
            return label;
        }
    }

    sealed interface PreflightError extends Cause {
        record Failed(String detail) implements PreflightError {
            @Override
            public String message() {
                return "Pre-flight checks failed: " + detail;
            }
        }
    }
}
