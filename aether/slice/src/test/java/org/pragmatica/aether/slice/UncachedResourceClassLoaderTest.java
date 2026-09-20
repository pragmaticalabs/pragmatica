// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.artifact.Version;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/// s25-inv1277: `URLClassLoader.getResourceAsStream` registers the JVM-shared cached `JarFile` of the
/// jar in the loader's closeables, so closing a slice loader after a manifest read closed a jar every
/// other reader in the process shares — and a `JarURLConnection.connect` racing that close re-cached
/// the closed instance for good (`zip file closed` on every later blueprint apply of that artifact).
///
/// The pin is deterministic: hold the shared instance J1 through a caching `JarURLConnection`, read
/// the entry through the loader, close the loader, then ask J1 for the entry. Unfixed, J1 is closed.
class UncachedResourceClassLoaderTest {
    private static final String ENTRY = "META-INF/slice/EchoService.manifest";
    private static final ClassLoader PLATFORM = ClassLoader.getPlatformClassLoader();

    @TempDir
    Path tempDir;

    @Test
    void sliceClassLoader_closedAfterAResourceRead_leavesTheSharedJarUsable() throws IOException {
        var jar = sliceJar("slice.jar");

        assertSharedJarSurvivesLoaderClose(jar, new SliceClassLoader(new URL[]{jar}, PLATFORM));
    }

    @Test
    void sharedLibraryClassLoader_closedAfterAResourceRead_leavesTheSharedJarUsable() throws IOException {
        var jar = sliceJar("shared.jar");
        var loader = new SharedLibraryClassLoader(PLATFORM);

        loader.addArtifact("org.example", "shared", Version.version("1.0.0").unwrap(), jar);

        assertSharedJarSurvivesLoaderClose(jar, loader);
    }

    @Test
    void frameworkClassLoader_closedAfterAResourceRead_leavesTheSharedJarUsable() throws IOException {
        var jar = sliceJar("framework.jar");

        assertSharedJarSurvivesLoaderClose(jar, new FrameworkClassLoader(new URL[]{jar}));
    }

    @Test
    void uncachedResourceClassLoader_closedAfterAResourceRead_leavesTheSharedJarUsable() throws IOException {
        var jar = sliceJar("bare.jar");

        assertSharedJarSurvivesLoaderClose(jar, new UncachedResourceClassLoader(new URL[]{jar}, PLATFORM));
    }

    /// The second half of "never touches the shared cache": a read through the loader does not POPULATE
    /// it either, so the loader can never be the opener that re-caches a closed jar in the JDK's
    /// `connect` race. Observable: a cached read keeps answering from the inode it opened first; an
    /// uncached one sees the jar that is on disk now.
    @Test
    void resourceRead_afterTheJarIsReplacedOnDisk_seesTheNewContent() throws IOException {
        var path = tempDir.resolve("replaced.jar");

        writeJar(path, "slice.name=v1\n");

        try (var loader = new UncachedResourceClassLoader(new URL[]{path.toUri().toURL()}, PLATFORM)) {
            assertThat(read(loader)).isEqualTo("slice.name=v1\n");

            var next = tempDir.resolve("replaced.jar.next");

            writeJar(next, "slice.name=v2\n");
            Files.move(next, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

            assertThat(read(loader)).describedAs("cached: still v1 from the replaced inode").isEqualTo("slice.name=v2\n");
        }
    }

    private static String read(URLClassLoader loader) throws IOException {
        try (var in = loader.getResourceAsStream(ENTRY)) {
            assertThat(in).isNotNull();

            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /// The override keeps `URLClassLoader`'s delegation: `getResource` asks the parent first, so a
    /// slice can still read a resource only its shared-library parent ships. `findResource` here would
    /// answer `null` for it, silently.
    @Test
    void resourceRead_answersFromTheParent_whenOnlyTheParentShipsIt() throws IOException {
        var parentJar = tempDir.resolve("parent.jar");
        var ownJar = tempDir.resolve("own-without-it.jar");

        writeJar(parentJar, ENTRY, "slice.name=from-parent\n");
        writeJar(ownJar, "META-INF/slice/Other.manifest", "slice.name=own\n");

        try (var parent = new URLClassLoader(new URL[]{parentJar.toUri().toURL()}, PLATFORM);
             var loader = new UncachedResourceClassLoader(new URL[]{ownJar.toUri().toURL()}, parent)) {
            assertThat(read(loader)).isEqualTo("slice.name=from-parent\n");
        }
    }

    private static void assertSharedJarSurvivesLoaderClose(URL jar, URLClassLoader loader) throws IOException {
        var shared = sharedJarFile(jar);

        // Control, inside the same run: a caching connection through the loader's own resource URL
        // answers with the SAME instance, so J1 is what an unfixed loader registers and closes.
        assertThat(((JarURLConnection) loader.getResource(ENTRY).openConnection()).getJarFile())
                .describedAs("the shared cache is in play for the loader's resource URL")
                .isSameAs(shared);

        try (loader) {
            try (var in = loader.getResourceAsStream(ENTRY)) {
                assertThat(in).isNotNull();
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("slice.name=echo\n");
            }
        }

        // Unfixed: IllegalStateException: zip file closed — the loader's close() closed J1.
        assertThat(shared.getEntry(ENTRY)).describedAs("closing the loader must not close the JVM-shared jar")
                                          .isNotNull();

        try (var in = new URI("jar:" + jar + "!/" + ENTRY).toURL().openStream()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("slice.name=echo\n");
        } catch (java.net.URISyntaxException e) {
            throw new IOException(e);
        }
    }

    /// J1: the instance every caching `jar:` opener of this jar in the JVM shares.
    private static JarFile sharedJarFile(URL jar) throws IOException {
        try {
            var connection = (JarURLConnection) new URI("jar:" + jar + "!/" + ENTRY).toURL().openConnection();

            connection.setUseCaches(true);

            return connection.getJarFile();
        } catch (java.net.URISyntaxException e) {
            throw new IOException(e);
        }
    }

    private URL sliceJar(String name) throws IOException {
        var path = tempDir.resolve(name);

        writeJar(path, "slice.name=echo\n");

        return path.toUri().toURL();
    }

    private static void writeJar(Path path, String manifestText) throws IOException {
        writeJar(path, ENTRY, manifestText);
    }

    private static void writeJar(Path path, String entry, String manifestText) throws IOException {
        try (var out = new JarOutputStream(Files.newOutputStream(path))) {
            out.putNextEntry(new JarEntry(entry));
            out.write(manifestText.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }
}
