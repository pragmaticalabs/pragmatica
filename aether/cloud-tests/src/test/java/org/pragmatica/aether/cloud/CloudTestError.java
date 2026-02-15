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
