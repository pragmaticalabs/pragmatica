package org.pragmatica.aether.slice.repository;

import org.pragmatica.aether.artifact.Artifact;

import java.net.URL;

public record Location(Artifact artifact, URL url) {
    public static Location location(Artifact artifact, URL url) {
        return new Location(artifact, url);
    }

    @Override
    public String toString() {
        return artifact.toString() + "@" + url.toString();
    }
}
