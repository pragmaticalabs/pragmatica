// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// The host port a docker node's management API is published on (#2089).
///
/// The operator's CLI is outside the docker network, where a container name does not resolve, and a bridge IP is not reachable on Docker
/// Desktop. A published port is: bootstrap asks the provider to publish each node's management port (`expose_host_ports`) and reads the
/// mapped host port back with `docker port`. Each node gets its own, so the address of a docker node is `127.0.0.1:<mapped>`.
final class DockerHostPorts {
    /// The in-container management port the provider publishes.
    static final int CONTAINER_MANAGEMENT_PORT = 8080;
    private static final long DOCKER_PORT_TIMEOUT_SECONDS = 20L;
    /// Test seam: replaces the `docker port` call. Null in production.
    static volatile Function<String, Result<Integer>> override;
    /// Test seam: replaces the `docker inspect` name lookup. Null in production.
    static volatile Function<String, Result<String>> nameOverride;

    private DockerHostPorts() {}

    /// The container's name, which is the cluster node id the cluster knows it by: lets destroy name a ledger container the cluster did not list.
    static Result<String> containerName(String containerId) {
        var override = DockerHostPorts.nameOverride;

        return override != null
               ? override.apply(containerId)
               : dockerName(containerId);
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<String> dockerName(String containerId) {
        try {
            var process = new ProcessBuilder("docker", "inspect", "--format", "{{.Name}}", containerId).redirectErrorStream(true)
                                                                                                       .start();
            var finished = process.waitFor(DOCKER_PORT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            var output = new String(process.getInputStream().readAllBytes(),
                                    StandardCharsets.UTF_8).strip();

            if (!finished) {
                process.destroyForcibly();

                return Causes.cause("`docker inspect " + containerId
                                   + "` did not answer within " + DOCKER_PORT_TIMEOUT_SECONDS
                                   + "s").result();
            }

            return process.exitValue() == 0 && !output.isEmpty()
                   ? Result.success(output.startsWith("/")
                                    ? output.substring(1)
                                    : output)
                   : Causes.cause("`docker inspect " + containerId + "` failed: " + output).result();
        } catch (IOException e) {
            return Causes.cause("could not run docker inspect: " + e.getMessage()).result();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return Causes.cause("interrupted running docker inspect").result();
        }
    }

    static Result<Integer> managementPort(String containerId) {
        var override = DockerHostPorts.override;

        return override != null
               ? override.apply(containerId)
               : dockerPort(containerId);
    }

    /// `docker port <id> 8080/tcp` prints one `addr:port` line per bound address (`0.0.0.0:55001`, `[::]:55001`); the first is enough.
    static Result<Integer> parse(String output) {
        var first = output.lines().map(String::strip).filter(line -> !line.isEmpty()).findFirst().orElse("");
        var colon = first.lastIndexOf(':');

        if (colon < 0 || colon == first.length() - 1 || !first.substring(colon + 1).chars().allMatch(Character::isDigit)) {
            return Causes.cause("no published management port in `docker port` output: '" + output.strip() + "'").result();
        }

        return Result.success(Integer.parseInt(first.substring(colon + 1)));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<Integer> dockerPort(String containerId) {
        try {
            var process = new ProcessBuilder("docker", "port", containerId, CONTAINER_MANAGEMENT_PORT + "/tcp").redirectErrorStream(true)
                                                                                                               .start();
            var finished = process.waitFor(DOCKER_PORT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            var output = new String(process.getInputStream().readAllBytes(),
                                    StandardCharsets.UTF_8);

            if (!finished) {
                process.destroyForcibly();

                return Causes.cause("`docker port " + containerId
                                   + "` did not finish in " + DOCKER_PORT_TIMEOUT_SECONDS
                                   + " s").result();
            }

            return process.exitValue() == 0
                   ? parse(output)
                   : Causes.cause("`docker port " + containerId
                                 + "` exited " + process.exitValue()
                                 + ": " + output.strip()).result();
        } catch (IOException e) {
            return Causes.cause("could not run `docker port`: " + e.getMessage()).result();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return Causes.cause("interrupted waiting for `docker port`").result();
        }
    }
}
