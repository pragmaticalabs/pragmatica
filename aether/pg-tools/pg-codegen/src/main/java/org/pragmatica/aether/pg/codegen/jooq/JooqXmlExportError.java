// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.pg.codegen.jooq;

import org.pragmatica.lang.Cause;


public sealed interface JooqXmlExportError extends Cause {
    record MissingSchema(String schemaName) implements JooqXmlExportError {
        @Override
        public String message() {
            return "Schema '" + schemaName + "' not found in input";
        }
    }

    record MarshalFailed(String detail) implements JooqXmlExportError {
        @Override
        public String message() {
            return "XML marshalling failed: " + detail;
        }
    }

    record IoError(String detail) implements JooqXmlExportError {
        @Override
        public String message() {
            return "I/O error writing XML: " + detail;
        }
    }
}
