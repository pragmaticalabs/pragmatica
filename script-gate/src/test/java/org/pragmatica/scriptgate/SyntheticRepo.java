package org.pragmatica.scriptgate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// A minimal but REAL Maven tree: a root aggregator, an `aether/forge/forge-tests` module, and the
/// jar modules it depends on, each with a source file and an installed artifact.
///
/// Both #865 tests build their fixture from this, so the checker and the script that calls it are
/// exercised against the same shape. Modification times are ASSIGNED, never produced by sleeping:
/// "stale" is then a property of the fixture rather than of how long the suite took to run, and the
/// tests cannot go flaky on a loaded machine.
record SyntheticRepo(Path root) {
    static final Instant SOURCE_TIME = Instant.parse("2026-01-01T00:00:00Z");
    static final Instant INSTALL_TIME = SOURCE_TIME.plusSeconds(600);
    static final Instant EDIT_TIME = INSTALL_TIME.plusSeconds(600);
    static final String GROUP_ID = "org.example";
    static final String VERSION = "1.0.0";
    static final Path FORGE_POM = Path.of("aether", "forge", "forge-tests", "pom.xml");

    static Result<SyntheticRepo> syntheticRepo(Path root, List<String> modules) {
        var repo = new SyntheticRepo(root);

        return repo.layout(modules)
                   .map(ignored -> repo);
    }

    private Result<Unit> layout(List<String> modules) {
        return mavenConfig(".m2-local").flatMap(ignored -> writePom(root.resolve("pom.xml"),
                                                                    "root",
                                                                    "pom",
                                                                    List.of()))
                          .flatMap(ignored -> writePom(root.resolve(FORGE_POM),
                                                       "forge-tests",
                                                       "jar",
                                                       modules))
                          .flatMap(ignored -> installAll(modules));
    }

    /// A decoy flag ahead of the property: the checker has to parse this file, not assume a layout.
    Result<Unit> mavenConfig(String localRepository) {
        var config = root.resolve(".mvn/maven.config");

        return createDirectories(config.getParent()).flatMap(ignored -> write(config,
                                                                              "-T 1C\n-Dmaven.repo.local=" + localRepository
                                                                             + "\n"))
                                .mapToUnit();
    }

    private Result<Unit> installAll(List<String> modules) {
        return Result.allOf(modules.stream().map(this::addAndInstall).toList()).mapToUnit();
    }

    private Result<Unit> addAndInstall(String artifactId) {
        return addModule(artifactId).flatMap(ignored -> install(artifactId));
    }

    /// Adds a module that nothing depends on - the control for the closure claim.
    Result<Unit> addModule(String artifactId) {
        var pom = root.resolve(artifactId + "/pom.xml");
        var source = sourceOf(artifactId);

        return writePom(pom,
                        artifactId,
                        "jar",
                        List.of()).flatMap(ignored -> writeSource(source))
                       .flatMap(ignored -> touch(source, SOURCE_TIME))
                       .flatMap(ignored -> touch(pom, SOURCE_TIME));
    }

    Result<Unit> install(String artifactId) {
        var jar = jarOf(artifactId);

        return createDirectories(jar.getParent()).flatMap(ignored -> write(jar, "stand-in for an installed artifact\n"))
                                .flatMap(ignored -> touch(jar, INSTALL_TIME));
    }

    Path sourceOf(String artifactId) {
        return root.resolve(artifactId + "/src/main/java/Runtime.java");
    }

    Path jarOf(String artifactId) {
        return root.resolve(".m2-local/" + GROUP_ID.replace('.', '/')
                           + "/" + artifactId
                           + "/" + VERSION
                           + "/" + artifactId
                           + "-" + VERSION
                           + ".jar");
    }

    Path forgePom() {
        return root.resolve(FORGE_POM);
    }

    static Result<Unit> touch(Path path, Instant when) {
        return Result.lift(() -> Files.setLastModifiedTime(path, FileTime.from(when))).mapToUnit();
    }

    static Result<Unit> delete(Path path) {
        return Result.lift(() -> Files.deleteIfExists(path)).mapToUnit();
    }

    /// A resource that ships inside the jar counts as source for the freshness comparison.
    Result<Unit> writeResource(Path resource) {
        return createDirectories(resource.getParent()).flatMap(ignored -> write(resource, "key=value\n"))
                                .mapToUnit();
    }

    private Result<Unit> writeSource(Path source) {
        return createDirectories(source.getParent()).flatMap(ignored -> write(source, "class Runtime {}\n"))
                                .mapToUnit();
    }

    private static Result<Unit> writePom(Path path, String artifactId, String packaging, List<String> dependencies) {
        return createDirectories(path.getParent()).flatMap(ignored -> write(path,
                                                                            pomText(artifactId, packaging, dependencies)))
                                .mapToUnit();
    }

    private static Result<Path> createDirectories(Path directory) {
        return Result.lift(() -> Files.createDirectories(directory));
    }

    private static Result<Path> write(Path path, String content) {
        return Result.lift(() -> Files.writeString(path, content));
    }

    private static String pomText(String artifactId, String packaging, List<String> dependencies) {
        var builder = new StringBuilder();

        builder.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n");
        builder.append("  <modelVersion>4.0.0</modelVersion>\n");
        builder.append("  <groupId>").append(GROUP_ID).append("</groupId>\n");
        builder.append("  <artifactId>").append(artifactId).append("</artifactId>\n");
        builder.append("  <version>").append(VERSION).append("</version>\n");
        builder.append("  <packaging>").append(packaging).append("</packaging>\n");
        builder.append("  <dependencies>\n");
        dependencies.forEach(dependency -> builder.append(dependencyText(dependency)));
        builder.append("  </dependencies>\n");
        builder.append("</project>\n");

        return builder.toString();
    }

    private static String dependencyText(String artifactId) {
        return "    <dependency><groupId>" + GROUP_ID
             + "</groupId><artifactId>" + artifactId
             + "</artifactId><version>" + VERSION
             + "</version></dependency>\n";
    }
}
