package org.pragmatica.jbct.init;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;


/// Shared project configuration reader.
/// Reads basePackage from jbct.toml (preferred) or derives from pom.xml (fallback). When a jbct.toml exists without
/// the key, the derived value is written into it (#2525): the key the `add-*` commands resolve is then the one the
/// lint reads. An existing value is never touched; a project without a jbct.toml gets none.
public record ProjectConfig(String basePackage, String groupId, String artifactId) {
    private static final Pattern TOML_BASE_PACKAGE = Pattern.compile("^\\s*basePackage\\s*=\\s*\"([^\"]+)\"",
                                                                     Pattern.MULTILINE);

    private static final Pattern PROJECT_TABLE = Pattern.compile("^\\[project\\][ \\t]*\\R", Pattern.MULTILINE);

    /// Read project configuration from the given directory.
    public static Result<ProjectConfig> projectConfig(Path projectDir) {
        return readPomProperties(projectDir).flatMap(pom -> resolveBasePackage(projectDir, pom));
    }

    private static Result<ProjectConfig> resolveBasePackage(Path projectDir, PomProperties pom) {
        var jbctToml = projectDir.resolve("jbct.toml");
        var derived = pom.groupId() + "." + pom.artifactId().replace("-", "");

        if (!Files.exists(jbctToml)) {
            return Result.success(new ProjectConfig(derived, pom.groupId(), pom.artifactId()));
        }

        try {
            var content = Files.readString(jbctToml);
            var matcher = TOML_BASE_PACKAGE.matcher(content);

            if (matcher.find()) {
                return Result.success(new ProjectConfig(matcher.group(1), pom.groupId(), pom.artifactId()));
            }

            Files.writeString(jbctToml, withBasePackage(content, derived));

            return Result.success(new ProjectConfig(derived, pom.groupId(), pom.artifactId()));
        } catch (IOException e) {
            return Causes.cause("Failed to update jbct.toml: " + e.getMessage()).result();
        }
    }

    /// The toml text with `basePackage` added: under its existing `[project]` table, else in a new one at the end.
    /// A second `[project]` table would be a duplicate table, which TOML refuses.
    private static String withBasePackage(String content, String basePackage) {
        var entry = "basePackage = \"" + basePackage + "\"\n";
        var table = PROJECT_TABLE.matcher(content);

        if (table.find()) {
            return content.substring(0, table.end()) + entry + content.substring(table.end());
        }

        var head = content.stripTrailing();
        var separator = head.isEmpty()
                        ? ""
                        : "\n\n";

        return head + separator + "[project]\n" + entry;
    }

    private static Result<PomProperties> readPomProperties(Path projectDir) {
        var pomPath = projectDir.resolve("pom.xml");

        if (!Files.exists(pomPath)) {
            return Causes.cause("No pom.xml found in " + projectDir
                               + ". Run this command from an existing slice project.").result();
        }

        try {
            var content = Files.readString(pomPath);

            return extractPomProperty(content, "groupId").flatMap(groupId -> extractPomProperty(content, "artifactId").map(artifactId -> new PomProperties(groupId,
                                                                                                                                                           artifactId)));
        } catch (IOException e) {
            return Causes.cause("Failed to read pom.xml: " + e.getMessage()).result();
        }
    }

    private static Result<String> extractPomProperty(String pomContent, String propertyName) {
        var pattern = Pattern.compile("<" + propertyName + ">([^<]+)</" + propertyName + ">");
        var matcher = pattern.matcher(pomContent);

        if (matcher.find()) {
            return Result.success(matcher.group(1).trim());
        }

        return Causes.cause("Could not find <" + propertyName + "> in pom.xml").result();
    }

    /// Resolve a package specification relative to basePackage.
    /// Starts with "." = relative (e.g., ".shared.resource" -> basePackage + ".shared.resource").
    /// Otherwise treated as absolute package name.
    public String resolvePackage(String packageSpec) {
        return Option.option(packageSpec)
                     .filter(Verify.Is::notBlank)
                     .map(this::qualifyPackage)
                     .or(() -> basePackage + ".shared.resource");
    }

    private String qualifyPackage(String packageSpec) {
        return packageSpec.startsWith(".")
               ? basePackage + packageSpec
               : packageSpec;
    }

    private record PomProperties(String groupId, String artifactId) {}
}
