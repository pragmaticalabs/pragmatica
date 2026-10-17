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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pragmatica.json.ValueObjectModule;
import org.pragmatica.serialization.codec.CodecProcessor;
import org.pragmatica.serialization.json.JsonTypeCodec;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The generator and the production Jackson mapper apply one value-object rule (owner ruling: value objects are
/// unwrapped everywhere on slice HTTP, one wire form). For every fixture in [ValueObjectFixtures] both sides give
/// the same verdict, the verdict is the expected one, and the bytes agree both ways.
class ValueObjectRuleAgreementTest {
    record Fixture(String name, Object sample, boolean valueObject) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Fixture> fixtures() {
        return Stream.of(new Fixture("Email", new ValueObjectFixtures.Email("a@b.c"), true),
                         new Fixture("Count", new ValueObjectFixtures.Count(7), true),
                         new Fixture("Flag", new ValueObjectFixtures.Flag(true), true),
                         new Fixture("Amount", new ValueObjectFixtures.Amount(new BigDecimal("12.50")), true),
                         new Fixture("Local", new ValueObjectFixtures.Local("x"), true),
                         new Fixture("TwoParts", new ValueObjectFixtures.TwoParts("a", "b"), false),
                         new Fixture("Tags", new ValueObjectFixtures.Tags(List.of("a", "b")), false),
                         new Fixture("WrongName", new ValueObjectFixtures.WrongName("x"), false),
                         new Fixture("WrongReturn", new ValueObjectFixtures.WrongReturn("x"), false),
                         new Fixture("WrongParams", new ValueObjectFixtures.WrongParams("x"), false),
                         new Fixture("Hidden", new ValueObjectFixtures.Hidden("x"), false));
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    void generatorAndJackson_giveTheSameValueObjectVerdict(Fixture fixture) throws Exception {
        var generator = generatedCodecInterface(fixture).getDeclaredMethods();
        var generatorSaysValueObject = Arrays.stream(generator)
                                             .anyMatch(method -> method.getName().equals("parse"));

        assertEquals(fixture.valueObject(), generatorSaysValueObject, "generator verdict");
        assertEquals(fixture.valueObject(),
                     ValueObjectModule.valueObjectFactory(fixture.sample().getClass()).isPresent(),
                     "Jackson verdict");
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    void generatedBytes_equalProductionMapperBytes_andEachDecodesTheOther(Fixture fixture) throws Exception {
        var codec = codec(fixture);
        var generated = codec.encode(fixture.sample());
        var jackson = Baseline.MAPPER.writeAsBytes(fixture.sample()).unwrap();

        assertEquals(new String(jackson, StandardCharsets.UTF_8), new String(generated, StandardCharsets.UTF_8));
        assertEquals(fixture.sample(), codec.decode(jackson).unwrap());
        assertEquals(fixture.sample(), Baseline.MAPPER.readBytes(generated, fixture.sample().getClass()).unwrap());
    }

    @Test
    void genericRecord_isRefusedByTheGenerator_andIsNoValueObjectForJackson(@TempDir Path tempDir) throws Exception {
        var source = Files.writeString(tempDir.resolve("GenericHolder.java"),
                                       """
                                       package fixture;

                                       @org.pragmatica.serialization.json.Json
                                       public record GenericHolder(org.pragmatica.serialization.json.tests.ValueObjectFixtures.Tagged<String> value) {}
                                       """);
        var errors = compileErrors(tempDir, source);

        assertTrue(errors.contains("generic record"), errors);
        assertFalse(ValueObjectModule.valueObjectFactory(ValueObjectFixtures.Tagged.class).isPresent());
    }

    @SuppressWarnings("unchecked")
    private static JsonTypeCodec<Object> codec(Fixture fixture) throws Exception {
        var holder = Class.forName(ValueObjectFixtures.class.getPackageName() + ".ValueObjectFixtures_" + fixture.name() + "JsonCodecs");
        var codecs = (Map<Class<?>, JsonTypeCodec<?>>) holder.getField("CODECS").get(null);

        return (JsonTypeCodec<Object>) codecs.get(fixture.sample().getClass());
    }

    private static Class<?> generatedCodecInterface(Fixture fixture) throws Exception {
        return Class.forName(ValueObjectFixtures.class.getPackageName() + ".ValueObjectFixtures_" + fixture.name()
                             + "JsonCodecs$ValueObjectFixtures_" + fixture.name() + "Json");
    }

    private static String compileErrors(Path tempDir, Path source) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var collector = new DiagnosticCollector<JavaFileObject>();

        try (var fileManager = compiler.getStandardFileManager(collector, null, StandardCharsets.UTF_8)) {
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(Files.createDirectories(tempDir.resolve("classes"))));
            fileManager.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(Files.createDirectories(tempDir.resolve("generated"))));
            fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, testClasspath());
            var task = compiler.getTask(null, fileManager, collector, List.of("-proc:full"), null,
                                        fileManager.getJavaFileObjectsFromPaths(List.of(source)));

            task.setProcessors(List.of(new CodecProcessor()));
            task.call();
        }

        return collector.getDiagnostics()
                        .stream()
                        .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                        .map(diagnostic -> diagnostic.getMessage(null))
                        .reduce("", (left, right) -> left + right + "\n");
    }

    private static List<Path> testClasspath() {
        var classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));

        return Arrays.stream(classpath.split(File.pathSeparator))
                     .filter(entry -> !entry.isBlank())
                     .map(Path::of)
                     .toList();
    }
}
