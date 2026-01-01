package org.pragmatica.aether.artifact;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

public record Artifact(GroupId groupId, ArtifactId artifactId, Version version) {
    private static final Fn1<Cause, String>INVALID_FORMAT = Causes.forOneValue("Invalid artifact format %s");

    public static Result<Artifact> artifact(String artifactString) {
        var parts = artifactString.split(":", 3);
        if (parts.length != 3) {
            return INVALID_FORMAT.apply(artifactString)
                                 .result();
        }
        return Result.all(GroupId.groupId(parts[0]),
                          ArtifactId.artifactId(parts[1]),
                          Version.version(parts[2]))
                     .map(Artifact::artifact);
    }

    public static Artifact artifact(GroupId groupId, ArtifactId artifactId, Version version) {
        return new Artifact(groupId, artifactId, version);
    }

    @Override
    public String toString() {
        return asString();
    }

    public String asString() {
        return groupId + ":" + artifactId + ":" + version.withQualifier();
    }
}
