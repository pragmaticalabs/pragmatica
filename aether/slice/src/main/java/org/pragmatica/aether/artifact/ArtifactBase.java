package org.pragmatica.aether.artifact;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.Codec;


/// Version-agnostic artifact identifier.
///
///
/// Used for operations that apply to all versions of an artifact, such as
/// rolling updates where both old and new versions are managed together.
///
///
/// Format: groupId:artifactId (e.g., "org.pragmatica-lite.aether:example-slice")
@Codec public record ArtifactBase(GroupId groupId, ArtifactId artifactId) {
    private static final Fn1<Cause, String> INVALID_FORMAT = Causes.forOneValue("Invalid artifact base format %s");

    public static Result<ArtifactBase> artifactBase(String artifactBaseString) {
        var parts = artifactBaseString.split(":", 2);
        if (parts.length != 2) {return INVALID_FORMAT.apply(artifactBaseString).result();}
        return Result.all(GroupId.groupId(parts[0]), ArtifactId.artifactId(parts[1])).map(ArtifactBase::new);
    }

    @SuppressWarnings("JBCT-VO-02") public static ArtifactBase artifactBase(GroupId groupId, ArtifactId artifactId) {
        return new ArtifactBase(groupId, artifactId);
    }

    @SuppressWarnings("JBCT-VO-02") public static ArtifactBase artifactBase(Artifact artifact) {
        return new ArtifactBase(artifact.groupId(), artifact.artifactId());
    }

    public Artifact withVersion(Version version) {
        return Artifact.artifact(groupId, artifactId, version);
    }

    public boolean matches(Artifact artifact) {
        return groupId.equals(artifact.groupId()) && artifactId.equals(artifact.artifactId());
    }

    @Override public String toString() {
        return asString();
    }

    public String asString() {
        return groupId + ":" + artifactId;
    }
}
