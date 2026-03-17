package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Cause;

import java.util.List;

/// Errors that can occur during blueprint expansion.
public sealed interface ExpanderError extends Cause {
    /// Artifact mismatch between requested and manifest-declared artifact.
    record ArtifactMismatch(Artifact requested, Artifact declared) implements ExpanderError {
        public static ArtifactMismatch artifactMismatch(Artifact requested, Artifact declared) {
            return new ArtifactMismatch(requested, declared);
        }

        @Override
        public String message() {
            return "Artifact mismatch: requested " + requested.asString() + " but JAR manifest declares " + declared.asString();
        }
    }

    /// Publisher topics with no subscribers in the blueprint.
    record OrphanPublishers(List<String> topics) implements ExpanderError {
        public static OrphanPublishers orphanPublishers(List<String> topics) {
            return new OrphanPublishers(List.copyOf(topics));
        }

        @Override
        public String message() {
            return "Publisher topics with no subscribers in blueprint: " + String.join(", ", topics);
        }
    }

    record unused() implements ExpanderError {
        @Override
        public String message() {
            return "unused";
        }
    }
}
