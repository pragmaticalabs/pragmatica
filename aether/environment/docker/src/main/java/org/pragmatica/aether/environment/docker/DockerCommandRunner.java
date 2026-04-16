// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment.docker;

import org.pragmatica.lang.Promise;

import java.util.List;


/// Abstraction for executing Docker CLI commands.
/// Enables testing by allowing injection of a stub implementation.
public interface DockerCommandRunner {
    Promise<String> execute(List<String> command);
}
