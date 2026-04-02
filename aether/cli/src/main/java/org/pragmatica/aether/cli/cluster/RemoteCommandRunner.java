package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.config.cluster.SshConfig;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/// SSH and SCP operations via system ProcessBuilder for on-premises bootstrap.
///
/// Uses the host system's `ssh` and `scp` commands with strict host key checking
/// disabled for automated provisioning.
@SuppressWarnings({"JBCT-PAT-01", "JBCT-SEQ-01", "JBCT-RET-01", "JBCT-EX-01"})
sealed interface RemoteCommandRunner {
    record unused() implements RemoteCommandRunner{}

    int DEFAULT_COMMAND_TIMEOUT_SECONDS = 120;
    int SCP_TIMEOUT_SECONDS = 300;
    int SSH_POLL_INTERVAL_MS = 5000;

    List<String> SSH_FLAGS = List.of("-o",
                                     "StrictHostKeyChecking=no",
                                     "-o",
                                     "UserKnownHostsFile=/dev/null",
                                     "-o",
                                     "ConnectTimeout=10");

    /// Execute a command on a remote host via SSH.
    static Result<String> ssh(String host, String command, SshConfig config) {
        var args = buildSshCommand(host, config);
        args.add(command);
        return executeProcess(args, DEFAULT_COMMAND_TIMEOUT_SECONDS);
    }

    /// Copy a local file to a remote host via SCP.
    static Result<Unit> scp(String localPath, String host, String remotePath, SshConfig config) {
        var args = buildScpCommand(localPath, host, remotePath, config);
        return executeProcess(args, SCP_TIMEOUT_SECONDS).mapToUnit();
    }

    /// Wait until SSH connectivity is established to the given host.
    static Result<Unit> waitForSsh(String host, SshConfig config, Duration timeout) {
        var deadline = System.currentTimeMillis() + timeout.toMillis();
        while ( System.currentTimeMillis() < deadline) {
            var result = ssh(host, "echo ok", config);
            if ( result.isSuccess()) {
                System.out.printf("  SSH connection established to %s%n", host);
                return Result.unitResult();
            }
            System.out.printf("  SSH not yet available on %s, retrying in 5s...%n", host);
            sleepQuietly(SSH_POLL_INTERVAL_MS);
        }
        return new RemoteCommandError.SshTimeout(host, timeout).result();
    }

    private static ArrayList<String> buildSshCommand(String host, SshConfig config) {
        var args = new ArrayList<>(List.of("ssh",
                                           "-i",
                                           config.keyPath(),
                                           "-p",
                                           String.valueOf(config.port())));
        args.addAll(SSH_FLAGS);
        args.add(config.user() + "@" + host);
        return args;
    }

    private static ArrayList<String> buildScpCommand(String localPath,
                                                     String host,
                                                     String remotePath,
                                                     SshConfig config) {
        var args = new ArrayList<>(List.of("scp",
                                           "-i",
                                           config.keyPath(),
                                           "-P",
                                           String.valueOf(config.port())));
        args.addAll(SSH_FLAGS);
        args.add(localPath);
        args.add(config.user() + "@" + host + ":" + remotePath);
        return args;
    }

    private static Result<String> executeProcess(List<String> command, int timeoutSeconds) {
        try {
            var process = new ProcessBuilder(command).redirectErrorStream(true)
                                                     .start();
            var completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if ( !completed) {
                process.destroyForcibly();
                return new RemoteCommandError.CommandTimeout(String.join(" ", command), timeoutSeconds).result();
            }
            var output = new String(process.getInputStream().readAllBytes()).trim();
            if ( process.exitValue() != 0) {
            return new RemoteCommandError.CommandFailed(String.join(" ", command), process.exitValue(), output).result();}
            return Result.success(output);
        }


        catch (Exception e) {
            return new RemoteCommandError.CommandException(String.join(" ", command), e).result();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        }


        catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    /// Error causes for remote command operations.
    sealed interface RemoteCommandError extends Cause {
        record SshTimeout(String host, Duration timeout) implements RemoteCommandError {
            @Override public String message() {
                return "SSH connection to " + host + " timed out after " + timeout;
            }
        }

        record CommandFailed(String command, int exitCode, String output) implements RemoteCommandError {
            @Override public String message() {
                return "Command failed (exit " + exitCode + "): " + command + " - " + output;
            }
        }

        record CommandTimeout(String command, int timeoutSeconds) implements RemoteCommandError {
            @Override public String message() {
                return "Command timed out after " + timeoutSeconds + "s: " + command;
            }
        }

        record CommandException(String command, Throwable cause) implements RemoteCommandError {
            @Override public String message() {
                return "Command failed with exception: " + command + " - " + Causes.fromThrowable(cause).message();
            }
        }
    }
}
