package org.pragmatica.serialization.codec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The services file across incremental compilations into one class output, driven through the platform `javac`
/// twice: an entry a previous compilation left is kept while its registry class still resolves (a package not
/// recompiled this time keeps its codecs), dropped once its class is gone (a stale entry would make every
/// `ServiceLoader` lookup fail), and replaced when this compilation wrote a registry for the same package.
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
        p1Registry = "p1." + CodecProcessorTest.registryName("p1", "", "p1.A");
        p2Registry = "p2." + CodecProcessorTest.registryName("p2", "", "p2.B");

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
        var newP1Registry = "p1." + CodecProcessorTest.registryName("p1", "", "p1.A", "p1.A2");

        compile("second", source("p1/A.java", P1_A), source("p1/A2.java", P1_A2));

        assertTrue(Files.exists(classes.resolve(p1Registry.replace('.', '/') + ".class")));
        assertEquals(List.of(p2Registry, newP1Registry), services());
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
        var compiler = ToolProvider.getSystemJavaCompiler();
        var collector = new DiagnosticCollector<JavaFileObject>();
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

            task.setProcessors(List.of(new CodecProcessor()));
            assertTrue(task.call(), collector.getDiagnostics().toString());
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
