package org.pragmatica.jbct.maven;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.zip.ZipFile;

import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// #1778: the blueprint jars must honour `project.build.outputTimestamp`, so the same sources build byte-identical jars
/// and a rebuilt re-push to the write-once cluster repository is the idempotent 200. These tests run the REAL jar
/// creation of the two blueprint mojos through the real `MavenArchiver` and read the entry times of the produced jar:
/// with the parameter every entry carries it, without it entries carry the wall clock. `PackageSlicesMojo` builds its
/// jar from a resolved dependency graph that a unit test cannot assemble; its setting is the same one-line call and is
/// evidenced end to end (two real `mvn package` runs of a generated project, identical sha256).
class ReproducibleArchiveTest {
    private static final String TIMESTAMP = "2025-01-01T00:00:00Z";

    @TempDir
    Path tmp;

    @Test
    void packageBlueprint_stampsEveryEntryWithTheOutputTimestamp() throws Exception {
        assertThat(entryTimes(new PackageBlueprintMojo(), TIMESTAMP)).isNotEmpty().allMatch(ReproducibleArchiveTest::in2025);
    }

    @Test
    void generateBlueprint_stampsEveryEntryWithTheOutputTimestamp() throws Exception {
        assertThat(entryTimes(new GenerateBlueprintMojo(), TIMESTAMP)).isNotEmpty().allMatch(ReproducibleArchiveTest::in2025);
    }

    @Test
    void packageBlueprint_withoutTheParameter_stampsTheWallClock() throws Exception {
        assertThat(entryTimes(new PackageBlueprintMojo(), null)).isNotEmpty().noneMatch(ReproducibleArchiveTest::in2025);
    }

    /// The entry time is the output timestamp (2025-01-01T00:00:00Z) up to the zip format's local-time rounding.
    private static boolean in2025(long time) {
        var stamp = Instant.ofEpochMilli(time).toString();

        return stamp.startsWith("2025-01-0") || stamp.startsWith("2024-12-31");
    }

    private java.util.List<Long> entryTimes(Object mojo, String outputTimestamp) throws Exception {
        var blueprint = Files.writeString(tmp.resolve("blueprint-" + mojo.getClass().getSimpleName() + ".toml"), "id = \"org.example:app:1.0.0\"\n");
        var model = new Model();

        model.setGroupId("org.example");
        model.setArtifactId("app");
        model.setVersion("1.0.0");
        model.setPackaging("jar");

        var project = new MavenProject(model);

        project.setFile(Files.writeString(tmp.resolve("pom.xml"), "<project/>").toFile());
        project.getBuild().setDirectory(tmp.toString());
        project.getBuild().setOutputDirectory(Files.createDirectories(tmp.resolve("classes")).toString());
        project.setArtifact(new org.apache.maven.artifact.DefaultArtifact("org.example",
                                                                         "app",
                                                                         "1.0.0",
                                                                         "compile",
                                                                         "jar",
                                                                         null,
                                                                         new org.apache.maven.artifact.handler.DefaultArtifactHandler("jar")));
        set(mojo, "blueprintFile", blueprint.toFile());
        set(mojo, "project", project);
        set(mojo, "outputTimestamp", outputTimestamp);
        set(mojo, "resourcesTomlFile", tmp.resolve("absent-resources.toml").toFile());
        set(mojo, "schemaDirectory", tmp.resolve("absent-schema").toFile());

        var jar = tmp.resolve("out-" + mojo.getClass().getSimpleName() + (outputTimestamp == null ? "-plain" : "") + ".jar").toFile();
        var method = mojo.getClass().getDeclaredMethod("createBlueprintJar", File.class, String.class);

        method.setAccessible(true);
        method.invoke(mojo, jar, "org.example:app:1.0.0");

        var times = new ArrayList<Long>();

        try (var zip = new ZipFile(jar)) {
            zip.stream().forEach(entry -> times.add(entry.getTime()));
        }

        return times;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);

        field.setAccessible(true);
        field.set(target, value);
    }
}
