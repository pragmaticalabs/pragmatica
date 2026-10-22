// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/// Test-scope probe for the worker-replica-feed members (W-OBS) that do not exist yet. The tripwire tests assert
/// "it is not there" while enabled and "it is there" in their `@Disabled` halves. Types are found by SIMPLE NAME on
/// the test classpath, so the tripwires do not hard-code the package W1 will choose. This is the cluster-module copy
/// of the helper of the same name in `aether/node` tests (separate modules, no shared test jar).
final class ReplicaSeam {
    private ReplicaSeam() {}

    /// True when a class file named `<simpleName>.class` is on the classpath, in a directory or a jar.
    static boolean typeExists(String simpleName) {
        var entry = simpleName + ".class";

        return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                     .map(Path::of)
                     .anyMatch(root -> contains(root, entry));
    }

    /// The body of a `@Disabled` presence probe: it ALWAYS fails, so enabling it can never pass vacuously. Whoever enables it after the
    /// member lands has to replace the call with the real check of `property`.
    static AssertionError failClosed(boolean memberExists, String addedBy, String property) {
        return new AssertionError(memberExists
                                  ? addedBy + " landed: replace this body with the real check, which is: " + property
                                  : addedBy + " is not there yet; the property to check once it is: " + property);
    }

    /// True when `KVStore` declares a public method with this name, whatever its parameters.
    static boolean kvStoreHasMethod(String name) {
        return Arrays.stream(KVStore.class.getMethods())
                     .map(Method::getName)
                     .anyMatch(name::equals);
    }

    private static boolean contains(Path root, String entry) {
        return Files.isDirectory(root)
               ? directoryContains(root, entry)
               : jarContains(root, entry);
    }

    private static boolean directoryContains(Path root, String entry) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.anyMatch(file -> file.getFileName() != null && file.getFileName().toString().equals(entry));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean jarContains(Path jar, String entry) {
        if (!Files.isRegularFile(jar) || !jar.toString().endsWith(".jar")) {
            return false;
        }

        try (var file = new JarFile(jar.toFile())) {
            return file.stream().anyMatch(item -> item.getName().endsWith("/" + entry) || item.getName().equals(entry));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
