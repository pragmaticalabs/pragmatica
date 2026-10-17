/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.serialization.json.tests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.codec.CodecProcessor;
import org.pragmatica.serialization.json.JsonCodecs;
import org.pragmatica.serialization.json.tests.other.Receipt;
import org.pragmatica.serialization.json.tests.other.ReceiptJson;

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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `JsonCodecs.codecFor` over artifacts compiled separately (each its own `javac` run into its own directory, as two
/// jars or `src/main` and `src/test` would be) and loaded together by a child class loader, while the thread's
/// context class loader stays the test's own: a type resolves against its own loader's providers.
class JsonCodecsClassPathTest {
    private static final String SERVICES = "META-INF/services/org.pragmatica.serialization.json.spi.JsonCodecProvider";

    @TempDir
    Path dir;

    // Two artifacts with @Json types in one package: both registries load, so neither type's codec vanishes.
    @Test
    void splitPackageAcrossTwoArtifacts_bothTypesResolve() throws Exception {
        var first = compile("first", Map.of("split/A.java", record("split", "A")));
        var second = compile("second", Map.of("split/B.java", record("split", "B")));

        assertNotEquals(services(first), services(second));
        try (var loader = loader(first, second)) {
            assertResolves(loader, "split.A");
            assertResolves(loader, "split.B");
        }
    }

    // The same type set compiled twice gives one registry name: the ServiceLoader loads it once, no conflict.
    @Test
    void sameTypeSetCompiledTwice_isOneProvider_andResolves() throws Exception {
        var first = compile("first", Map.of("dup/A.java", record("dup", "A")));
        var second = compile("second", Map.of("dup/A.java", record("dup", "A")));

        assertEquals(services(first), services(second));
        try (var loader = loader(first, second)) {
            assertResolves(loader, "dup.A");
        }
    }

    // Overlapping type sets give two differently named registries listing one class: that is the conflict.
    @Test
    void overlappingTypeSets_areAConflictForTheSharedType_only() throws Exception {
        var wide = compile("wide", Map.of("ov/A.java", record("ov", "A"), "ov/C.java", record("ov", "C")));
        var narrow = compile("narrow", Map.of("ov/A.java", record("ov", "A")));

        try (var loader = loader(wide, narrow)) {
            var conflict = assertInstanceOf(JsonCodecs.ConflictingProviders.class,
                                            failureOf(JsonCodecs.codecFor(loader.loadClass("ov.A"))));

            assertEquals(Set.of(services(wide).getFirst(), services(narrow).getFirst()), Set.copyOf(conflict.providers()));
            assertResolves(loader, "ov.C");
        }
    }

    // A type of the parent loader still resolves through the parent's providers.
    @Test
    void typeOfTheParentLoader_stillResolves_besideAChildLoader() throws Exception {
        var child = compile("child", Map.of("kid/K.java", record("kid", "K")));

        try (var loader = loader(child)) {
            assertResolves(loader, "kid.K");
            assertSame(ReceiptJson.CODEC, JsonCodecs.codecFor(Receipt.class).unwrap().unwrap());
            assertEquals(Thread.currentThread().getContextClassLoader(), JsonCodecsClassPathTest.class.getClassLoader());
        }
    }

    private static void assertResolves(ClassLoader loader, String name) throws ClassNotFoundException {
        var type = loader.loadClass(name);
        var codec = JsonCodecs.codecFor(type).unwrap();

        assertTrue(codec.isPresent(), name + ": " + codec);
        assertEquals(loader, type.getClassLoader());
    }

    private static String record(String packageName, String name) {
        return "package " + packageName + ";\n\n@org.pragmatica.serialization.json.Json\npublic record " + name + "(String value) {}\n";
    }

    private static List<String> services(Path classes) throws IOException {
        return Files.readAllLines(classes.resolve(SERVICES));
    }

    private static URLClassLoader loader(Path... classes) throws IOException {
        var urls = new URL[classes.length];

        for (int i = 0; i < classes.length; i++) {
            urls[i] = classes[i].toUri().toURL();
        }

        return new URLClassLoader(urls, JsonCodecsClassPathTest.class.getClassLoader());
    }

    private Path compile(String artifact, Map<String, String> sources) throws IOException {
        var root = dir.resolve(artifact);
        var classes = Files.createDirectories(root.resolve("classes"));
        var files = sources.entrySet()
                           .stream()
                           .map(entry -> write(root.resolve("src").resolve(entry.getKey()), entry.getValue()))
                           .toList();
        var compiler = ToolProvider.getSystemJavaCompiler();
        var collector = new DiagnosticCollector<JavaFileObject>();

        try (var fileManager = compiler.getStandardFileManager(collector, null, StandardCharsets.UTF_8)) {
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
            fileManager.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(Files.createDirectories(root.resolve("generated"))));
            fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, testClasspath());
            var task = compiler.getTask(null,
                                        fileManager,
                                        collector,
                                        List.of("--release", Integer.toString(Runtime.version().feature())),
                                        null,
                                        fileManager.getJavaFileObjectsFromPaths(files));

            task.setProcessors(List.of(new CodecProcessor()));
            assertTrue(task.call(), collector.getDiagnostics().toString());
        }

        return classes;
    }

    private static Path write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());

            return Files.writeString(file, text);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static List<Path> testClasspath() {
        var classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));

        return Arrays.stream(classpath.split(File.pathSeparator))
                     .filter(entry -> !entry.isBlank())
                     .map(Path::of)
                     .toList();
    }

    private static Cause failureOf(Result<?> result) {
        return StrictDecodingTest.failureOf(result);
    }
}
