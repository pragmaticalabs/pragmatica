// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster.init;

import org.pragmatica.lang.Cause;

/// Errors produced by the cluster init wizard. Sealed so callers can pattern-match
/// when they need to render context-specific guidance.
public sealed interface ClusterInitError extends Cause {

    /// Aether requires at least 3 nodes for consensus quorum.
    record TooFewNodes(int got) implements ClusterInitError {
        @Override public String message() {
            return "Aether requires at least 3 nodes for consensus quorum (got " + got + "). "
                   + "For local single-process dev/test, use --target forge.";
        }
    }

    /// Invalid topology choice — core count out of range, even, etc.
    record InvalidTopology(String detail) implements ClusterInitError {
        @Override public String message() {
            return "Invalid topology: " + detail;
        }
    }

    /// Output file already exists and overwrite was not authorised.
    record OutputExists(String path) implements ClusterInitError {
        @Override public String message() {
            return "Output file already exists: " + path + ". Re-run with --force to overwrite.";
        }
    }

    /// Required field was missing or invalid in batch mode.
    record MissingField(String name) implements ClusterInitError {
        @Override public String message() {
            return "Required field missing or invalid: " + name;
        }
    }

    /// Invalid CIDR / IP / hostname / port etc.
    record InvalidValue(String field, String got, String expected) implements ClusterInitError {
        @Override public String message() {
            return "Invalid " + field + " '" + got + "': " + expected;
        }
    }

    /// Operator aborted the wizard.
    record Aborted() implements ClusterInitError {
        @Override public String message() {
            return "Wizard aborted by operator.";
        }
    }

    /// Generated config failed validation by ClusterBootstrapConfigValidator.
    record ConfigValidationFailed(String detail) implements ClusterInitError {
        @Override public String message() {
            return "Generated configuration failed validation: " + detail;
        }
    }

    /// Filesystem I/O failure during write.
    record IoFailure(String path, String reason) implements ClusterInitError {
        @Override public String message() {
            return "Failed to write " + path + ": " + reason;
        }
    }
}
