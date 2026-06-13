package org.pragmatica.jbct.doc;

import org.pragmatica.lang.Cause;

import java.util.List;

/// Errors for the `doc` command source-header pipeline.
/// Named using past tense per JBCT error naming convention.
public sealed interface DocError extends Cause {
    /// Source for the requested name could not be resolved through any strategy.
    record SourceNotFound(String name, List<String> attempts) implements DocError {
        public static SourceNotFound sourceNotFound(String name, List<String> attempts) {
            return new SourceNotFound(name, List.copyOf(attempts));
        }

        @Override
        public String message() {
            return "Could not resolve source for '" + name + "'. Attempted:\n  - " + String.join("\n  - ", attempts);
        }
    }

    /// Source was located but contains no class-level `///` header block.
    record HeaderMissing(String name) implements DocError {
        public static HeaderMissing headerMissing(String name) {
            return new HeaderMissing(name);
        }

        @Override
        public String message() {
            return "No class-level documentation header found for '" + name + "'";
        }
    }
}
