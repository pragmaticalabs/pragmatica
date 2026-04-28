// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.codegen.jooq;

import org.pragmatica.lang.Cause;


public sealed interface JooqXmlExportError extends Cause {
    record MissingSchema(String schemaName) implements JooqXmlExportError {
        @Override public String message() {
            return "Schema '" + schemaName + "' not found in input";
        }
    }

    record MarshalFailed(String detail) implements JooqXmlExportError {
        @Override public String message() {
            return "XML marshalling failed: " + detail;
        }
    }

    record IoError(String detail) implements JooqXmlExportError {
        @Override public String message() {
            return "I/O error writing XML: " + detail;
        }
    }
}
