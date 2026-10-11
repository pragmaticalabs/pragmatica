// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli;

public sealed interface ExitCode {
    int SUCCESS = 0;
    int ERROR = 1;
    int TIMEOUT = 2;
    int NOT_FOUND = 3;
    int CLEANUP_FAILED = 4;
    int USAGE = 64;

    record unused() implements ExitCode {}
}
