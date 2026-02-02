package org.pragmatica.aether.slice.dependency;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

/**
 * Represents a dependency on an artifact with version pattern.
 * <p>
 * Format: {@code groupId:artifactId:versionPattern}
 * <p>
 * Examples:
 * - {@code org.pragmatica-lite:core:^0.8.0}
 * - {@code org.example:order-domain:[1.0.0,2.0.0)}
 * - {@code com.fasterxml.jackson.core:jackson-databind:>=2.15.0}
 *
 * @param groupId        Maven group ID
 * @param artifactId     Maven artifact ID
 * @param versionPattern Version pattern for compatibility checking
 */
public record ArtifactDependency(String groupId,
                                 String artifactId,
                                 VersionPattern versionPattern) {
    /**
     * Parse artifact dependency from string.
     * <p>
     * Format: {@code groupId:artifactId:versionPattern}
     *
     * @param line The dependency string
     * @return Parsed dependency or error
     */
    public static Result<ArtifactDependency> artifactDependency(String line) {
        var trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return EMPTY_LINE.result();
        }
        if (trimmed.startsWith("#")) {
            return COMMENT_LINE.result();
        }
        if (trimmed.startsWith("[")) {
            return SECTION_HEADER.result();
        }
        // Find the last colon - version pattern comes after it
        // This handles cases where version pattern itself may contain special chars
        var lastColon = trimmed.lastIndexOf(':');
        if (lastColon <= 0) {
            return INVALID_FORMAT.apply(line)
                                 .result();
        }
        var versionStr = trimmed.substring(lastColon + 1)
                                .trim();
        var groupArtifact = trimmed.substring(0, lastColon);
        // Find the colon separating groupId from artifactId
        var colonPos = groupArtifact.lastIndexOf(':');
        if (colonPos <= 0) {
            return INVALID_FORMAT.apply(line)
                                 .result();
        }
        var groupId = groupArtifact.substring(0, colonPos)
                                   .trim();
        var artifactId = groupArtifact.substring(colonPos + 1)
                                      .trim();
        if (groupId.isEmpty()) {
            return EMPTY_GROUP_ID.apply(line)
                                 .result();
        }
        if (artifactId.isEmpty()) {
            return EMPTY_ARTIFACT_ID.apply(line)
                                    .result();
        }
        if (versionStr.isEmpty()) {
            return EMPTY_VERSION.apply(line)
                                .result();
        }
        return VersionPattern.parse(versionStr)
                             .map(pattern -> new ArtifactDependency(groupId, artifactId, pattern));
    }

    /**
     * Format dependency back to string representation.
     */
    public String asString() {
        return groupId + ":" + artifactId + ":" + versionPattern.asString();
    }

    /**
     * Get artifact key (groupId:artifactId) for conflict detection.
     */
    public String artifactKey() {
        return groupId + ":" + artifactId;
    }

    // Error constants
    static final Cause EMPTY_LINE = Causes.cause("Dependency line is empty");
    static final Cause COMMENT_LINE = Causes.cause("Dependency line is a comment");
    static final Cause SECTION_HEADER = Causes.cause("Line is a section header");
    private static final Fn1<Cause, String> INVALID_FORMAT = Causes.forOneValue("Invalid artifact dependency format: %s. Expected groupId:artifactId:versionPattern");
    private static final Fn1<Cause, String> EMPTY_GROUP_ID = Causes.forOneValue("Empty group ID in dependency: %s");
    private static final Fn1<Cause, String> EMPTY_ARTIFACT_ID = Causes.forOneValue("Empty artifact ID in dependency: %s");
    private static final Fn1<Cause, String> EMPTY_VERSION = Causes.forOneValue("Empty version pattern in dependency: %s");
}
