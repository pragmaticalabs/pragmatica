package org.pragmatica.jbct.doc;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.pragmatica.jbct.doc.DocError.SourceNotFound.sourceNotFound;
import static org.pragmatica.lang.Option.option;

/// Resolves a class or package name to Pragmatica Core source-file content.
///
/// Resolution strategies are tried in order until one yields readable source for one of the
/// candidate paths (see [SourceTarget]):
/// 1. an explicit sources jar (`--jar`);
/// 2. a pragmatica repo checkout found by walking up from the current directory (a directory
///    containing `core/src/main/java/org/pragmatica/lang/Result.java`);
/// 3. a Maven-local core sources jar under
///    `~/.m2/repository/org/pragmatica-lite/core/<version>/core-<version>-sources.jar`,
///    using `--use-version` when given, otherwise the lexicographically-latest version that
///    has a sources jar.
///
/// On total failure the returned [DocError.SourceNotFound] lists every attempted location.
public sealed interface SourceResolver {
    String REPO_MARKER = "core/src/main/java/org/pragmatica/lang/Result.java";
    String CORE_REPO_DIR = "org/pragmatica-lite/core";

    record unused() implements SourceResolver {}

    /// Resolve a name to source content, given optional explicit jar and version overrides.
    static Result<String> resolve(String name, Option<Path> jarOverride, Option<String> versionOverride) {
        var candidates = SourceTarget.candidates(name);
        var attempts = new ArrayList<String>();
        return fromJarOverride(candidates, jarOverride, attempts)
            .orElse(() -> fromRepoCheckout(candidates, attempts))
            .orElse(() -> fromMavenLocal(candidates, versionOverride, attempts))
            .toResult(sourceNotFound(name, attempts));
    }

    private static Option<String> fromJarOverride(List<String> candidates,
                                                  Option<Path> jarOverride,
                                                  List<String> attempts) {
        return jarOverride.flatMap(jar -> fromJar(jar, candidates, attempts));
    }

    private static Option<String> fromRepoCheckout(List<String> candidates, List<String> attempts) {
        return findRepoRoot().flatMap(root -> fromDirectory(root, candidates, attempts));
    }

    private static Option<String> fromMavenLocal(List<String> candidates,
                                                 Option<String> versionOverride,
                                                 List<String> attempts) {
        return selectSourcesJar(versionOverride).flatMap(jar -> fromJar(jar, candidates, attempts));
    }

    private static Option<String> fromDirectory(Path root, List<String> candidates, List<String> attempts) {
        return firstReadable(candidates, candidate -> readFromDirectory(root, candidate, attempts));
    }

    private static Option<String> readFromDirectory(Path root, String candidate, List<String> attempts) {
        var file = root.resolve("core/src/main/java")
                       .resolve(candidate);
        attempts.add("repo checkout: " + file);
        return Result.lift(() -> Files.readString(file))
                     .option();
    }

    private static Option<String> fromJar(Path jar, List<String> candidates, List<String> attempts) {
        return firstReadable(candidates, candidate -> readFromJar(jar, candidate, attempts));
    }

    private static Option<String> readFromJar(Path jar, String candidate, List<String> attempts) {
        attempts.add("sources jar " + jar + " entry " + candidate);
        return readZipEntry(jar, candidate).option();
    }

    private static Result<String> readZipEntry(Path jar, String entryName) {
        return Result.lift(() -> {
                              try (var zip = new ZipFile(jar.toFile())) {
                                  return option(zip.getEntry(entryName))
                                               .flatMap(entry -> readEntry(zip, entry));
                              }
                          })
                     .flatMap(opt -> opt.toResult(DocError.HeaderMissing.headerMissing(entryName)));
    }

    private static Option<String> readEntry(ZipFile zip, ZipEntry entry) {
        return Result.lift(() -> {
                              try (InputStream in = zip.getInputStream(entry)) {
                                  return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                              }
                          })
                     .option();
    }

    private static Option<Path> findRepoRoot() {
        return walkToRepoRoot(Path.of("")
                                  .toAbsolutePath());
    }

    private static Option<Path> walkToRepoRoot(Path dir) {
        return Files.isRegularFile(dir.resolve(REPO_MARKER)) ? option(dir)
                                                            : parentOf(dir).flatMap(SourceResolver::walkToRepoRoot);
    }

    private static Option<Path> parentOf(Path dir) {
        return option(dir.getParent());
    }

    private static Option<Path> selectSourcesJar(Option<String> versionOverride) {
        var coreDir = mavenRepository().resolve(CORE_REPO_DIR);
        return versionOverride.map(version -> sourcesJarPath(coreDir, version))
                              .filter(Files::isRegularFile)
                              .orElse(() -> latestSourcesJar(coreDir));
    }

    private static Option<Path> latestSourcesJar(Path coreDir) {
        var existing = listVersions(coreDir).stream()
                                            .sorted(Comparator.reverseOrder())
                                            .map(version -> sourcesJarPath(coreDir, version))
                                            .filter(Files::isRegularFile)
                                            .findFirst();
        return existing.map(Option::option)
                       .orElseGet(Option::none);
    }

    private static List<String> listVersions(Path coreDir) {
        return Result.lift(() -> {
                              try (var stream = Files.list(coreDir)) {
                                  return stream.filter(Files::isDirectory)
                                               .map(SourceResolver::dirName)
                                               .toList();
                              }
                          })
                     .or(List.<String>of());
    }

    private static String dirName(Path path) {
        return path.getFileName()
                   .toString();
    }

    private static Path sourcesJarPath(Path coreDir, String version) {
        return coreDir.resolve(version)
                      .resolve("core-" + version + "-sources.jar");
    }

    private static Path mavenRepository() {
        return Path.of(System.getProperty("user.home"), ".m2", "repository");
    }

    private static Option<String> firstReadable(List<String> candidates, Fn1<Option<String>, String> reader) {
        return candidates.stream()
                         .map(reader::apply)
                         .filter(Option::isPresent)
                         .findFirst()
                         .orElseGet(Option::none);
    }
}
