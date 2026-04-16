// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.cloud;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;

import java.time.Duration;

/// Error causes for cloud integration test operations.
public sealed interface CloudTestError extends Cause {

    /// SSH connection timed out waiting for host.
    record SshTimeout(String host, Duration timeout) implements CloudTestError {
        @Override
        public String message() {
            return "SSH connection to " + host + " timed out after " + timeout;
        }
    }

    /// Remote command exited with non-zero status.
    record CommandFailed(String command, int exitCode, String output) implements CloudTestError {
        @Override
        public String message() {
            return "Command failed (exit " + exitCode + "): " + command + " - " + output;
        }
    }

    /// Remote command exceeded time limit.
    record CommandTimeout(String command, int timeoutSeconds) implements CloudTestError {
        @Override
        public String message() {
            return "Command timed out after " + timeoutSeconds + "s: " + command;
        }
    }

    /// Exception during command execution.
    record CommandException(String command, Throwable cause) implements CloudTestError {
        @Override
        public String message() {
            return "Command failed with exception: " + command + " - " + Causes.fromThrowable(cause).message();
        }
    }

    /// HTTP request to cloud node failed.
    record HttpRequestFailed(String url, String detail) implements CloudTestError {
        @Override
        public String message() {
            return "HTTP request to " + url + " failed: " + detail;
        }
    }
}
