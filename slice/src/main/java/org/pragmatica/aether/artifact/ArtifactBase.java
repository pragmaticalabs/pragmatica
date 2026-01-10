package org.pragmatica.aether.artifact;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

/**
 * Version-agnostic artifact identifier.
 *
 * <p>Used for operations that apply to all versions of an artifact, such as
 * rolling updates where both old and new versions are managed together.
 *
 * <p>Format: groupId:artifactId (e.g., "org.pragmatica-lite.aether:example-slice")
 */
public record ArtifactBase(GroupId groupId, ArtifactId artifactId) {
    private static final Fn1<Cause, String> INVALID_FORMAT = Causes.forOneValue("Invalid artifact base format {}");

    /**
     * Parses an artifact base from string format (groupId:artifactId).
     *
     * @param artifactBaseString the string to parse
     * @return parsed artifact base or error
     */
    public static Result<ArtifactBase> artifactBase(String artifactBaseString) {
        var parts = artifactBaseString.split(":", 2);
        if (parts.length != 2) {
            return INVALID_FORMAT.apply(artifactBaseString)
                                 .result();
        }
        return Result.all(GroupId.groupId(parts[0]),
                          ArtifactId.artifactId(parts[1]))
                     .map(ArtifactBase::artifactBase);
    }

    /**
     * Creates an artifact base from components.
     */
    public static ArtifactBase artifactBase(GroupId groupId, ArtifactId artifactId) {
        return new ArtifactBase(groupId, artifactId);
    }

    /**
     * Extracts the artifact base from a full artifact.
     *
     * @param artifact the full artifact with version
     * @return the version-agnostic artifact base
     */
    public static ArtifactBase artifactBase(Artifact artifact) {
        return new ArtifactBase(artifact.groupId(), artifact.artifactId());
    }

    /**
     * Creates a full artifact by combining this base with a version.
     *
     * @param version the version to add
     * @return full artifact
     */
    public Artifact withVersion(Version version) {
        return Artifact.artifact(groupId, artifactId, version);
    }

    /**
     * Checks if the given artifact matches this base (same groupId and artifactId).
     *
     * @param artifact the artifact to check
     * @return true if artifact matches this base
     */
    public boolean matches(Artifact artifact) {
        return groupId.equals(artifact.groupId()) && artifactId.equals(artifact.artifactId());
    }

    @Override
    public String toString() {
        return asString();
    }

    public String asString() {
        return groupId + ":" + artifactId;
    }
}
