// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceLoadingFailure.Intermittent.ArtifactNotFound;
import org.pragmatica.aether.slice.SliceLoadingFailure.Intermittent.ArtifactUnavailable;
import org.pragmatica.aether.slice.dependency.SliceRegistry;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

/// The composite lookup behind `SliceStore.loadSlice` reports `ArtifactNotFound` only when EVERY repository
/// answered "absent"; a repository that could not answer (timeout, network) makes the artifact unavailable,
/// not absent, and the cause names what each repository said.
class SliceStoreLocateTest {
    private static final SliceInvokerFacade STUB_INVOKER = new SliceInvokerFacade() {
        @Override
        public <R, T> Result<MethodHandle<R, T>> methodHandle(String artifact,
                                                                  String method,
                                                                  TypeToken<T> requestType,
                                                                  TypeToken<R> responseType) {
            return Causes.cause("Stub invoker").result();
        }
    };

    private record TestAbsent(String detail) implements Repository.Absent {
        @Override
        public String message() {
            return "absent " + detail;
        }
    }

    @TempDir
    Path tempDir;

    private Artifact artifact;

    @BeforeEach
    void setUp() {
        artifact = Artifact.artifact("org.example:test-slice:1.0.0").unwrap();
    }

    @Test
    void loadSlice_notAbsent_whenOneRepositoryTimesOutAndOtherIsAbsent() {
        Repository timedOut = _ -> new CoreError.Timeout("dht read").promise();
        Repository absent = _ -> new TestAbsent("second").promise();

        var cause = loadFailure(List.of(timedOut, absent));

        assertThat(cause).isNotInstanceOf(ArtifactNotFound.class);
        assertThat(cause).isInstanceOf(ArtifactUnavailable.class);
        assertThat(cause.message()).contains("repository #0 unavailable: ")
                                   .contains("repository #1 absent: absent second");
    }

    @Test
    void loadSlice_artifactNotFound_whenEveryRepositoryAbsent() {
        Repository first = _ -> new TestAbsent("first").promise();
        Repository second = _ -> new TestAbsent("second").promise();

        var cause = loadFailure(List.of(first, second));

        assertThat(cause).isInstanceOf(ArtifactNotFound.class);
    }

    @Test
    void loadSlice_artifactNotFound_whenNoRepositories() {
        assertThat(loadFailure(List.of())).isInstanceOf(ArtifactNotFound.class);
    }

    @Test
    void loadSlice_locates_whenLaterRepositoryHasArtifact() throws IOException {
        var jar = Files.createFile(tempDir.resolve("test-slice-1.0.0.jar"));
        var url = jar.toUri().toURL();
        var consulted = new AtomicInteger();
        Repository absent = _ -> new TestAbsent("first").promise();
        Repository found = _ -> {
            consulted.incrementAndGet();
            return Location.location(artifact, url).async();
        };

        var cause = loadFailure(List.of(absent, found));

        // the empty file is no slice, so the load fails LATER than the lookup — which is the point
        assertThat(consulted.get()).isEqualTo(1);
        assertThat(cause).isNotInstanceOf(ArtifactNotFound.class)
                         .isNotInstanceOf(ArtifactUnavailable.class);
    }

    private Cause loadFailure(List<Repository> repositories) {
        var store = SliceStore.sliceStoreWithoutResourceProvisioning(SliceRegistry.sliceRegistry(),
                                                                     repositories,
                                                                     new SharedLibraryClassLoader(getClass().getClassLoader()),
                                                                     STUB_INVOKER,
                                                                     SliceActionConfig.sliceActionConfig());
        var failure = new Cause[1];

        store.loadSlice(artifact)
             .await()
             .onSuccessRun(Assertions::fail)
             .onFailure(cause -> failure[0] = cause);

        return failure[0];
    }
}
