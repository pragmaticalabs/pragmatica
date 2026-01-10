package org.pragmatica.aether.slice.repository;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.net.URL;

public record Location(Artifact artifact, URL url) {
    private static final Cause NULL_ARTIFACT = Causes.cause("Artifact cannot be null");
    private static final Cause NULL_URL = Causes.cause("URL cannot be null");

    public static Result<Location> location(Artifact artifact, URL url) {
        if (artifact == null) {
            return NULL_ARTIFACT.result();
        }
        if (url == null) {
            return NULL_URL.result();
        }
        return Result.success(new Location(artifact, url));
    }

    @Override
    public String toString() {
        return artifact.toString() + "@" + url.toString();
    }
}
