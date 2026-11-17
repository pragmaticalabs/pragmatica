package org.pragmatica.jbct.init;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.pragmatica.jbct.config.ConfigLoader;
import org.pragmatica.jbct.config.JbctConfig;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.io.FileOps;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.cause;


/// Shared project configuration reader.
/// Reads basePackage from the loader's resolved `jbct.toml` chain (preferred) or derives it from pom.xml (fallback).
/// When the module has a `jbct.toml` and the chain has no value, the derived one is written into that file (#2525): the
/// key the `add-*` commands resolve is then the one the lint reads. The write is one path: the edited text goes to a
/// sibling temp file, the loader loads that file and must read the derived value back, and only then does an atomic
/// move replace the original. Any failure leaves `jbct.toml` byte-identical and fails the command with the cause. A
/// value the chain holds is never touched; a project without a `jbct.toml` gets none.
public record ProjectConfig(String basePackage, String groupId, String artifactId) {
    private static final String TEMP_NAME = ConfigLoader.PROJECT_CONFIG_NAME + ".tmp";

    private static final Pattern PROJECT_TABLE = Pattern.compile("^\\[project\\][ \\t]*(?:#[^\\r\\n]*)?(?:\\R|\\z)",
                                                                 Pattern.MULTILINE);

    /// Read project configuration from the given directory.
    public static Result<ProjectConfig> projectConfig(Path projectDir) {
        return readPomProperties(projectDir).flatMap(pom -> resolveBasePackage(projectDir, pom));
    }

    private static Result<ProjectConfig> resolveBasePackage(Path projectDir, PomProperties pom) {
        var derived = pom.groupId() + "." + pom.artifactId().replace("-", "");

        return ConfigLoader.loadForSourceRoot(none(),
                                              projectDir)
                           .flatMap(chain -> chainValueOr(chain, projectDir, derived))
                           .map(name -> new ProjectConfig(name,
                                                          pom.groupId(),
                                                          pom.artifactId()));
    }

    private static Result<String> chainValueOr(JbctConfig chain, Path projectDir, String derived) {
        return chain.basePackage()
                    .fold(() -> deriveInto(projectDir, derived),
                          Result::success);
    }

    private static Result<String> deriveInto(Path projectDir, String derived) {
        var jbctToml = projectDir.resolve(ConfigLoader.PROJECT_CONFIG_NAME);

        return FileOps.isRegularFile(jbctToml)
               ? FileOps.readString(jbctToml)
                        .flatMap(content -> writeThroughLoader(projectDir,
                                                               jbctToml,
                                                               withBasePackage(content, derived),
                                                               derived))
                        .map(_ -> derived)
               : success(derived);
    }

    /// Temp file, load it, then atomic move; the original is only ever replaced by a file the loader read the derived
    /// value from. A failed attempt removes its temp file (BER: the inverse of the one thing it created). A failed
    /// removal is absorbed: the temp name is outside the loader's discovery and the next attempt overwrites it.
    private static Result<Path> writeThroughLoader(Path projectDir, Path jbctToml, String text, String derived) {
        var temp = projectDir.resolve(TEMP_NAME);

        return FileOps.writeString(temp, text)
                      .flatMap(_ -> loadedBack(projectDir, temp, derived))
                      .flatMap(_ -> FileOps.moveAtomic(temp, jbctToml))
                      .onFailure(_ -> FileOps.deleteIfExists(temp))
                      .mapError(cause -> refused(jbctToml, derived, cause));
    }

    private static Result<String> loadedBack(Path projectDir, Path candidate, String derived) {
        return ConfigLoader.loadForSourceRoot(some(candidate), projectDir).flatMap(loaded -> readBack(loaded, derived));
    }

    private static Result<String> readBack(JbctConfig loaded, String derived) {
        return loaded.basePackage()
                     .filter(derived::equals)
                     .toResult(cause("the loader did not read back " + derived));
    }

    private static Cause refused(Path jbctToml, String derived, Cause cause) {
        return cause("Cannot write basePackage \"" + derived
                    + "\" into " + jbctToml
                    + ": " + cause.message()
                    + ". The file is unchanged; set it by hand under [project] as basePackage = \"<your.package>\".",
                     some(cause));
    }

    /// The toml text with `basePackage` added: under its existing `[project]` table header, else in a new table at the end.
    private static String withBasePackage(String content, String basePackage) {
        var entry = "basePackage = \"" + basePackage + "\"\n";
        var table = PROJECT_TABLE.matcher(content);

        return table.find()
               ? insertAfter(content, table.end(), entry)
               : appendTable(content, entry);
    }

    private static String insertAfter(String content, int position, String entry) {
        var head = content.substring(0, position);
        var separator = head.endsWith("\n")
                        ? ""
                        : "\n";

        return head + separator + entry + content.substring(position);
    }

    private static String appendTable(String content, String entry) {
        var head = content.stripTrailing();
        var separator = head.isEmpty()
                        ? ""
                        : "\n\n";

        return head + separator + "[project]\n" + entry;
    }

    private static Result<PomProperties> readPomProperties(Path projectDir) {
        var pomPath = projectDir.resolve("pom.xml");

        if (!Files.exists(pomPath)) {
            return cause("No pom.xml found in " + projectDir + ". Run this command from an existing slice project.").result();
        }

        try {
            var content = Files.readString(pomPath);

            return extractPomProperty(content, "groupId").flatMap(groupId -> extractPomProperty(content, "artifactId").map(artifactId -> new PomProperties(groupId,
                                                                                                                                                           artifactId)));
        } catch (IOException e) {
            return cause("Failed to read pom.xml: " + e.getMessage()).result();
        }
    }

    private static Result<String> extractPomProperty(String pomContent, String propertyName) {
        var pattern = Pattern.compile("<" + propertyName + ">([^<]+)</" + propertyName + ">");
        var matcher = pattern.matcher(pomContent);

        if (matcher.find()) {
            return success(matcher.group(1).trim());
        }

        return cause("Could not find <" + propertyName + "> in pom.xml").result();
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
