// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.repository;

import java.net.URI;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #3021: the router itself, with tagged fake repositories: a module coordinate is answered by `staged` alone (both `locate`
/// forms), every other coordinate by `other` alone, and the router owns a private copy of the module set.
class StagedRepositoryTest {
    private static final Artifact MODULE = Artifact.artifact("org.example:echo:1.0.0").unwrap();
    private static final Artifact OTHER = Artifact.artifact("org.example:lib:1.0.0").unwrap();

    @Test
    void moduleCoordinate_isAnsweredByStagedOnly_inBothForms() {
        var router = StagedRepository.stagedRepository(Set.of(MODULE), tagged("staged"), tagged("other"));

        assertThat(hostOf(router.locate(MODULE))).isEqualTo("staged");
        assertThat(hostOf(router.locate(MODULE, "sources"))).isEqualTo("staged");
    }

    @Test
    void otherCoordinate_isAnsweredByOtherOnly_inBothForms() {
        var router = StagedRepository.stagedRepository(Set.of(MODULE), tagged("staged"), tagged("other"));

        assertThat(hostOf(router.locate(OTHER))).isEqualTo("other");
        assertThat(hostOf(router.locate(OTHER, "sources"))).isEqualTo("other");
    }

    @Test
    void moduleCoordinate_staysRoutedToStaged_whenTheCallersSetChangesAfterwards() {
        var live = new HashSet<>(Set.of(MODULE));
        var router = StagedRepository.stagedRepository(live, tagged("staged"), tagged("other"));

        live.remove(MODULE);

        assertThat(hostOf(router.locate(MODULE))).isEqualTo("staged");
    }

    /// A repository that answers every lookup with a URL whose host names it, so the test reads WHO answered.
    private static Repository tagged(String name) {
        return new Repository() {
            @Override
            public Promise<Location> locate(Artifact artifact) {
                return answer(artifact);
            }

            @Override
            public Promise<Location> locate(Artifact artifact, String classifier) {
                return answer(artifact);
            }

            private Promise<Location> answer(Artifact artifact) {
                return Location.location(artifact, url(name)).async();
            }
        };
    }

    private static URL url(String host) {
        return Result.lift(Causes::fromThrowable,
                           () -> URI.create("http://" + host + "/x.jar").toURL())
                   .unwrap();
    }

    private static String hostOf(Promise<Location> located) {
        return located.await()
                      .fold(cause -> "failure: " + cause.message(),
                            location -> location.url().getHost());
    }
}
