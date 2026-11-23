// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.StagedRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// #3021 gate 0(g): a node whose shared loader parent is a `FrameworkClassLoader` sees ONLY the jars of the framework
/// directory, never the test class path (which holds the module's own classes). Without this the S4 residual (a class the
/// packager failed to bundle is still found on the test class path) cannot close.
class FrameworkClassLoaderParentIsolationTest {
    @TempDir
    Path frameworkDir;

    @Test
    void fromDirectory_servesTheDirectoryJarsOnly_notTheApplicationClassPath() throws Exception {
        writeJarWith(Location.class);

        var loader = FrameworkClassLoader.fromDirectory(frameworkDir).unwrap();

        try (loader) {
            assertThat(loader.loadClass(Location.class.getName()).getClassLoader()).as("served from the directory jar").isSameAs(loader);
            assertThatThrownBy(() -> loader.loadClass(StagedRepository.class.getName())).as("on the application class path, not in the directory")
                                                                                       .isInstanceOf(ClassNotFoundException.class);
        }
    }

    @Test
    void fromDirectory_failsOnADirectoryWithoutJars_soTheNodeFallBackIsDetectable() {
        assertThat(FrameworkClassLoader.fromDirectory(frameworkDir).isFailure()).isTrue();
        assertThat(FrameworkClassLoader.fromDirectory(frameworkDir.resolve("missing")).isFailure()).isTrue();
    }

    private void writeJarWith(Class<?> type) throws IOException {
        var entry = type.getName().replace('.', '/') + ".class";

        try (InputStream bytes = type.getClassLoader().getResourceAsStream(entry);
             var jar = new JarOutputStream(Files.newOutputStream(frameworkDir.resolve("framework.jar")))) {
            jar.putNextEntry(new JarEntry(entry));
            jar.write(bytes.readAllBytes());
            jar.closeEntry();
        }
    }
}
