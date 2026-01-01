package org.pragmatica.aether.slice.dependency;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

/**
 * Convention-based mapper between fully qualified class names and Maven artifact coordinates.
 *
 * <p>Conventions:
 * <ul>
 *   <li>GroupId = package prefix (all but last segment)</li>
 *   <li>ArtifactId = kebab-case of simple class name</li>
 *   <li>ClassName = GroupId + "." + PascalCase of ArtifactId</li>
 * </ul>
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code org.example.UserService} → {@code org.example:user-service:1.0.0}</li>
 *   <li>{@code org.example:user-service:1.0.0} → {@code org.example.UserService}</li>
 *   <li>{@code com.company.app.OrderProcessor} → {@code com.company.app:order-processor:1.0.0}</li>
 * </ul>
 */
public interface ArtifactMapper {
    /**
     * Convert a fully qualified class name and version to artifact coordinates.
     *
     * @param className fully qualified class name (e.g., "org.example.UserService")
     * @param version   version string (e.g., "1.0.0")
     *
     * @return artifact or error if className format is invalid
     */
    static Result<Artifact> toArtifact(String className, String version) {
        var lastDot = className.lastIndexOf('.');
        if (lastDot <= 0) {
            return INVALID_CLASS_NAME.apply(className)
                                     .result();
        }
        var groupId = className.substring(0, lastDot);
        var simpleName = className.substring(lastDot + 1);
        if (simpleName.isEmpty() || !Character.isUpperCase(simpleName.charAt(0))) {
            return INVALID_CLASS_NAME.apply(className)
                                     .result();
        }
        var artifactId = toKebabCase(simpleName);
        return Artifact.artifact(groupId + ":" + artifactId + ":" + version);
    }

    /**
     * Convert a fully qualified class name and version pattern to artifact coordinates.
     *
     * @param className      fully qualified class name
     * @param versionPattern version pattern to extract version from
     *
     * @return artifact or error
     */
    static Result<Artifact> toArtifact(String className, VersionPattern versionPattern) {
        return toArtifact(className, extractVersion(versionPattern));
    }

    static Result<Artifact> toArtifact(DependencyDescriptor descriptor) {
        return toArtifact(descriptor.sliceClassName(), descriptor.versionPattern());
    }

    /**
     * Convert artifact coordinates to a fully qualified class name.
     *
     * @param artifact the artifact
     *
     * @return class name (e.g., "org.example.UserService")
     */
    static String toClassName(Artifact artifact) {
        var groupId = artifact.groupId()
                              .id();
        var artifactId = artifact.artifactId()
                                 .id();
        var simpleName = toPascalCase(artifactId);
        return groupId + "." + simpleName;
    }

    /**
     * Convert groupId and artifactId to a fully qualified class name.
     *
     * @param groupId    group ID (e.g., "org.example")
     * @param artifactId artifact ID (e.g., "user-service")
     *
     * @return class name (e.g., "org.example.UserService")
     */
    static String toClassName(String groupId, String artifactId) {
        var simpleName = toPascalCase(artifactId);
        return groupId + "." + simpleName;
    }

    /**
     * Convert PascalCase to kebab-case.
     * <p>Examples:
     * <ul>
     *   <li>UserService → user-service</li>
     *   <li>OrderProcessor → order-processor</li>
     *   <li>API → api</li>
     *   <li>XMLParser → xml-parser</li>
     * </ul>
     */
    private static String toKebabCase(String pascalCase) {
        if (pascalCase == null || pascalCase.isEmpty()) {
            return pascalCase;
        }
        var result = new StringBuilder();
        var chars = pascalCase.toCharArray();
        for (int i = 0; i < chars.length; i++ ) {
            var c = chars[i];
            if (Character.isUpperCase(c)) {
                // Add hyphen before uppercase (except at start)
                if (i > 0) {
                    // Don't add hyphen if previous char was also uppercase and next is lowercase
                    // (handles cases like "XMLParser" → "xml-parser")
                    var prevUpper = Character.isUpperCase(chars[i - 1]);
                    var nextLower = (i + 1 < chars.length) && Character.isLowerCase(chars[i + 1]);
                    if (!prevUpper || nextLower) {
                        result.append('-');
                    }
                }
                result.append(Character.toLowerCase(c));
            }else {
                result.append(c);
            }
        }
        return result.toString();
    }

    /**
     * Convert kebab-case to PascalCase.
     * <p>Examples:
     * <ul>
     *   <li>user-service → UserService</li>
     *   <li>order-processor → OrderProcessor</li>
     *   <li>api → Api</li>
     * </ul>
     */
    private static String toPascalCase(String kebabCase) {
        if (kebabCase == null || kebabCase.isEmpty()) {
            return kebabCase;
        }
        var result = new StringBuilder();
        var capitalizeNext = true;
        for (var c : kebabCase.toCharArray()) {
            if (c == '-') {
                capitalizeNext = true;
            }else if (capitalizeNext) {
                result.append(Character.toUpperCase(c));
                capitalizeNext = false;
            }else {
                result.append(c);
            }
        }
        return result.toString();
    }

    /**
     * Extract a concrete version from a version pattern.
     * For exact versions, returns the version directly.
     * For ranges/comparisons, returns the lower bound.
     */
    private static String extractVersion(VersionPattern pattern) {
        return switch (pattern) {
            case VersionPattern.Exact(Version version) -> version.withQualifier();
            case VersionPattern.Range(Version from, _, _, _) -> from.withQualifier();
            case VersionPattern.Comparison(_, Version version) -> version.withQualifier();
            case VersionPattern.Tilde(Version version) -> version.withQualifier();
            case VersionPattern.Caret(Version version) -> version.withQualifier();
        };
    }

    Fn1<Cause, String>INVALID_CLASS_NAME = Causes.forOneValue(
    "Invalid class name format: %s. Expected fully qualified name like 'org.example.ClassName'");
}
