package org.pragmatica.jbct.init;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/// Rewrites Maven version properties in a project's pom.xml to target versions.
/// Used by the `jbct migrate` command to bump dependency versions.
public final class VersionMigrator {
    private VersionMigrator() {}

    /// A version property to migrate and the value to migrate it to.
    public record PropertyTarget(String property, String targetVersion) {}

    /// A single property value that was changed in the pom.
    public record VersionChange(String property, String oldVersion, String newVersion) {}

    /// Outcome of a migration: the properties that were actually changed.
    public record MigrationResult(List<VersionChange> changes) {
        public boolean isEmpty() {
            return changes.isEmpty();
        }
    }

    /// Migrate the version properties in the pom.xml under the given project directory.
    /// Properties absent from the pom are skipped; properties already at the target are left unchanged.
    public static Result<MigrationResult> migrate(Path projectDir, List<PropertyTarget> targets) {
        var pomPath = projectDir.resolve("pom.xml");
        if (!Files.exists(pomPath)) {
            return Causes.cause("No pom.xml found in " + projectDir + ". Run this command from a project directory.")
                         .result();
        }
        try {
            var content = Files.readString(pomPath);
            var changes = new ArrayList<VersionChange>();
            var updated = applyTargets(content, targets, changes);
            if (!updated.equals(content)) {
                Files.writeString(pomPath, updated);
            }
            return Result.success(new MigrationResult(List.copyOf(changes)));
        } catch (IOException e) {
            return Causes.cause("Failed to update pom.xml: " + e.getMessage())
                         .result();
        }
    }

    private static String applyTargets(String content, List<PropertyTarget> targets, List<VersionChange> changes) {
        var result = content;
        for (var target : targets) {
            result = applyTarget(result, target, changes);
        }
        return result;
    }

    private static String applyTarget(String content, PropertyTarget target, List<VersionChange> changes) {
        var pattern = Pattern.compile("<" + Pattern.quote(target.property()) + ">([^<]+)</"
                                      + Pattern.quote(target.property()) + ">");
        var matcher = pattern.matcher(content);
        if (!matcher.find()) {
            return content;
        }
        var oldVersion = matcher.group(1)
                                .trim();
        if (oldVersion.equals(target.targetVersion())) {
            return content;
        }
        changes.add(new VersionChange(target.property(), oldVersion, target.targetVersion()));
        return content.substring(0, matcher.start())
               + "<" + target.property() + ">" + target.targetVersion() + "</" + target.property() + ">"
               + content.substring(matcher.end());
    }
}
