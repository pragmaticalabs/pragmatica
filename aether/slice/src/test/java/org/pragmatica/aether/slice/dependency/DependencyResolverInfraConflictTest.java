// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.slice.dependency;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.MethodHandle;
import org.pragmatica.aether.slice.SharedLibraryClassLoader;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.SliceLoadingFailure;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;

import static org.assertj.core.api.Assertions.assertThat;

/// #1184 through `DependencyResolver`, so the requester the conflict names is the one the resolver
/// passes (`manifest.artifact()`), at both entry points. The conflict fires while the dependency file
/// is processed, before any slice class is loaded, so the jar needs a manifest and a dependency file
/// and nothing else.
class DependencyResolverInfraConflictTest {
    private static final String SLICE_B = "org.example:slice-b:1.0.0";
    private static final String SLICE_CLASS = "com.example.SliceB";
    private static final String EXPECTED = "slice org.example:slice-b:1.0.0 requires org.example:lib:2.0.0"
                                         + " but org.example:lib:1.0.0 is already loaded by org.example:slice-a:1.0.0";
    private static final SliceInvokerFacade NO_INVOKER = new SliceInvokerFacade() {
        @Override
        public <R, T> Result<MethodHandle<R, T>> methodHandle(String sliceArtifact,
                                                              String methodName,
                                                              TypeToken<T> requestType,
                                                              TypeToken<R> responseType) {
            return Assertions.fail("the load must fail before any invoker is consulted");
        }
    };

    @TempDir
    Path tempDir;

    private final SharedLibraryClassLoader sharedLoader = new SharedLibraryClassLoader(getClass().getClassLoader());

    @AfterEach
    void closeLoader() throws Exception {
        sharedLoader.close();
    }

    @Test
    void resolve_failsNamingBothRequesters_whenTheSlicesInfraDependencyConflicts() throws Exception {
        var repository = repositoryWith(sliceJar());
        loadedBySliceA();

        DependencyResolver.resolve(artifact(SLICE_B), repository, SliceRegistry.sliceRegistry(), sharedLoader, NO_INVOKER)
                          .await()
                          .onSuccessRun(() -> Assertions.fail("slice-b was loaded although its [infra] version conflicts"))
                          .onFailure(cause -> {
                              assertThat(cause).isInstanceOf(SliceLoadingFailure.Fatal.SharedLoaderVersionConflict.class);
                              assertThat(cause.message()).contains(EXPECTED);
                          });
    }

    @Test
    void resolveWithContext_failsNamingBothRequesters_whenTheSlicesInfraDependencyConflicts() throws Exception {
        var repository = repositoryWith(sliceJar());
        loadedBySliceA();

        DependencyResolver.resolveWithContext(artifact(SLICE_B), repository, SliceRegistry.sliceRegistry(), sharedLoader, NO_INVOKER)
                          .await()
                          .onSuccessRun(() -> Assertions.fail("slice-b was loaded although its [infra] version conflicts"))
                          .onFailure(cause -> {
                              assertThat(cause).isInstanceOf(SliceLoadingFailure.Fatal.SharedLoaderVersionConflict.class);
                              assertThat(cause.message()).contains(EXPECTED);
                          });
    }

    private void loadedBySliceA() throws Exception {
        sharedLoader.addArtifact("org.example", "lib", Version.version("1.0.0").unwrap(), new URL("file:///repo/lib-1.0.0.jar"), "org.example:slice-a:1.0.0")
                    .onFailureRun(Assertions::fail);
    }

    private static Repository repositoryWith(URL sliceJar) {
        return artifact -> artifact.asString().equals(SLICE_B)
                           ? Promise.success(new Location(artifact, sliceJar))
                           : Promise.success(new Location(artifact, dummyUrl(artifact)));
    }

    private static URL dummyUrl(Artifact artifact) {
        try {
            return new URL("file:///repo/" + artifact.asString().replace(":", "-") + ".jar");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Artifact artifact(String coordinates) {
        return Artifact.artifact(coordinates).unwrap();
    }

    private URL sliceJar() throws IOException {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Slice-Artifact", SLICE_B);
        manifest.getMainAttributes().putValue("Slice-Class", SLICE_CLASS);
        var path = tempDir.resolve("slice-b.jar");

        try (var out = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            out.putNextEntry(new JarEntry("META-INF/dependencies/" + SLICE_CLASS));
            out.write("[infra]\norg.example:lib:2.0.0\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        return path.toUri().toURL();
    }
}
