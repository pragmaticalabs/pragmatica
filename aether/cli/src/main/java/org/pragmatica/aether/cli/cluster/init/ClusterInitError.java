// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster.init;

import org.pragmatica.lang.Cause;


public sealed interface ClusterInitError extends Cause {
    record TooFewNodes(int got) implements ClusterInitError {
        @Override
        public String message() {
            return "Aether requires at least 3 nodes for consensus quorum (got " + got
                 + "). "
                 + "For local single-process dev/test, use --target forge.";
        }
    }

    record InvalidTopology(String detail) implements ClusterInitError {
        @Override
        public String message() {
            return "Invalid topology: " + detail;
        }
    }

    record OutputExists(String path) implements ClusterInitError {
        @Override
        public String message() {
            return "Output file already exists: " + path + ". Re-run with --force to overwrite.";
        }
    }

    record MissingField(String name) implements ClusterInitError {
        @Override
        public String message() {
            return "Required field missing or invalid: " + name;
        }
    }

    record InvalidValue(String field, String got, String expected) implements ClusterInitError {
        @Override
        public String message() {
            return "Invalid " + field + " '" + got + "': " + expected;
        }
    }

    enum Aborted implements ClusterInitError {
        INSTANCE;
        @Override
        public String message() {
            return "Wizard aborted by operator.";
        }
    }

    record ConfigValidationFailed(String detail) implements ClusterInitError {
        @Override
        public String message() {
            return "Generated configuration failed validation: " + detail;
        }
    }

    record IoFailure(String path, String reason) implements ClusterInitError {
        @Override
        public String message() {
            return "Failed to write " + path + ": " + reason;
        }
    }
}
