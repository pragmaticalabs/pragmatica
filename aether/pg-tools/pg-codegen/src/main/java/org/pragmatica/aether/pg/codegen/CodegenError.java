// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
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
