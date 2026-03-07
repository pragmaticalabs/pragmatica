package org.pragmatica.jbct.init;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/// Shared project configuration reader.
/// Reads basePackage from jbct.toml (preferred) or derives from pom.xml (fallback).
public record ProjectConfig(String basePackage, String groupId, String artifactId) {
    private static final Pattern TOML_BASE_PACKAGE = Pattern.compile("^\\s*basePackage\\s*=\\s*\"([^\"]+)\"", Pattern.MULTILINE);

    /// Read project configuration from the given directory.
    public static Result<ProjectConfig> projectConfig(Path projectDir) {
        return readPomProperties(projectDir)
                  .flatMap(pom -> resolveBasePackage(projectDir, pom));
    }

    private static Result<ProjectConfig> resolveBasePackage(Path projectDir, PomProperties pom) {
        var jbctToml = projectDir.resolve("jbct.toml");
        if (Files.exists(jbctToml)) {
            try {
                var content = Files.readString(jbctToml);
                var matcher = TOML_BASE_PACKAGE.matcher(content);
                if (matcher.find()) {
                    return Result.success(new ProjectConfig(matcher.group(1), pom.groupId(), pom.artifactId()));
                }
            } catch (IOException _) {
                // Fall through to derivation
            }
        }
        var derived = pom.groupId() + "." + pom.artifactId().replace("-", "");
        return Result.success(new ProjectConfig(derived, pom.groupId(), pom.artifactId()));
    }

    private static Result<PomProperties> readPomProperties(Path projectDir) {
        var pomPath = projectDir.resolve("pom.xml");
        if (!Files.exists(pomPath)) {
            return Causes.cause("No pom.xml found in " + projectDir + ". Run this command from an existing slice project.")
                         .result();
        }
        try {
            var content = Files.readString(pomPath);
            return extractPomProperty(content, "groupId")
                      .flatMap(groupId -> extractPomProperty(content, "artifactId")
                                             .map(artifactId -> new PomProperties(groupId, artifactId)));
        } catch (IOException e) {
            return Causes.cause("Failed to read pom.xml: " + e.getMessage())
                         .result();
        }
    }

    private static Result<String> extractPomProperty(String pomContent, String propertyName) {
        var pattern = Pattern.compile("<" + propertyName + ">([^<]+)</" + propertyName + ">");
        var matcher = pattern.matcher(pomContent);
        if (matcher.find()) {
            return Result.success(matcher.group(1).trim());
        }
        return Causes.cause("Could not find <" + propertyName + "> in pom.xml")
                     .result();
    }

    /// Resolve a package specification relative to basePackage.
    /// Starts with "." = relative (e.g., ".shared.resource" -> basePackage + ".shared.resource").
    /// Otherwise treated as absolute package name.
    public String resolvePackage(String packageSpec) {
        if (packageSpec == null || packageSpec.isBlank()) {
            return basePackage + ".shared.resource";
        }
        if (packageSpec.startsWith(".")) {
            return basePackage + packageSpec;
        }
        return packageSpec;
    }

    private record PomProperties(String groupId, String artifactId) {}
}
