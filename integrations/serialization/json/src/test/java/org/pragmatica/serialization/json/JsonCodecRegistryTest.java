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
package org.pragmatica.serialization.json;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.json.spi.JsonCodecProvider;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader.Provider;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The per-type lookup over providers compiled and listed by this test (package `p`, one class directory, one services
/// file listing healthy and broken entries alike), loaded by a child class loader so each type resolves against that
/// loader's providers through the real `ServiceLoader`: only the provider named after the requested type is
/// instantiated, a broken provider of that name fails the lookup, and a broken entry of another name changes nothing.
public class JsonCodecRegistryTest {
    private static final String CODEC = "public static final org.pragmatica.serialization.json.JsonTypeCodec<Object> CODEC ="
                                        + " org.pragmatica.serialization.json.JsonTypeCodec.jsonTypeCodec((out, value) -> out.writeNull(), in -> null);";

    @TempDir
    static Path dir;

    private static Path classes;
    private static URLClassLoader loader;

    @BeforeAll
    static void compileProviders() throws IOException {
        var sources = new TreeMap<String, String>();

        for (var type : List.of("Good", "Plain", "Thrower", "InitFails", "Unmarked", "OtherMarked", "Foreign", "NullType",
                                "NullCodec", "CodecThrows", "WrongIface", "Missing", "NoCtor", "Endless", "SelfCause", "Under_Score",
                                "Dol$lar", "CtorOverflow", "TypeOverflow")) {
            sources.put(type, "public record " + type + "() {}");
        }

        sources.put("Outer", "public interface Outer {\n    record In() {}\n}");
        sources.put("GoodJson", provider("GoodJson", "p.Good", "Good", "CODEC", ""));
        sources.put("ThrowerJson", provider("ThrowerJson", "p.Thrower", "Thrower", "CODEC", "throw new IllegalStateException(\"constructor failed\");"));
        sources.put("InitFailsJson", provider("InitFailsJson", "p.InitFails", "InitFails", "CODEC", "")
                     .replace("{\n    " + CODEC, "{\n    static {\n        if (Boolean.TRUE) {\n            throw new IllegalStateException(\"initialiser failed\");\n        }\n    }\n\n    " + CODEC));
        sources.put("UnmarkedJson", provider("UnmarkedJson", "p.Unmarked", "Unmarked", "CODEC", "").replace("@org.pragmatica.serialization.json.spi.GeneratedFor(\"p.Unmarked\")\n", ""));
        sources.put("OtherMarkedJson", provider("OtherMarkedJson", "p.Good", "OtherMarked", "CODEC", ""));
        sources.put("ForeignJson", provider("ForeignJson", "p.Foreign", "Good", "CODEC", ""));
        sources.put("NullTypeJson", provider("NullTypeJson", "p.NullType", "NullType", "CODEC", "").replace("return NullType.class;", "return null;"));
        sources.put("NullCodecJson", provider("NullCodecJson", "p.NullCodec", "NullCodec", "null", ""));
        sources.put("CodecThrowsJson", provider("CodecThrowsJson", "p.CodecThrows", "CodecThrows", "CODEC", "")
                     .replace("return CODEC;", "throw new IllegalStateException(\"codec failed\");"));
        sources.put("WrongIfaceJson", "@org.pragmatica.serialization.json.spi.GeneratedFor(\"p.WrongIface\")\npublic final class WrongIfaceJson {}");
        sources.put("NoCtorJson", provider("NoCtorJson", "p.NoCtor", "NoCtor", "CODEC", "").replace("public NoCtorJson()", "private NoCtorJson()"));
        sources.put("EndlessJson", provider("EndlessJson", "p.Endless", "Endless", "CODEC", "throw new Chained(0);"));
        sources.put("SelfCauseJson", provider("SelfCauseJson", "p.SelfCause", "SelfCause", "CODEC", "throw new SelfCaused();"));
        sources.put("CtorOverflowJson", provider("CtorOverflowJson", "p.CtorOverflow", "CtorOverflow", "CODEC",
                                                 "throw new StackOverflowError(\"constructor overflowed\");"));
        sources.put("TypeOverflowJson", provider("TypeOverflowJson", "p.TypeOverflow", "TypeOverflow", "CODEC", "")
                     .replace("return TypeOverflow.class;", "throw new StackOverflowError(\"type overflowed\");"));
        sources.put("Under_0ScoreJson", provider("Under_0ScoreJson", "p.Under_Score", "Under_Score", "CODEC", ""));
        sources.put("Outer_InJson", provider("Outer_InJson", "p.Outer.In", "Outer.In", "CODEC", ""));
        sources.put("Chained", """
            /// A cause chain with no end: every getCause is a fresh throwable one level deeper.
            public final class Chained extends RuntimeException {
                private final int depth;

                public Chained(int depth) {
                    super("level " + depth);
                    this.depth = depth;
                }

                @Override
                public synchronized Throwable getCause() {
                    return new Chained(depth + 1);
                }
            }""");
        sources.put("SelfCaused", """
            /// A throwable that is its own cause.
            public final class SelfCaused extends RuntimeException {
                public SelfCaused() {
                    super("its own cause");
                }

                @Override
                public synchronized Throwable getCause() {
                    return this;
                }
            }""");
        classes = compile(dir.resolve("classes"), sources);
        services(classes,
                 "# broken entries first, then the healthy ones",
                 "p.MissingJson",
                 "p.WrongIfaceJson",
                 "p.NoCtorJson",
                 "p.ThrowerJson",
                 "p.InitFailsJson",
                 "p.UnmarkedJson",
                 "p.OtherMarkedJson",
                 "p.ForeignJson",
                 "p.NullTypeJson",
                 "p.NullCodecJson",
                 "p.CodecThrowsJson",
                 "p.EndlessJson",
                 "p.SelfCauseJson",
                 "p.CtorOverflowJson",
                 "p.TypeOverflowJson",
                 "p.GoodJson   # trailing comment",
                 "p.Under_0ScoreJson",
                 "p.Outer_InJson");
        loader = loader(classes);
    }

    @AfterAll
    static void closeLoader() throws IOException {
        loader.close();
    }

    @Test
    void codecFor_resolvesTheProviderNamedAfterTheType_besideBrokenEntriesOfOtherNames() throws Exception {
        var good = type("p.Good");
        var codec = JsonCodecs.codecFor(good).unwrap().unwrap();

        assertSame(loader.loadClass("p.GoodJson").getField("CODEC").get(null), codec);
    }

    @Test
    void codecFor_isEmpty_forATypeWithoutAProvider_whateverOtherEntriesAreBroken() throws Exception {
        assertEquals(Result.success(Option.none()), JsonCodecs.codecFor(type("p.Plain")));
        assertEquals(Result.success(Option.none()), JsonCodecs.codecFor(String.class));
    }

    // The provider name mirrors the processor's flat names: declaring classes joined with `_`, `_` escaped as `_0`.
    @Test
    void codecFor_derivesTheProviderName_forNestedAndEscapedSimpleNames() throws Exception {
        assertTrue(JsonCodecs.codecFor(type("p.Outer$In")).unwrap().isPresent());
        assertTrue(JsonCodecs.codecFor(type("p.Under_Score")).unwrap().isPresent());
        assertEquals("p.Outer_InJson", JsonCodecRegistry.providerName(type("p.Outer$In")));
        assertEquals("p.Under_0ScoreJson", JsonCodecRegistry.providerName(type("p.Under_Score")));
        assertEquals("p.Dol_1larJson", JsonCodecRegistry.providerName(type("p.Dol$lar")));
        assertEquals("java.lang.StringJson", JsonCodecRegistry.providerName(String.class));
        assertEquals("org.pragmatica.serialization.json.JsonCodecRegistryTest_Probe_0TypeJson",
                     JsonCodecRegistry.providerName(Probe_Type.class));
    }

    record Probe_Type() {}

    // Listed but not loadable by the ServiceLoader: the class is missing, does not implement the interface, or has no
    // public no-argument constructor. The lookup cannot see the entry's name, so the services files attribute it.
    @Test
    void codecFor_fails_whenTheTypesListedProviderCannotBeLoaded() throws Exception {
        for (var name : List.of("Missing", "WrongIface", "NoCtor")) {
            var failed = providerFailed("p." + name);

            assertEquals("p." + name + "Json", failed.provider());
            assertTrue(failed.message().contains("is listed in META-INF/services/"), failed.message());
            assertTrue(chain(failed.origin()).contains("p." + name + "Json"), chain(failed.origin()));
        }
    }

    @Test
    void codecFor_fails_whenTheTypesProviderCannotBeInstantiated() throws Exception {
        assertTrue(providerFailed("p.Thrower").message().contains("could not be instantiated"));
        assertTrue(chain(providerFailed("p.Thrower").origin()).contains("constructor failed"));
        assertTrue(chain(providerFailed("p.InitFails").origin()).contains("initialiser failed"));
    }

    // The marker is checked before the constructor runs: a class at the name that was not generated for the type is
    // never run.
    @Test
    void codecFor_fails_whenTheProviderClassIsNotMarkedAsGeneratedForTheType() throws Exception {
        assertTrue(providerFailed("p.Unmarked").message().contains("not marked as generated for p.Unmarked"));
        assertTrue(providerFailed("p.OtherMarked").message().contains("not marked as generated for p.OtherMarked"));
    }

    @Test
    void codecFor_fails_whenTheProviderServesAnotherTypeOrNothing() throws Exception {
        assertTrue(providerFailed("p.Foreign").message().contains("type() does not return p.Foreign"));
        assertTrue(providerFailed("p.NullType").message().contains("type() does not return p.NullType"));
        assertTrue(providerFailed("p.NullCodec").message().contains("codec() returned null"));
        assertTrue(providerFailed("p.CodecThrows").message().contains("codec failed"));
    }

    // A cause chain is walked to a bounded depth and stops at a throwable already seen: no hang, no exhausted stack,
    // and the failure is the cached answer.
    @Test
    void codecFor_fails_withABoundedOrigin_forEndlessAndSelfReferentialCauseChains() throws Exception {
        var endless = providerFailed("p.Endless");
        var selfCaused = providerFailed("p.SelfCause");

        assertEquals(JsonCodecs.ProviderFailed.MAX_CAUSE_DEPTH, depth(endless.origin()));
        assertTrue(chain(endless.origin()).contains("level 0"), chain(endless.origin()));
        assertEquals(2, depth(selfCaused.origin()));
        assertTrue(selfCaused.origin().source().unwrap().message().contains("its own cause"));
        assertSame(JsonCodecs.codecFor(type("p.Endless")), JsonCodecs.codecFor(type("p.Endless")));
        assertSame(JsonCodecs.codecFor(type("p.SelfCause")), JsonCodecs.codecFor(type("p.SelfCause")));
    }

    // Every level's message, outermost first.
    private static String chain(Cause origin) {
        var text = new StringBuilder(origin.message());

        for (var level = origin.source(); level.isPresent(); level = level.flatMap(Cause::source)) {
            level.onPresent(cause -> text.append("\n").append(cause.message()));
        }

        return text.toString();
    }

    private static int depth(Cause origin) {
        var depth = 1;

        for (var level = origin.source(); level.isPresent(); level = level.flatMap(Cause::source)) {
            depth++;
        }

        return depth;
    }

    // The JDK's iterator reports a broken entry from hasNext; whatever the entry, the scan moves on to the next one.
    @Test
    void lookup_isolatesAHasNextFailure_andConsultsItOnlyForAListedName() throws Exception {
        var good = new Constructed(loader.loadClass("p.GoodJson").asSubclass(JsonCodecProvider.class));
        Iterable<Provider<JsonCodecProvider>> providers = () -> new HasNextFailsOnce(good);

        assertTrue(JsonCodecRegistry.lookup(type("p.Good"), "p.GoodJson", providers, () -> Result.success(true)).unwrap().isPresent());
        assertEquals(Result.success(Option.none()),
                     JsonCodecRegistry.lookup(type("p.Plain"), "p.PlainJson", providers, () -> Result.success(false)));
        var failed = assertInstanceOf(JsonCodecs.ProviderFailed.class,
                                      failureOf(JsonCodecRegistry.lookup(type("p.Plain"), "p.PlainJson", providers, () -> Result.success(true))));

        assertEquals("p.PlainJson", failed.provider());
        assertTrue(failed.origin().message().contains("unparseable services file"), failed.origin().message());
    }

    @Test
    void lookup_fails_whenTheServicesFilesCannotBeReadToAttributeAFailure() throws Exception {
        Iterable<Provider<JsonCodecProvider>> providers = () -> new HasNextFailsOnce(provider(GoodJsonStandIn.class));
        JsonCodecRegistry.Listing unreadable = () -> Causes.cause("unreadable").result();

        assertEquals("unreadable", failureOf(JsonCodecRegistry.lookup(type("p.Plain"), "p.PlainJson", providers, unreadable)).message());
    }

    // Obtaining the iterator is guarded too: for the registry that is loading the services of the class's loader.
    @Test
    void lookup_fails_whenTheProvidersCannotBeStreamed() throws Exception {
        Iterable<Provider<JsonCodecProvider>> providers = () -> {
            throw new ServiceConfigurationError("services cannot be loaded");
        };
        var failed = assertInstanceOf(JsonCodecs.ProviderFailed.class,
                                      failureOf(JsonCodecRegistry.lookup(type("p.Good"), "p.GoodJson", providers, () -> Result.success(true))));

        assertEquals(JsonCodecRegistry.SERVICES_ENTRY, failed.provider());
        assertTrue(failed.message().contains("services cannot be loaded"), failed.message());
    }

    // Documented exception to "never throws": a VirtualMachineError raised by the lookup's own call into the provider
    // instance (here type()) is not a failure a Cause can carry, so it propagates.
    @Test
    void codecFor_letsAVirtualMachineErrorOfTheProvidersTypeMethodPropagate() throws Exception {
        var type = type("p.TypeOverflow");
        var thrown = assertThrows(StackOverflowError.class, () -> JsonCodecs.codecFor(type));

        assertEquals("type overflowed", thrown.getMessage());
    }

    // Not an exception to "never throws": the JDK's ServiceLoader wraps every throwable of a provider's instantiation,
    // errors included, into a ServiceConfigurationError, so a constructor's StackOverflowError is a ProviderFailed, and
    // it is the cached answer like any other.
    @Test
    void codecFor_fails_whenTheProviderConstructorThrowsAVirtualMachineError() throws Exception {
        var failed = providerFailed("p.CtorOverflow");

        assertEquals("p.CtorOverflowJson", failed.provider());
        assertTrue(failed.message().contains(ServiceConfigurationError.class.getName()), failed.message());
        assertTrue(chain(failed.origin()).contains("java.lang.StackOverflowError: constructor overflowed"), chain(failed.origin()));
        assertSame(JsonCodecs.codecFor(type("p.CtorOverflow")), JsonCodecs.codecFor(type("p.CtorOverflow")));
    }

    // The scan stops at the provider of the requested name: the entries listed after it (Under_0ScoreJson,
    // Outer_InJson) are never streamed, so never loaded. The miss is the control: it streams, and so loads, them all.
    @Test
    void codecFor_streamsNoProviderAfterTheOneOfTheName() throws Exception {
        try (var hit = new RecordingLoader(classes); var miss = new RecordingLoader(classes)) {
            assertTrue(JsonCodecs.codecFor(hit.loadClass("p.Good")).unwrap().isPresent());
            assertEquals(Result.success(Option.none()), JsonCodecs.codecFor(miss.loadClass("p.Plain")));

            assertTrue(hit.loaded().contains("p.GoodJson"), hit.loaded().toString());
            assertFalse(hit.loaded().contains("p.Under_0ScoreJson"), hit.loaded().toString());
            assertFalse(hit.loaded().contains("p.Outer_InJson"), hit.loaded().toString());
            assertTrue(miss.loaded().containsAll(List.of("p.GoodJson", "p.Under_0ScoreJson", "p.Outer_InJson")),
                       miss.loaded().toString());
        }
    }

    // Isolation is per entry, not per line: the JDK rejects a services file with a malformed line as a whole, so its
    // healthy entry cannot be loaded either (listed, so a ProviderFailed). A services file of another class path entry
    // listing the same provider is unaffected, unless the name stood above the malformed line: the JDK counted it as
    // seen while parsing, and skips it in every later file.
    @Test
    void codecFor_fails_forTheHealthyEntryOfAServicesFileWithAMalformedLine() throws Exception {
        var healthyFirst = copyClasses(dir.resolve("healthy-first"));
        var malformedFirst = copyClasses(dir.resolve("malformed-first"));
        var elsewhere = Files.createDirectories(dir.resolve("elsewhere"));

        services(healthyFirst, "p.GoodJson", "not a name");
        services(malformedFirst, "not a name", "p.GoodJson");
        services(elsewhere, "p.GoodJson");
        try (var alone = loader(healthyFirst);
             var nameSeenFirst = loader(healthyFirst, elsewhere);
             var nameNotSeen = loader(malformedFirst, elsewhere)) {
            var failed = assertInstanceOf(JsonCodecs.ProviderFailed.class, failureOf(JsonCodecs.codecFor(alone.loadClass("p.Good"))));

            assertTrue(failed.message().contains("is listed in META-INF/services/"), failed.message());
            assertTrue(chain(failed.origin()).contains("Illegal configuration-file syntax"), chain(failed.origin()));
            assertInstanceOf(JsonCodecs.ProviderFailed.class, failureOf(JsonCodecs.codecFor(nameSeenFirst.loadClass("p.Good"))));
            assertTrue(JsonCodecs.codecFor(nameNotSeen.loadClass("p.Good")).unwrap().isPresent());
        }
    }

    // An exception that cannot describe itself is named by its class: building the failure never throws.
    @Test
    void lookup_describesAnUnprintableException_byItsClass() throws Exception {
        var goodJson = loader.loadClass("p.GoodJson").asSubclass(JsonCodecProvider.class);
        Iterable<Provider<JsonCodecProvider>> providers = () -> List.<Provider<JsonCodecProvider>>of(new Unprintable(goodJson)).iterator();
        var failed = assertInstanceOf(JsonCodecs.ProviderFailed.class,
                                      failureOf(JsonCodecRegistry.lookup(type("p.Good"), "p.GoodJson", providers, () -> Result.success(true))));

        assertTrue(failed.message().contains(UnprintableException.class.getName()), failed.message());
        assertTrue(failed.origin().message().contains(UnprintableException.class.getName()), failed.origin().message());
    }

    // The answer is computed once per class and kept: a second lookup is the same value, not a fresh scan.
    @Test
    void codecFor_answer_isCachedPerClass() throws Exception {
        var first = JsonCodecs.codecFor(type("p.Good"));

        assertSame(first, JsonCodecs.codecFor(type("p.Good")));
    }

    // The cache lives with the class: once a loader whose type was looked up is unreachable, it is collected.
    @Test
    void codecFor_doesNotKeepAChildClassLoaderReachable() throws Exception {
        var collected = lookUpInDiscardedLoader();

        for (int attempt = 0; attempt < 200 && collected.get() != null; attempt++) {
            System.gc();
            Thread.sleep(10);
        }

        assertNull(collected.get(), "the child class loader stayed reachable after its type was looked up");
    }

    private static WeakReference<ClassLoader> lookUpInDiscardedLoader() throws Exception {
        try (var child = loader(classes)) {
            var type = child.loadClass("p.Good");

            assertTrue(JsonCodecs.codecFor(type).unwrap().isPresent());
            assertSame(child, type.getClassLoader());

            return new WeakReference<>(child);
        }
    }

    private static JsonCodecs.ProviderFailed providerFailed(String type) throws ClassNotFoundException {
        return assertInstanceOf(JsonCodecs.ProviderFailed.class, failureOf(JsonCodecs.codecFor(type(type))));
    }

    private static Class<?> type(String name) throws ClassNotFoundException {
        var type = loader.loadClass(name);

        assertSame(loader, type.getClassLoader());

        return type;
    }

    /// Throws from the first hasNext(), as an iterator does for a services file it cannot parse, then yields `last`.
    static final class HasNextFailsOnce implements Iterator<Provider<JsonCodecProvider>> {
        private final Provider<JsonCodecProvider> last;
        private int calls;
        private boolean served;

        HasNextFailsOnce(Provider<JsonCodecProvider> last) {
            this.last = last;
        }

        @Override
        public boolean hasNext() {
            if (calls++ == 0) {
                throw new ServiceConfigurationError("unparseable services file");
            }

            return !served;
        }

        @Override
        public Provider<JsonCodecProvider> next() {
            served = true;

            return last;
        }
    }

    /// A class standing in for a provider that is never instantiated.
    public static final class GoodJsonStandIn implements JsonCodecProvider {
        @Override
        public Class<?> type() {
            return Object.class;
        }

        @Override
        public JsonTypeCodec<?> codec() {
            return null;
        }
    }

    record Streamed(Class<? extends JsonCodecProvider> type) implements Provider<JsonCodecProvider> {
        @Override
        public JsonCodecProvider get() {
            throw new AssertionError("a provider not of the requested name must not be instantiated");
        }
    }

    record Constructed(Class<? extends JsonCodecProvider> type) implements Provider<JsonCodecProvider> {
        @Override
        public JsonCodecProvider get() {
            try {
                return type.getConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
    }

    record Unprintable(Class<? extends JsonCodecProvider> type) implements Provider<JsonCodecProvider> {
        @Override
        public JsonCodecProvider get() {
            throw new UnprintableException();
        }
    }

    // An exception that cannot describe itself: its getMessage throws, so does every toString or stack trace print.
    static final class UnprintableException extends RuntimeException {
        @Override
        public String getMessage() {
            throw new IllegalStateException("getMessage failed");
        }
    }

    private static Provider<JsonCodecProvider> provider(Class<?> type) {
        return new Streamed(type.asSubclass(JsonCodecProvider.class));
    }

    private static String provider(String name, String marker, String type, String codec, String constructorBody) {
        return "@org.pragmatica.serialization.json.spi.GeneratedFor(\"" + marker + "\")\n"
               + "public final class " + name + " implements org.pragmatica.serialization.json.spi.JsonCodecProvider {\n"
               + "    " + CODEC + "\n\n"
               + "    public " + name + "() {\n        " + constructorBody + "\n    }\n\n"
               + "    @Override\n    public Class<?> type() {\n        return " + type + ".class;\n    }\n\n"
               + "    @Override\n    public org.pragmatica.serialization.json.JsonTypeCodec<?> codec() {\n        return " + codec + ";\n    }\n"
               + "}";
    }

    private static Path compile(Path classes, Map<String, String> sources) throws IOException {
        var source = Files.createDirectories(dir.resolve("src/p"));
        var files = new ArrayList<Path>();

        Files.createDirectories(classes);
        for (var entry : sources.entrySet()) {
            files.add(Files.writeString(source.resolve(entry.getKey() + ".java"), "package p;\n\n" + entry.getValue() + "\n"));
        }

        var compiler = ToolProvider.getSystemJavaCompiler();
        var collector = new DiagnosticCollector<JavaFileObject>();

        try (var fileManager = compiler.getStandardFileManager(collector, null, StandardCharsets.UTF_8)) {
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
            fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, testClasspath());
            var task = compiler.getTask(null,
                                        fileManager,
                                        collector,
                                        List.of("-proc:none", "--release", Integer.toString(Runtime.version().feature())),
                                        null,
                                        fileManager.getJavaFileObjectsFromPaths(files));

            assertTrue(task.call(), collector.getDiagnostics().toString());
        }

        return classes;
    }

    private static void services(Path classes, String... lines) throws IOException {
        var services = classes.resolve(JsonCodecRegistry.SERVICES_ENTRY);

        Files.createDirectories(services.getParent());
        Files.writeString(services, String.join("\n", lines) + "\n");
    }

    /// Records every class it defines itself, which for `p` is every class a lookup through it loads.
    static final class RecordingLoader extends URLClassLoader {
        private final List<String> loaded = Collections.synchronizedList(new ArrayList<>());

        RecordingLoader(Path classes) throws IOException {
            super(new URL[] {classes.toUri().toURL()}, JsonCodecRegistryTest.class.getClassLoader());
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            loaded.add(name);

            return super.findClass(name);
        }

        List<String> loaded() {
            return List.copyOf(loaded);
        }
    }

    // The compiled classes without their services file.
    private static Path copyClasses(Path target) throws IOException {
        try (var files = Files.walk(classes)) {
            for (var file : files.filter(Files::isRegularFile).filter(file -> !file.toString().contains("META-INF")).toList()) {
                var copy = target.resolve(classes.relativize(file));

                Files.createDirectories(copy.getParent());
                Files.copy(file, copy);
            }
        }

        return target;
    }

    private static URLClassLoader loader(Path... entries) throws IOException {
        var urls = new URL[entries.length];

        for (int index = 0; index < entries.length; index++) {
            urls[index] = entries[index].toUri().toURL();
        }

        return new URLClassLoader(urls, JsonCodecRegistryTest.class.getClassLoader());
    }

    private static List<Path> testClasspath() {
        var classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));

        return Arrays.stream(classpath.split(File.pathSeparator))
                     .filter(entry -> !entry.isBlank())
                     .map(Path::of)
                     .toList();
    }

    private static Cause failureOf(Result<?> result) {
        assertNotNull(result);

        return result.fold(cause -> cause, value -> {
            throw new AssertionError("expected a failure, got " + value);
        });
    }
}
