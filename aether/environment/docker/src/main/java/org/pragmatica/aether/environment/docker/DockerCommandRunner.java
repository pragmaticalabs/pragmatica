// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment.docker;

import java.util.List;

import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.lang.Promise;


public interface DockerCommandRunner {
    Promise<String> execute(List<String> command);

    /// The same command with `stdin` written to the process (e.g. `docker cp - <container>:<dir>` reading a tar archive), so a
    /// payload that must not be on any argv travels on a pipe (#828). A runner that cannot do this refuses by name.
    default Promise<String> execute(List<String> command, byte[] stdin) {
        return EnvironmentError.operationNotSupported("command with stdin").promise();
    }
}
