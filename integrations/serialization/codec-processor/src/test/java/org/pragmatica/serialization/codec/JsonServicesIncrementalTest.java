package org.pragmatica.serialization.codec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.pragmatica.serialization.json.JsonCodecs;

import javax.annotation.processing.Processor;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The services file across incremental compilations into one class output, driven through the platform `javac`
/// several times: an entry a previous compilation left is kept while its entry point class still resolves (types not
/// recompiled this time keep their providers), dropped once its class is gone (a stale entry would make the
/// `ServiceLoader` report a broken provider), and the file stays sorted. Also the generated-name refusals that need a
/// class compiled by an earlier run: another source's entry point, and an unmarked class at an entry point's name.
class JsonServicesIncrementalTest {
    private static final String SERVICES = "META-INF/services/org.pragmatica.serialization.json.spi.JsonCodecProvider";
    private static final String P1_A = "package p1;\n\n@org.pragmatica.serialization.json.Json\npublic record A(String a) {}\n";
    private static final String P1_A2 = "package p1;\n\n@org.pragmatica.serialization.json.Json\npublic record A2(String a2) {}\n";
    private static final String P2_B = "package p2;\n\n@org.pragmatica.serialization.json.Json\npublic record B(long b) {}\n";

    @TempDir
    Path dir;

    private Path classes;

    @BeforeEach
    void compileBothPackages() throws IOException {
        classes = Files.createDirectories(dir.resolve("classes"));

        compile("first", source("p1/A.java", P1_A), source("p2/B.java", P2_B));
        assertEquals(List.of("p1.AJson", "p2.BJson"), services());
    }

    @Test
    void recompilingSomeTypes_keepsTheOthersStillResolvableEntries() throws Exception {
        compile("second", source("p1/A.java", P1_A));

        assertEquals(List.of("p1.AJson", "p2.BJson"), services());
        assertResolve("p1.A", "p2.B");
    }

    @Test
    void aTypeAddedLater_isListedBesideThePreviousEntries_sorted() throws Exception {
        compile("second", source("p1/A2.java", P1_A2));

        assertEquals(List.of("p1.A2Json", "p1.AJson", "p2.BJson"), services());
        assertResolve("p1.A", "p1.A2", "p2.B");
    }

    @Test
    void anEntryWhoseClassIsGone_isDropped() throws IOException {
        Files.delete(classes.resolve("p2/BJson.class"));

        compile("second", source("p1/A.java", P1_A));

        assertEquals(List.of("p1.AJson"), services());
    }

    // Documented limit: an entry point whose type lost @Json still resolves while its class file remains, so its entry
    // stays until the build tool removes the outputs of the changed source or the output is cleaned.
    @Test
    void aTypeRecompiledWithoutTheAnnotation_keepsItsEntry_whileItsEntryPointClassRemains() throws IOException {
        compile("second", source("p1/A2.java", P1_A2));
        compile("third", source("p1/A.java", P1_A), source("p1/A2.java", "package p1;\n\npublic record A2(String a2) {}\n"));

        assertEquals(List.of("p1.A2Json", "p1.AJson", "p2.BJson"), services());
    }

