// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.codegen;

import org.pragmatica.lang.Cause;


public sealed interface CodegenError extends Cause {
    record UnsupportedType(String typeName) implements CodegenError {
        @Override
        public String message() {
            return "Unsupported PostgreSQL type: " + typeName;
        }
    }

    record GenerationFailed(String detail) implements CodegenError {
        @Override
        public String message() {
            return "Code generation failed: " + detail;
        }
    }

    record IoError(String detail) implements CodegenError {
        @Override
        public String message() {
            return "I/O error: " + detail;
        }
    }
}
