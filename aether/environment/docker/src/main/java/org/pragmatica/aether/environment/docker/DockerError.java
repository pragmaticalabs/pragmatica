// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment.docker;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;


public sealed interface DockerError extends Cause {
    Fn1<DockerError, Throwable> COMMAND_EXECUTION_FAILED = CommandExecutionFailed::new;

    record CommandExecutionFailed(Throwable cause) implements DockerError {
        @Override
        public String message() {
            return "Docker command execution failed: " + cause.getMessage();
        }
    }

    record ContainerNotFound(String containerId) implements DockerError {
        @Override
        public String message() {
            return "Docker container not found: " + containerId;
        }
    }

    record InvalidCommandOutput(String detail) implements DockerError {
        @Override
        public String message() {
            return "Invalid Docker command output: " + detail;
        }
    }
}
