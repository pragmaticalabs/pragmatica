// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.slice;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.dependency.SliceRegistry;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// #1436 — through the REAL composite behind `SliceStore.loadSlice`, with a real slice jar that declares a
/// `[shared]` dependency. Fixing `SharedDependencyLoader.locateOptional` alone would change nothing in
/// production if the composite folded every per-repository failure to "not found" before it got there, so a
/// probe that hands `locateOptional` a stub repository directly cannot pin this; this one cannot be satisfied
/// without both halves (the composite keeps the unavailable cause, #1769, and the loader no longer folds it).
class SliceStoreSharedLocateTest {
    private static final SliceInvokerFacade STUB_INVOKER = new SliceInvokerFacade() {
        @Override
        public <R, T> Result<MethodHandle<R, T>> methodHandle(String artifact,
                                                                  String method,
                                                                  TypeToken<T> requestType,
                                                                  TypeToken<R> responseType) {
            return Causes.cause("Stub invoker").result();
        }
    };
    private static final String SLICE_CLASS = "com.example.NoSuchSlice";

    private record Absent(String detail) implements Repository.Absent {
        @Override
        public String message() {
            return "absent " + detail;
        }
    }

    @TempDir
    Path tempDir;

    private Artifact slice;
    private URL sliceJar;
    private SharedLibraryClassLoader sharedLoader;

    @BeforeEach
    void setUp() throws IOException {
        slice = Artifact.artifact("org.example:test-slice:1.0.0").unwrap();
        sliceJar = writeSliceJarDeclaring("org.example:lib:1.0.0");
        sharedLoader = new SharedLibraryClassLoader(getClass().getClassLoader());
    }

    /// RED on the unmodified base: the slice load went on past the `[shared]` stage, `lib` was recorded as
    /// runtime-provided, and the failure arrived later from the class that was never there.
    @Test
    void loadSlice_sharedLibraryRepositoryTimesOut_failsUnavailable_andRegistersNothing() {
        var cause = loadFailure(List.of(repositoryAnswering(_ -> new CoreError.Timeout("dht read").promise())));

        assertThat(cause).isInstanceOf(SliceLoadingFailure.Intermittent.ArtifactUnavailable.class);
        assertThat(cause.message()).contains("org.example:lib:1.0.0");
        assertThat(sharedLoader.loadedBy("org.example", "lib").isPresent()).as("an unreachable library is not runtime-provided")
                                                                          .isFalse();
    }

    /// The control: every repository answering "absent" is the genuine not-found, so the library is still
    /// registered runtime-provided and the load proceeds to its own, later, failure.
    @Test
    void loadSlice_sharedLibraryAbsentEverywhere_stillRegistersRuntimeProvided() {
        var cause = loadFailure(List.of(repositoryAnswering(_ -> new Absent("lib").promise())));

        assertThat(sharedLoader.loadedBy("org.example", "lib").unwrap()).isEqualTo("org.example:test-slice:1.0.0");
        assertThat(cause).as("the load went past the [shared] stage and failed on the missing slice class")
                         .isNotInstanceOf(SliceLoadingFailure.Intermittent.ArtifactUnavailable.class);
    }

    private Repository repositoryAnswering(Repository forLib) {
        return requested -> requested.equals(slice)
                            ? Location.location(requested, sliceJar).async()
                            : forLib.locate(requested);
    }

    private Cause loadFailure(List<Repository> repositories) {
        var store = SliceStore.sliceStoreWithoutResourceProvisioning(SliceRegistry.sliceRegistry(),
                                                                     repositories,
                                                                     sharedLoader,
                                                                     STUB_INVOKER,
                                                                     SliceActionConfig.sliceActionConfig());
        var failure = new Cause[1];

        store.loadSlice(slice)
             .await()
             .onSuccessRun(Assertions::fail)
             .onFailure(cause -> failure[0] = cause);

        return failure[0];
    }

    private URL writeSliceJarDeclaring(String sharedCoordinates) throws IOException {
        var manifest = new Manifest();
        var attributes = manifest.getMainAttributes();

        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(SliceManifest.SLICE_ARTIFACT_ATTR, slice.asString());
        attributes.putValue(SliceManifest.SLICE_CLASS_ATTR, SLICE_CLASS);

        var path = Files.createFile(tempDir.resolve("test-slice-1.0.0.jar"));

        try (var out = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            out.putNextEntry(new JarEntry("META-INF/dependencies/" + SLICE_CLASS));
            out.write(("[shared]\n" + sharedCoordinates + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }

        return path.toUri().toURL();
    }
}
