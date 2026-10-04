// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.resource.artifact;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.artifact.MavenProtocolHandler.MavenResponse;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.Partition;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import static org.assertj.core.api.Assertions.assertThat;

/// #1919 — `<group>/<artifact>/<version>/maven-metadata.xml` is what Maven writes for a SNAPSHOT version. The
/// built-in store holds no SNAPSHOTs (#1778), so it is not served; but it must be REFUSED ACCURATELY, not as the
/// generic `400 Cannot parse path` that told a client its (well-formed) request was malformed.
///
/// Why refuse rather than serve: `mvn deploy` of a release never writes version-level metadata, and a SNAPSHOT
/// deploy is refused at its first file PUT, so serving this form would imply SNAPSHOT support the store
/// deliberately lacks. PUT answers 400 (the same status the store's own SNAPSHOT refusal uses), GET answers 404
/// (nothing is ever stored there) — both naming the reason.
class MavenVersionLevelMetadataTest {
    private static final String VERSION_METADATA = "/repository/org/example/lib/1.0.0-SNAPSHOT/maven-metadata.xml";
    private static final String ARTIFACT_METADATA = "/repository/org/example/lib/maven-metadata.xml";
    private static final byte[] JAR = "jar-bytes".getBytes(StandardCharsets.UTF_8);

    private ConcurrentHashMap<String, byte[]> dht;
    private MavenProtocolHandler handler;

    @BeforeEach
    void setup() {
        dht = new ConcurrentHashMap<>();
        var storage = StorageInstance.storageInstance("test-artifacts", List.of(MemoryTier.memoryTier(64 * 1024 * 1024)));

        handler = MavenProtocolHandler.mavenProtocolHandler(ArtifactStore.artifactStore(mapDht(), storage));
    }

    @Test
    void put_ofVersionLevelMetadata_isRefusedAs400_namingWhy_andStoresNothing() {
        for (var suffix : List.of("", ".sha1", ".md5")) {
            var response = put(VERSION_METADATA + suffix);

            assertThat(response.statusCode()).as("PUT %s", suffix).isEqualTo(400);
            assertThat(body(response)).as("PUT %s", suffix)
                                      .contains("Version-level maven-metadata.xml")
                                      .contains("SNAPSHOT")
                                      .doesNotContain("Cannot parse path");
        }

        assertThat(dht).as("nothing is stored for a refused request").isEmpty();
    }

    @Test
    void get_ofVersionLevelMetadata_is404_namingWhy() {
        for (var suffix : List.of("", ".sha1", ".md5")) {
            var response = get(VERSION_METADATA + suffix);

            assertThat(response.statusCode()).as("GET %s", suffix).isEqualTo(404);
            assertThat(body(response)).as("GET %s", suffix)
                                      .contains("Version-level maven-metadata.xml")
                                      .doesNotContain("Cannot parse path");
        }
    }

    /// A release version directory has no version-level metadata either: Maven never writes it.
    @Test
    void get_ofAReleaseVersionsMetadata_isTheSameRefusal() {
        var response = get("/repository/org/example/lib/1.0.0/maven-metadata.xml");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(body(response)).contains("Version-level maven-metadata.xml");
    }

    /// Controls: artifact-level metadata is untouched, and a path that is genuinely malformed still says so.
    @Test
    void artifactLevelMetadata_isUnchanged_andARealParseFailureStillAnswersCannotParse() {
        put("/repository/org/example/lib/1.0.0/lib-1.0.0.jar", JAR);

        assertThat(get(ARTIFACT_METADATA).statusCode()).isEqualTo(200);
        assertThat(put(ARTIFACT_METADATA).statusCode()).isLessThan(300);

        var malformed = get("/repository/org/maven-metadata.xml");

        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(body(malformed)).contains("Cannot parse path");
    }

    private MavenResponse put(String path) {
        return put(path, JAR);
    }

    private MavenResponse put(String path, byte[] content) {
        return handler.handlePut(path, content).await().unwrap();
    }

    private MavenResponse get(String path) {
        return handler.handleGet(path).await().unwrap();
    }

    private static String body(MavenResponse response) {
        return new String(response.content(), StandardCharsets.UTF_8);
    }

    private DHTClient mapDht() {
        return new DHTClient() {
            @Override
            public Promise<Unit> put(byte[] key, byte[] value) {
                dht.put(new String(key, StandardCharsets.UTF_8), value);

                return Promise.unitPromise();
            }

            @Override
            public Promise<Option<byte[]>> get(byte[] key) {
                return Promise.success(Option.option(dht.get(new String(key, StandardCharsets.UTF_8))));
            }

            @Override
            public Promise<Boolean> exists(byte[] key) {
                return Promise.success(dht.containsKey(new String(key, StandardCharsets.UTF_8)));
            }

            @Override
            public Promise<Boolean> remove(byte[] key) {
                return Promise.success(dht.remove(new String(key, StandardCharsets.UTF_8)) != null);
            }

            @Override
            public Partition partitionFor(byte[] key) {
                return Partition.partition(Math.abs(new String(key, StandardCharsets.UTF_8).hashCode()) % 1024).unwrap();
            }
        };
    }
}
