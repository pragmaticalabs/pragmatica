/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.pragmatica.aether.cloud;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/// Static utility for SSH and SCP operations via system commands.
/// All methods use ProcessBuilder with strict host key checking disabled.
public sealed interface RemoteCommandRunner {

    Logger log = LoggerFactory.getLogger(RemoteCommandRunner.class);

    /// Common SSH flags for non-interactive, key-based connections.
    List<String> SSH_FLAGS = List.of(
        "-o", "StrictHostKeyChecking=no",
        "-o", "UserKnownHostsFile=/dev/null",
        "-o", "ConnectTimeout=10"
    );

    /// Executes a command on a remote host via SSH.
    static Result<String> ssh(String host, String command, Path privateKeyPath) {
        var args = new java.util.ArrayList<>(List.of("ssh", "-i", privateKeyPath.toString()));
        args.addAll(SSH_FLAGS);
        args.add("root@" + host);
        args.add(command);

        return executeProcess(args, 120);
    }

    /// Copies a local file to a remote host via SCP.
    static Result<Unit> scp(Path localFile, String host, String remotePath, Path privateKeyPath) {
        var args = new java.util.ArrayList<>(List.of("scp", "-i", privateKeyPath.toString()));
        args.addAll(SSH_FLAGS);
        args.add(localFile.toString());
        args.add("root@" + host + ":" + remotePath);

        return executeProcess(args, 300).mapToUnit();
    }

    /// Waits until SSH connectivity is established to the given host.
    /// Polls every 5 seconds until the timeout is reached.
    static Result<Unit> waitForSsh(String host, Path privateKeyPath, Duration timeout) {
        var deadline = System.currentTimeMillis() + timeout.toMillis();

        while (System.currentTimeMillis() < deadline) {
            var result = ssh(host, "echo ok", privateKeyPath);

            if (result.isSuccess()) {
                log.info("SSH connection established to {}", host);
                return Result.unitResult();
            }

            log.debug("SSH not yet available on {}, retrying in 5s...", host);
            sleepQuietly(5000);
        }

        return new CloudTestError.SshTimeout(host, timeout).result();
    }

    // --- Leaf: execute a process and capture output ---

    private static Result<String> executeProcess(List<String> command, int timeoutSeconds) {
        try {
            var process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();

            var completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!completed) {
                process.destroyForcibly();
                return new CloudTestError.CommandTimeout(String.join(" ", command), timeoutSeconds).result();
            }

            var output = new String(process.getInputStream().readAllBytes()).trim();

            if (process.exitValue() != 0) {
                return new CloudTestError.CommandFailed(String.join(" ", command), process.exitValue(), output).result();
            }

            return Result.success(output);
        } catch (IOException | InterruptedException e) {
            return new CloudTestError.CommandException(String.join(" ", command), e).result();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    record unused() implements RemoteCommandRunner {}
}
