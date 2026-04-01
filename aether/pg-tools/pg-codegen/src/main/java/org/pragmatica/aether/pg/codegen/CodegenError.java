package org.pragmatica.aether.pg.codegen;

import org.pragmatica.lang.Cause;

/// Error types for code generation.
public sealed interface CodegenError extends Cause {

    record UnsupportedType(String typeName) implements CodegenError {
        @Override public String message() { return "Unsupported PostgreSQL type: " + typeName; }
    }

    record GenerationFailed(String detail) implements CodegenError {
        @Override public String message() { return "Code generation failed: " + detail; }
    }

    record IoError(String detail) implements CodegenError {
        @Override public String message() { return "I/O error: " + detail; }
    }
}
