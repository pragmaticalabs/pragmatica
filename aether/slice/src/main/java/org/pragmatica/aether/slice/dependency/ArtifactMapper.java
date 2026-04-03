package org.pragmatica.aether.slice.dependency;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// Convention-based mapper between fully qualified class names and Maven artifact coordinates.
///
///
/// Conventions:
///
///   - GroupId = package prefix (all but last segment)
///   - ArtifactId = kebab-case of simple class name
///   - ClassName = GroupId + "." + PascalCase of ArtifactId
///
///
///
/// Examples:
///
///   - `org.example.UserService` → `org.example:user-service:1.0.0`
///   - `org.example:user-service:1.0.0` → `org.example.UserService`
///   - `com.company.app.OrderProcessor` → `com.company.app:order-processor:1.0.0`
///
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02", "JBCT-PAT-01"}) public interface ArtifactMapper {
    static Result<Artifact> toArtifact(String className, String version) {
        var lastDot = className.lastIndexOf('.');
        if (lastDot <= 0) {return INVALID_CLASS_NAME.apply(className).result();}
        var groupId = className.substring(0, lastDot);
        var simpleName = className.substring(lastDot + 1);
        if (simpleName.isEmpty() || !Character.isUpperCase(simpleName.charAt(0))) {return INVALID_CLASS_NAME.apply(className)
                                                                                                                  .result();}
        var artifactId = toKebabCase(simpleName);
        return Artifact.artifact(groupId + ":" + artifactId + ":" + version);
    }

    static Result<Artifact> toArtifact(String className, VersionPattern versionPattern) {
        return toArtifact(className, extractVersion(versionPattern));
    }

    static Result<Artifact> toArtifact(DependencyDescriptor descriptor) {
        return toArtifact(descriptor.sliceClassName(), descriptor.versionPattern());
    }

    static Result<Artifact> toArtifact(ArtifactDependency dependency) {
        var version = extractVersion(dependency.versionPattern());
        return Artifact.artifact(dependency.groupId() + ":" + dependency.artifactId() + ":" + version);
    }

    static String toClassName(Artifact artifact) {
        var groupId = artifact.groupId().id();
        var artifactId = artifact.artifactId().id();
        var simpleName = toPascalCase(artifactId);
        return groupId + "." + simpleName;
    }

    static String toClassName(String groupId, String artifactId) {
        var simpleName = toPascalCase(artifactId);
        return groupId + "." + simpleName;
    }

    private static String toKebabCase(String pascalCase) {
        if (pascalCase == null || pascalCase.isEmpty()) {return pascalCase;}
        var result = new StringBuilder();
        var chars = pascalCase.toCharArray();
        for (int i = 0;i <chars.length;i++) {
            var c = chars[i];
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    var prevUpper = Character.isUpperCase(chars[i - 1]);
                    var nextLower = (i + 1 <chars.length) && Character.isLowerCase(chars[i + 1]);
                    if (!prevUpper || nextLower) {result.append('-');}
                }
                result.append(Character.toLowerCase(c));
            } else {result.append(c);}
        }
        return result.toString();
    }

    private static String toPascalCase(String kebabCase) {
        if (kebabCase == null || kebabCase.isEmpty()) {return kebabCase;}
        var result = new StringBuilder();
        var capitalizeNext = true;
        for (var c : kebabCase.toCharArray()) {if (c == '-') {capitalizeNext = true;} else if (capitalizeNext) {
            result.append(Character.toUpperCase(c));
            capitalizeNext = false;
        } else {result.append(c);}}
        return result.toString();
    }

    private static String extractVersion(VersionPattern pattern) {
        return switch (pattern){
            case VersionPattern.Exact(Version version) -> version.withQualifier();
            case VersionPattern.Range(Version from, _, _, _) -> from.withQualifier();
            case VersionPattern.Comparison(_, Version version) -> version.withQualifier();
            case VersionPattern.Tilde(Version version) -> version.withQualifier();
            case VersionPattern.Caret(Version version) -> version.withQualifier();
            case VersionPattern.unused _ -> "0.0.0";
        };
    }

    Fn1<Cause, String> INVALID_CLASS_NAME = Causes.forOneValue("Invalid class name format: %s. Expected fully qualified name like 'org.example.ClassName'");
}
