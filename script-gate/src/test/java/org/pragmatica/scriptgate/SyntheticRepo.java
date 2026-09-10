package org.pragmatica.scriptgate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

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

    static SyntheticRepo create(Path root, List<String> modules) throws IOException {
        var repo = new SyntheticRepo(root);

        Files.createDirectories(root.resolve(".mvn"));
        // A decoy flag ahead of the property: the checker has to parse this file, not assume a layout.
        Files.writeString(root.resolve(".mvn/maven.config"), "-T 1C\n-Dmaven.repo.local=.m2-local\n");

        writePom(root.resolve("pom.xml"), "root", "pom", List.of());
        writePom(root.resolve("aether/forge/forge-tests/pom.xml"), "forge-tests", "jar", modules);

        for (var module : modules) {
            repo.addModule(module);
            repo.install(module);
        }

        return repo;
    }

    /// Adds a module that nothing depends on - the control for the closure claim.
    void addModule(String artifactId) throws IOException {
        var pom = root.resolve(artifactId + "/pom.xml");

        writePom(pom, artifactId, "jar", List.of());

        var source = sourceOf(artifactId);

        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Runtime {}\n");
        touch(source, SOURCE_TIME);
        touch(pom, SOURCE_TIME);
    }

    void install(String artifactId) throws IOException {
        var jar = jarOf(artifactId);

        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "stand-in for an installed artifact\n");
        touch(jar, INSTALL_TIME);
    }

    Path sourceOf(String artifactId) {
        return root.resolve(artifactId + "/src/main/java/Runtime.java");
    }

    Path jarOf(String artifactId) {
        return root.resolve(".m2-local/org/example/" + artifactId + "/1.0.0/" + artifactId + "-1.0.0.jar");
    }

    static void touch(Path path, Instant when) throws IOException {
        Files.setLastModifiedTime(path, FileTime.from(when));
    }

    static void deleteTree(Path path) throws IOException {
        try (var entries = Files.walk(path)) {
            for (var entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(entry);
            }
        }
    }

    private static void writePom(Path path, String artifactId, String packaging, List<String> dependencies) throws IOException {
        var builder = new StringBuilder();

        builder.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n");
        builder.append("  <modelVersion>4.0.0</modelVersion>\n");
        builder.append("  <groupId>org.example</groupId>\n");
        builder.append("  <artifactId>").append(artifactId).append("</artifactId>\n");
        builder.append("  <version>1.0.0</version>\n");
        builder.append("  <packaging>").append(packaging).append("</packaging>\n");
        builder.append("  <dependencies>\n");

        for (var dependency : dependencies) {
            builder.append("    <dependency><groupId>org.example</groupId><artifactId>")
                   .append(dependency)
                   .append("</artifactId><version>1.0.0</version></dependency>\n");
        }

        builder.append("  </dependencies>\n");
        builder.append("</project>\n");

        Files.createDirectories(path.getParent());
        Files.writeString(path, builder.toString());
    }
}
