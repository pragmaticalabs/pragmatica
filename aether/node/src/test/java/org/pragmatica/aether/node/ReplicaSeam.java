// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/// Test-scope probe for the worker-replica-feed types (W-OBS) that do not exist yet. The tripwire tests assert
/// "the type is not there" while enabled and "the type is there" in their `@Disabled` halves; this finds a type by
/// SIMPLE NAME anywhere on the test classpath, so the tripwires do not hard-code the package W1 or W2 will choose.
public final class ReplicaSeam {
    private ReplicaSeam() {}

    /// True when a class file named `<simpleName>.class` is on the classpath, in a directory or a jar.
    public static boolean typeExists(String simpleName) {
        var entry = simpleName + ".class";

        return Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                     .map(Path::of)
                     .anyMatch(root -> contains(root, entry));
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
