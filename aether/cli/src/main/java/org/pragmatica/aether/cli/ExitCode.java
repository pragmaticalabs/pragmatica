// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

public sealed interface ExitCode {
    int SUCCESS = 0;

    int ERROR = 1;

    int TIMEOUT = 2;

    int NOT_FOUND = 3;

    int USAGE = 64;

    record unused() implements ExitCode{}
}
