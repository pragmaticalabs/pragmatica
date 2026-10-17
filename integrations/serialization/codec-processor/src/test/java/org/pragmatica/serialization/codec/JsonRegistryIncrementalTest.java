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

/// The services file and the registries across incremental compilations into one class output, driven through the
/// platform `javac` several times: an entry a previous compilation left is kept while its registry class still
/// resolves (a package not recompiled this time keeps its codecs), dropped once its class is gone (a stale entry would
/// make every `ServiceLoader` lookup fail), and replaced when this compilation wrote a registry for the same package,
/// which then also lists the package's types that were not recompiled, as long as they still resolve.
class JsonRegistryIncrementalTest {
    private static final String SERVICES = "META-INF/services/org.pragmatica.serialization.json.spi.JsonCodecProvider";
    private static final String P1_A = "package p1;\n\n@org.pragmatica.serialization.json.Json\npublic record A(String a) {}\n";
    private static final String P1_A2 = "package p1;\n\n@org.pragmatica.serialization.json.Json\npublic record A2(String a2) {}\n";
    private static final String P2_B = "package p2;\n\n@org.pragmatica.serialization.json.Json\npublic record B(long b) {}\n";

    @TempDir
    Path dir;

    private Path classes;
    private String p1Registry;
    private String p2Registry;

    @BeforeEach
    void compileBothPackages() throws IOException {
        classes = Files.createDirectories(dir.resolve("classes"));
        p1Registry = "p1." + CodecProcessorTest.registryName("p1", "", "A");
        p2Registry = "p2." + CodecProcessorTest.registryName("p2", "", "B");

        compile("first", source("p1/A.java", P1_A), source("p2/B.java", P2_B));
        assertEquals(List.of(p1Registry, p2Registry), services());
    }

    @Test
    void recompilingOnePackage_keepsTheOtherPackagesStillResolvableEntry() throws IOException {
        compile("second", source("p1/A.java", P1_A));

        assertEquals(List.of(p2Registry, p1Registry), services());
    }

    @Test
    void anEntryWhoseRegistryClassIsGone_isDropped() throws IOException {
        Files.delete(classes.resolve(p2Registry.replace('.', '/') + ".class"));

        compile("second", source("p1/A.java", P1_A));

        assertEquals(List.of(p1Registry), services());
    }

    // p1 now holds two types: its registry gets a new name, and the old p1 entry (still resolvable, its class file
    // is still in the output) must not stay beside it, or A would be listed twice and every lookup of it conflict.
    @Test
    void anEntryForAPackageRecompiledThisTime_isReplacedByTheNewRegistry() throws IOException {
        var newP1Registry = "p1." + CodecProcessorTest.registryName("p1", "", "A", "A2");

        compile("second", source("p1/A.java", P1_A), source("p1/A2.java", P1_A2));

        assertTrue(Files.exists(classes.resolve(p1Registry.replace('.', '/') + ".class")));
        assertEquals(List.of(p2Registry, newP1Registry), services());
    }

    // Recompiling only A of p1 {A, A2} rewrites p1's registry; A2 (not recompiled, still in the output) stays listed,
    // so the registry keeps the {A, A2} name and both still resolve.
    @Test
    void recompilingSomeTypesOfAPackage_keepsItsOtherTypes() throws Exception {
        var both = "p1." + CodecProcessorTest.registryName("p1", "", "A", "A2");

        compile("second", source("p1/A.java", P1_A), source("p1/A2.java", P1_A2));
        compile("third", source("p1/A.java", P1_A));

        assertEquals(List.of(p2Registry, both), services());
        assertEquals(List.of("p1.A", "p1.A2"), manifest(both));
        assertResolve("p1.A", "p1.A2", "p2.B");
    }

    // Once A2's source and classes are gone, the next compilation of p1 drops it: its registry lists A alone.
    @Test
    void aCarriedOverTypeWhoseClassesAreGone_isDropped() throws Exception {
        compile("second", source("p1/A.java", P1_A), source("p1/A2.java", P1_A2));
        Files.delete(dir.resolve("src/p1/A2.java"));
        try (var generated = Files.list(classes.resolve("p1"))) {
            for (var file : generated.filter(path -> path.getFileName().toString().startsWith("A2")).toList()) {
                Files.delete(file);
            }
        }

        compile("third", source("p1/A.java", P1_A));

        assertEquals(List.of(p2Registry, p1Registry), services());
        assertEquals(List.of("p1.A"), manifest(p1Registry));
        assertResolve("p1.A", "p2.B");
    }

    // A2 recompiled without @Json: its old entry point is still in the output, but it is no longer a @Json type.
    @Test
    void aTypeRecompiledWithoutTheAnnotation_isDropped() throws Exception {
        compile("second", source("p1/A.java", P1_A), source("p1/A2.java", P1_A2));
        compile("third", source("p1/A.java", P1_A), source("p1/A2.java", "package p1;\n\npublic record A2(String a2) {}\n"));

        assertEquals(List.of(p2Registry, p1Registry), services());
    }

    // Early's registry carries Late over from the previous compilation, then another processor generates Late again
    // in a later round: Late is already listed, so no second registry lists it too (which would make it a conflict).
    @Test
    void aCarriedOverTypeThatArrivesAgainLate_isListedOnce() throws Exception {
        var early = "package com.example;\n\n@Deprecated\n@org.pragmatica.serialization.json.Json\npublic record Early(String text) {}\n";
        var both = "com.example." + CodecProcessorTest.registryName("com.example", "", "Early", "Late");

        compile("second",
                source("com/example/Early.java", early),
                source("com/example/Late.java", "package com.example;\n\n@org.pragmatica.serialization.json.Json\npublic record Late(long id) {}\n"));
        Files.delete(dir.resolve("src/com/example/Late.java"));

        var collector = new DiagnosticCollector<JavaFileObject>();

        assertTrue(run("third", collector, List.of(new CodecProcessor(), new CodecProcessorTest.LateJsonType(both)),
                       source("com/example/Early.java", early)),
                   collector.getDiagnostics().toString());
        assertEquals(List.of(p1Registry, p2Registry, both), services());
        assertResolve("com.example.Early", "com.example.Late");
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

    private void assertResolve(String... types) throws Exception {
        try (var loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
            for (var type : types) {
                var codec = JsonCodecs.codecFor(loader.loadClass(type));

                assertTrue(codec.isSuccess() && codec.unwrap().isPresent(), type + ": " + codec);
            }
        }
    }

    private List<String> manifest(String registry) throws IOException {
        return Files.readAllLines(classes.resolve(JsonRegistries.manifestFile(registry)));
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
