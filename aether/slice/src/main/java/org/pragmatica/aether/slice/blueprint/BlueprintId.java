package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

/**
 * Blueprint identifier using Maven artifact coordinates (RFC-0005).
 * Format: groupId:artifactId:version (e.g., "org.example:commerce:1.0.0")
 */
public record BlueprintId(Artifact artifact) {
    private static final Fn1<Cause, String> INVALID_FORMAT = Causes.forOneValue("Invalid blueprint ID format: %s");

    public static Result<BlueprintId> blueprintId(String input) {
        return Artifact.artifact(input)
                       .mapError(_ -> INVALID_FORMAT.apply(input))
                       .map(BlueprintId::new);
    }

    @Override
    public String toString() {
        return asString();
    }

    public String asString() {
        return artifact.asString();
    }
}
