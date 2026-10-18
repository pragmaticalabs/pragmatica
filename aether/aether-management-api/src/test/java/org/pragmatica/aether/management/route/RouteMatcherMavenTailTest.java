// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.management.route;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpMethod;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.http.HttpMethod.DELETE;
import static org.pragmatica.http.HttpMethod.GET;
import static org.pragmatica.http.HttpMethod.POST;
import static org.pragmatica.http.HttpMethod.PUT;


/// #1986: a Maven repository path is parsed by its coordinate (group / artifact / version / file), never by
/// segment count. The group id is every segment before the coordinate tail, so one logical operation resolves to
/// one route whether the group has 1, 2 or 3+ segments.
class RouteMatcherMavenTailTest {
    private static final List<String> GROUPS = List.of("org", "org/example", "org/example/deep");

    private final RouteMatcher matcher = RouteMatcher.shared();

    @Test
    void put_artifactFile_matchesArtifactPut_forAnyGroupDepth() {
        for (var group : GROUPS) {
            assertRoute(PUT, "/repository/" + group + "/hello/1.0.0/hello-1.0.0.jar", ManagementRoute.ARTIFACT_PUT, group, "hello", "1.0.0", "hello-1.0.0.jar");
        }
    }

    @Test
    void post_artifactFile_matchesArtifactPost_forAnyGroupDepth() {
        for (var group : GROUPS) {
            assertRoute(POST, "/repository/" + group + "/hello/1.0.0/hello-1.0.0.jar", ManagementRoute.ARTIFACT_POST, group, "hello", "1.0.0", "hello-1.0.0.jar");
        }
    }

    @Test
    void put_metadata_matchesArtifactPut_forAnyGroupDepth() {
        for (var group : GROUPS) {
            assertRoute(PUT, "/repository/" + group + "/hello/maven-metadata.xml", ManagementRoute.ARTIFACT_PUT, group, "hello", "", "maven-metadata.xml");
            assertRoute(PUT, "/repository/" + group + "/hello/maven-metadata.xml.sha1", ManagementRoute.ARTIFACT_PUT, group, "hello", "", "maven-metadata.xml.sha1");
        }
    }

    @Test
    void get_artifactFile_matchesArtifactGet_forAnyGroupDepth() {
        for (var group : GROUPS) {
            assertRoute(GET, "/repository/" + group + "/hello/1.0.0/hello-1.0.0.jar", ManagementRoute.ARTIFACT_GET, group, "hello", "1.0.0", "hello-1.0.0.jar");
        }
    }

    @Test
    void get_metadata_matchesMavenMetadata_forAnyGroupDepth() {
        for (var group : GROUPS) {
            var result = matcher.match(GET, "/repository/" + group + "/hello/maven-metadata.xml");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(matched -> {
                assertThat(matched.route()).isEqualTo(ManagementRoute.MAVEN_METADATA);
                assertThat(matched.param("groupPath").or("?")).isEqualTo(group);
                assertThat(matched.param("artifactId").or("?")).isEqualTo("hello");
            });
        }
    }

    @Test
    void delete_version_matchesArtifactDelete_forAnyGroupDepth() {
        for (var group : GROUPS) {
            var result = matcher.match(DELETE, "/repository/" + group + "/hello/1.0.0");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(matched -> {
                assertThat(matched.route()).isEqualTo(ManagementRoute.ARTIFACT_DELETE);
                assertThat(matched.param("groupPath").or("?")).isEqualTo(group);
            });
        }
    }

    @Test
    void get_info_matchesArtifactInfo_forAnyGroupDepth() {
        for (var group : GROUPS) {
            var result = matcher.match(GET, "/repository/info/" + group + "/hello/1.0.0");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(matched -> {
                assertThat(matched.route()).isEqualTo(ManagementRoute.ARTIFACT_INFO);
                assertThat(matched.param("groupPath").or("?")).isEqualTo(group);
                assertThat(matched.param("version").or("?")).isEqualTo("1.0.0");
            });
        }
    }

    @Test
    void get_artifactsList_stillMatchesTheListRoute() {
        var result = matcher.match(GET, "/repository/artifacts");

        assertThat(result.isSuccess()).isTrue();
        result.onSuccess(matched -> assertThat(matched.route()).isEqualTo(ManagementRoute.REPOSITORY_ARTIFACTS_LIST));
    }

    @Test
    void get_versionLevelMetadata_keepsTheVersionInTheGroupTail() {
        for (var group : GROUPS) {
            assertRoute(GET, "/repository/" + group + "/hello/1.0-SNAPSHOT/maven-metadata.xml", ManagementRoute.ARTIFACT_GET, group, "hello", "1.0-SNAPSHOT", "maven-metadata.xml");
        }
    }

    @Test
    void get_pathWithNoRoomForAGroup_isNoMatch() {
        assertThat(matcher.match(PUT, "/repository/hello/1.0.0/hello-1.0.0.jar").isFailure()).isTrue();
    }

    @Test
    void put_tooShortToBeACoordinate_isNoMatch() {
        assertThat(matcher.match(PUT, "/repository/hello/1.0.0").isFailure()).isTrue();
    }

    private void assertRoute(HttpMethod method, String path, ManagementRoute expected, String group, String artifact, String version, String file) {
        var result = matcher.match(method, path);

        assertThat(result.isSuccess()).as(path).isTrue();
        result.onSuccess(matched -> {
            assertThat(matched.route()).as(path).isEqualTo(expected);
            assertThat(matched.param("groupPath").or("?")).isEqualTo(group);
            assertThat(matched.param("artifactId").or("?")).isEqualTo(artifact);
            assertThat(matched.param("version").or("?")).isEqualTo(version);
            assertThat(matched.param("file").or("?")).isEqualTo(file);
        });
    }
}
