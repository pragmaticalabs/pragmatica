package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Cause;

/**
 * Errors that can occur during blueprint expansion.
 */
public sealed interface ExpanderError extends Cause {
    /**
     * Artifact mismatch between requested and manifest-declared artifact.
     */
    record ArtifactMismatch(Artifact requested, Artifact declared) implements ExpanderError {
        public static ArtifactMismatch cause(Artifact requested, Artifact declared) {
            return new ArtifactMismatch(requested, declared);
        }

        @Override
        public String message() {
            return "Artifact mismatch: requested " + requested.asString() + " but JAR manifest declares " + declared.asString();
        }
    }
}
