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

/// The request sequence `mvn deploy` sends for a `maven-plugin` packaging: the plugin's jar and pom, the artifact-level
/// `maven-metadata.xml`, and then the GROUP-level `<group path>/maven-metadata.xml` that lists the plugin's goal prefix.
/// The group-level file answered `400 Cannot parse path` for a two-segment group (`org/example`), because the parser
/// only knew the artifact-level and version-level shapes.
///
/// It is refused accurately, not stored: the built-in repository is an internal cache for deployed slices, not a
/// Maven plugin repository (`operators/artifact-repository.md`), and serving plugin-prefix resolution would need the
/// store to read each pom's packaging. A deploy of a plugin therefore still stops at that PUT, but with a message that
/// says why instead of blaming the request.
class MavenPluginDeployShapedSequenceTest {
    private static final String PLUGIN = "/repository/org/example/demo-maven-plugin/1.0.0/demo-maven-plugin-1.0.0";
    private static final String GROUP_METADATA = "/repository/org/example/maven-metadata.xml";
    private static final byte[] BYTES = "bytes".getBytes(StandardCharsets.UTF_8);

    private ConcurrentHashMap<String, byte[]> dht;
    private MavenProtocolHandler handler;

    @BeforeEach
    void setup() {
        dht = new ConcurrentHashMap<>();
        var storage = StorageInstance.storageInstance("test-artifacts", List.of(MemoryTier.memoryTier(64 * 1024 * 1024)));

        handler = MavenProtocolHandler.mavenProtocolHandler(ArtifactStore.artifactStore(mapDht(), storage));
    }

    @Test
    void pluginDeploySequence_storesTheArtifact_thenRefusesTheGroupLevelMetadataAccurately() {
        assertThat(put(PLUGIN + ".jar").statusCode()).isEqualTo(200);
        assertThat(put(PLUGIN + ".pom").statusCode()).isEqualTo(200);
        assertThat(put("/repository/org/example/demo-maven-plugin/maven-metadata.xml").statusCode()).isLessThan(300);

        for (var suffix : List.of("", ".md5", ".sha1", ".sha256", ".sha512")) {
            var response = put(GROUP_METADATA + suffix);

            assertThat(response.statusCode()).as("PUT group metadata%s", suffix).isEqualTo(400);
            assertThat(body(response)).as("PUT group metadata%s", suffix)
                                      .contains("Group-level maven-metadata.xml")
                                      .contains("Maven plugin")
                                      .doesNotContain("Cannot parse path");
        }

        assertThat(get(PLUGIN + ".jar").statusCode()).as("the plugin artifact itself is stored").isEqualTo(200);
    }

    @Test
    void getOfGroupLevelMetadata_is404_namingWhy() {
        for (var suffix : List.of("", ".md5", ".sha1", ".sha256", ".sha512")) {
            var response = get(GROUP_METADATA + suffix);

            assertThat(response.statusCode()).as("GET group metadata%s", suffix).isEqualTo(404);
            assertThat(body(response)).as("GET group metadata%s", suffix).contains("Group-level maven-metadata.xml");
        }
    }

    /// Controls: artifact-level and version-level handling is untouched, and a real parse failure still says so.
    @Test
    void artifactLevelAndVersionLevelMetadata_areUnchanged_andARealParseFailureStillAnswersCannotParse() {
        put(PLUGIN + ".jar");

        assertThat(get("/repository/org/example/demo-maven-plugin/maven-metadata.xml").statusCode()).isEqualTo(200);
        assertThat(body(get("/repository/org/example/demo-maven-plugin/1.0.0/maven-metadata.xml"))).contains("Version-level");

        var malformed = get("/repository/maven-metadata.xml");

        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(body(malformed)).contains("Cannot parse path");
    }

    private MavenResponse put(String path) {
        return handler.handlePut(path, BYTES).await().unwrap();
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
