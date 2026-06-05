// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.stream;

/// Spec event-stream-namespaces §8.6 exit codes for `aether stream` commands.
///
/// Distinct from the global [org.pragmatica.aether.cli.ExitCode] constants — those serve the
/// generic CLI surface (e.g., `NOT_FOUND` is 3 there for missing `--field` extractions). The
/// `aether stream` surface speaks to operator scripts that branch on HTTP-shape errors
/// (404/409/410) and on user-cancelled interactive confirmations, and the spec pins specific
/// numbers per command. Keeping these constants local keeps the global set untouched.
public sealed interface StreamExitCode {
    /// Successful operation.
    int SUCCESS = 0;

    /// Generic failure: network, server 5xx, deserialization, etc.
    int ERROR = 1;

    /// Address parse failure or malformed CLI argument.
    int VALIDATION = 2;

    /// User declined an interactive confirmation prompt.
    int USER_CANCELLED = 3;

    /// HTTP 404 — stream/version/group not found.
    int NOT_FOUND = 4;

    /// HTTP 409 — conflicting state (e.g. group already exists).
    int CONFLICT = 5;

    /// HTTP 410 — gone (e.g. stream version was purged).
    int GONE = 6;

    record unused() implements StreamExitCode {}
}
