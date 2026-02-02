package org.pragmatica.aether.artifact;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import java.util.regex.Pattern;

import static org.pragmatica.lang.Verify.ensure;

public record ArtifactId(String id) {
    public static Result<ArtifactId> artifactId(String id) {
        return Result.all(ensure(id, Verify.Is::matches, ARTIFACT_ID_PATTERN))
                     .map(ArtifactId::new);
    }

    @Override
    public String toString() {
        return id;
    }

    private static final Pattern ARTIFACT_ID_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
}
