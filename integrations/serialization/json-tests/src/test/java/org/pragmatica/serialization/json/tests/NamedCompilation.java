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

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.serialization.json.JsonOptions;
import org.pragmatica.serialization.json.JsonTypeCodec;
import org.pragmatica.serialization.json.NamingStrategy;


/// The payload sources compiled again, at test time, by the real codec processor under `-Ajson.naming=<strategy>`:
/// naming is a build-time choice, so each strategy needs its own compilation of the same types. The classes load in
/// a child-first loader (the test class path holds the default compilation under the same names), and are driven by
/// reflection: `Samples` builds the values, `<Flat>Json.codec(options)` the codecs.
final class NamedCompilation {
    static final String PACKAGE = "org.pragmatica.serialization.json.tests";
    // Surefire runs in the module directory.
    static final Path SOURCES = Path.of("src/test/java/org/pragmatica/serialization/json/tests");

    static final List<String> PAYLOADS = List.of("Samples.java",
                                                 "Ecommerce.java",
                                                 "Ticketing.java",
                                                 "Catalog.java",
                                                 "Telemetry.java",
                                                 "OptionsFixtures.java");

    private static final Map<NamingStrategy, ClassLoader> LOADERS = new ConcurrentHashMap<>();

    private NamedCompilation() {}

    static ClassLoader loader(NamingStrategy naming) {
        return LOADERS.computeIfAbsent(naming, NamedCompilation::compile);
    }

    /// The value a static no-argument method of `type` (a simple name in the payload package) returns.
    static Object sample(NamingStrategy naming, String type, String method) {
        try {
            return loader(naming).loadClass(PACKAGE + "." + type)
                         .getMethod(method)
                         .invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /// The generated entry point `entryPoint`'s codec under `options`.
    @SuppressWarnings("unchecked")
    static JsonTypeCodec<Object> codec(NamingStrategy naming, String entryPoint, JsonOptions options) {
        try {
            return (JsonTypeCodec<Object>) loader(naming).loadClass(PACKAGE + "." + entryPoint)
                                                 .getMethod("codec", JsonOptions.class)
                                                 .invoke(null, options);
        } catch (InvocationTargetException e) {
            throw new AssertionError(e.getTargetException());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static ClassLoader compile(NamingStrategy naming) {
        try {
            var out = Files.createTempDirectory("json-naming-" + naming);
            var compiler = ToolProvider.getSystemJavaCompiler();
            var diagnostics = new DiagnosticCollector<JavaFileObject>();

            try (var files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
                var units = files.getJavaFileObjectsFromPaths(PAYLOADS.stream().map(SOURCES::resolve).toList());
                var options = List.of("-d",
                                      out.toString(),
                                      "-classpath",
                                      System.getProperty("java.class.path"),
                                      "-proc:full",
                                      "-processor",
                                      "org.pragmatica.serialization.codec.CodecProcessor",
                                      "-Ajson.naming=" + naming.name());

                if (!compiler.getTask(null, files, diagnostics, options, null, units).call()) {
                    throw new AssertionError("compilation under " + naming + " failed: " + diagnostics.getDiagnostics());
                }
            }

            return new ChildFirst(out.toUri().toURL(),
                                  NamedCompilation.class.getClassLoader());
        } catch (MalformedURLException e) {
            throw new AssertionError(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Loads the payload package's classes from the compilation's output first; everything else from the parent.
    static final class ChildFirst extends URLClassLoader {
        ChildFirst(URL classes, ClassLoader parent) {
            super(new URL[]{classes}, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                var loaded = findLoadedClass(name);

                if (loaded == null && name.startsWith(PACKAGE + ".")) {
                    loaded = findOwn(name);
                }

                if (loaded == null) {
                    return super.loadClass(name, resolve);
                }

                if (resolve) {
                    resolveClass(loaded);
                }

                return loaded;
            }
        }

        // A class of the package this compilation did not produce (a test class) is the parent's.
        private Class<?> findOwn(String name) {
            try {
                return findClass(name);
            } catch (ClassNotFoundException notCompiledHere) {
                return null;
            }
        }
    }
}