    // An entry point class generated for another source (its marker names someone else) is refused, not overwritten.
    @Test
    void anEntryPointGeneratedForAnotherSource_isRefused() throws IOException {
        compile("second",
                source("p3/XJson.java",
                       "package p3;\n\n@org.pragmatica.serialization.json.spi.GeneratedFor(\"p3.Other\")\npublic final class XJson {}\n"));

        var errors = compileFailing("third",
                                    source("p3/X.java", "package p3;\n\n@org.pragmatica.serialization.json.Json\npublic record X(String x) {}\n"));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.getFirst().contains("Cannot generate p3.XJson for p3.X: it already exists, generated for p3.Other"),
                   errors.getFirst());
    }

    // A compiled class at the entry point's name without the marker but shaped like generated JSON codec code (an
    // entry point of an older processor version): stale output, so the message says to clean, not to rename.
    @Test
    void anUnmarkedCompiledClassShapedLikeAnOldEntryPoint_isReportedAsStaleOutput() throws IOException {
        compile("second",
                source("p3/XJson.java",
                       "package p3;\n\npublic final class XJson {\n    public static final org.pragmatica.serialization.json.JsonTypeCodec<Object> CODEC = null;\n}\n"));
        Files.delete(dir.resolve("src/p3/XJson.java"));

        var errors = compileFailing("third",
                                    source("p3/X.java", "package p3;\n\n@org.pragmatica.serialization.json.Json\npublic record X(String x) {}\n"));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.getFirst().contains("Cannot generate p3.XJson for p3.X: a class p3.XJson already exists without the GeneratedFor marker"),
                   errors.getFirst());
        assertTrue(errors.getFirst().contains("stale generated output. Clean the build"), errors.getFirst());
        assertFalse(errors.getFirst().contains("rename"), errors.getFirst());
    }

    // An unmarked compiled class of any other shape may be a user type or stale output: the message names both ways out.
    @Test
    void anUnmarkedCompiledClassOfAnotherShape_namesBothWaysOut() throws IOException {
        compile("second", source("p3/XJson.java", "package p3;\n\npublic final class XJson {\n    public static final String NAME = \"x\";\n}\n"));
        Files.delete(dir.resolve("src/p3/XJson.java"));

        var errors = compileFailing("third",
                                    source("p3/X.java", "package p3;\n\n@org.pragmatica.serialization.json.Json\npublic record X(String x) {}\n"));

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.getFirst().contains("the type p3.XJson already exists and is not marked as generated JSON codec code"),
                   errors.getFirst());
        assertTrue(errors.getFirst().contains("If it is stale output of an older build, clean the build"), errors.getFirst());
        assertTrue(errors.getFirst().contains("otherwise rename p3.XJson, or rename or move p3.X."), errors.getFirst());
    }

    private void assertResolve(String... types) throws Exception {
        try (var loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
            for (var type : types) {
                var codec = JsonCodecs.codecFor(loader.loadClass(type));

                assertTrue(codec.isSuccess() && codec.unwrap().isPresent(), type + ": " + codec);
            }
        }
    }

    private List<String> services() throws IOException {
        return Files.readAllLines(classes.resolve(SERVICES));
    }

    private Path source(String relative, String text) throws IOException {
        var file = dir.resolve("src").resolve(relative);

        Files.createDirectories(file.getParent());
        Files.writeString(file, text);

        return file;
    }

    private void compile(String run, Path... sources) throws IOException {
        var collector = new DiagnosticCollector<JavaFileObject>();

        assertTrue(run(run, collector, sources), collector.getDiagnostics().toString());
    }

    private List<String> compileFailing(String run, Path... sources) throws IOException {
        var collector = new DiagnosticCollector<JavaFileObject>();

        assertFalse(run(run, collector, sources));

        return collector.getDiagnostics()
                        .stream()
                        .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                        .map(diagnostic -> diagnostic.getMessage(null))
                        .toList();
    }

    private boolean run(String run, DiagnosticCollector<JavaFileObject> collector, Path... sources) throws IOException {
        return run(run, collector, List.of(new CodecProcessor()), sources);
    }

    private boolean run(String run,
                        DiagnosticCollector<JavaFileObject> collector,
                        List<? extends Processor> processors,
                        Path... sources) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var classPath = new ArrayList<>(testClasspath());

        classPath.add(classes);
        try (var fileManager = compiler.getStandardFileManager(collector, null, StandardCharsets.UTF_8)) {
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
            fileManager.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT,
                                             List.of(Files.createDirectories(dir.resolve("generated-" + run))));
            fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, classPath);
            var task = compiler.getTask(null,
                                        fileManager,
                                        collector,
                                        List.of("--release", Integer.toString(Runtime.version().feature())),
                                        null,
                                        fileManager.getJavaFileObjectsFromPaths(List.of(sources)));

            task.setProcessors(processors);

            return task.call();
        }
    }

    private static List<Path> testClasspath() {
        var classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));

        return Arrays.stream(classpath.split(File.pathSeparator))
                     .filter(entry -> !entry.isBlank())
                     .map(Path::of)
                     .toList();
    }
}
