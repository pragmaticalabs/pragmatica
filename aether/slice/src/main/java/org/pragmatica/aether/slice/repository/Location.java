package org.pragmatica.aether.slice.repository;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.net.URL;

import static org.pragmatica.lang.Verify.Is;
import static org.pragmatica.lang.Verify.ensure;

public record Location(Artifact artifact, URL url) {
    private static final Cause NULL_ARTIFACT = Causes.cause("Artifact cannot be null");
    private static final Cause NULL_URL = Causes.cause("URL cannot be null");

    public static Result<Location> location(Artifact artifact, URL url) {
        return Result.all(ensure(artifact, Is::notNull, NULL_ARTIFACT),
                          ensure(url, Is::notNull, NULL_URL))
                     .map(Location::new);
    }

    @Override
    public String toString() {
        return artifact.toString() + "@" + url.toString();
    }
}
