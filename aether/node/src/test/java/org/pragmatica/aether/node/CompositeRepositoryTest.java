// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceLoadingFailure;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1927: the repository the schema orchestrator uses is a real composite of the configured repositories, consulted in
/// order, falling through only when a repository ANSWERED "absent". It used to be `repositories.getFirst()`, so every
/// repository after the first was ignored. Reached through `AetherNode.compositeRepository`, the wiring seam.
class CompositeRepositoryTest {
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:orders-app:1.0.0").unwrap();

    private record Absent(String id) implements Repository.Absent {
        @Override
        public String message() {
            return "absent in " + id;
        }
    }

    private static final Cause UNREACHABLE = new SliceLoadingFailure.Intermittent.Timeout("locate", () -> "remote unreachable");

    @Test
    void aRepositoryThatAnsweredAbsent_fallsThroughToTheNext() throws Exception {
        var calls = new CopyOnWriteArrayList<String>();

        var located = composite(recording("first", calls, new Absent("first").promise()), recording("second", calls, found())).locate(ARTIFACT).await();

        assertThat(located.isSuccess()).as("the second repository has it: %s", located).isTrue();
        assertThat(calls).containsExactly("first", "second");
    }

    @Test
    void aRepositoryThatCouldNotAnswer_doesNotEndTheSearch() throws Exception {
        var located = composite(recording("first", new CopyOnWriteArrayList<>(), UNREACHABLE.promise()),
                                recording("second", new CopyOnWriteArrayList<>(), found())).locate(ARTIFACT).await();

        assertThat(located.isSuccess()).isTrue();
    }

    @Test
    void absentEverywhere_isNotFound() throws Exception {
        composite(recording("first", new CopyOnWriteArrayList<>(), new Absent("first").promise()),
                  recording("second", new CopyOnWriteArrayList<>(), new Absent("second").promise())).locate(ARTIFACT)
                                                                                                      .await()
                                                                                                      .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("not found"))
                                                                                                      .onFailure(cause -> assertThat(cause).isInstanceOf(SliceLoadingFailure.Intermittent.ArtifactNotFound.class));
    }

    @Test
    void aRepositoryThatCouldNotAnswer_andNoOneHasIt_isUnavailableNotNotFound() throws Exception {
        composite(recording("first", new CopyOnWriteArrayList<>(), UNREACHABLE.promise()),
                  recording("second", new CopyOnWriteArrayList<>(), new Absent("second").promise())).locate(ARTIFACT)
                                                                                                      .await()
                                                                                                      .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("not found"))
                                                                                                      .onFailure(cause -> assertThat(cause).isInstanceOf(SliceLoadingFailure.Intermittent.ArtifactUnavailable.class));
    }

    @Test
    void theClassifierLookup_consultsEveryRepositoryInOrder_withTheClassifier() throws Exception {
        var calls = new CopyOnWriteArrayList<String>();

        var located = composite(recording("first", calls, new Absent("first").promise()), recording("second", calls, found())).locate(ARTIFACT, "blueprint").await();

        assertThat(located.isSuccess()).isTrue();
        assertThat(calls).containsExactly("first:blueprint", "second:blueprint");
    }

    private static java.net.URL url() {
        try {
            return URI.create("file:///repo/orders-app.jar").toURL();
        } catch (java.net.MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Promise<Location> found() {
        return Promise.success(new Location(ARTIFACT, url()));
    }

    private static Repository recording(String id, List<String> calls, Promise<Location> answer) {
        return new Repository() {
            @Override
            public Promise<Location> locate(Artifact artifact) {
                calls.add(id);

                return answer;
            }

            @Override
            public Promise<Location> locate(Artifact artifact, String classifier) {
                calls.add(id + ":" + classifier);

                return answer;
            }
        };
    }

    private static Repository composite(Repository... repositories) throws Exception {
        Method method = AetherNode.class.getDeclaredMethod("compositeRepository", List.class);

        method.setAccessible(true);

        return (Repository) method.invoke(null, List.of(repositories));
    }
}
