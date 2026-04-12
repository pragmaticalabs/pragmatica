package org.pragmatica.aether.pg.codegen.jooq;

import org.pragmatica.lang.Cause;


/// Error types for jOOQ XML export.
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
