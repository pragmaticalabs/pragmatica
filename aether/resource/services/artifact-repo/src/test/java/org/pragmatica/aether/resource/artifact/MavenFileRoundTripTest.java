// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.resource.artifact;

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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/// #281 — the files of one Maven coordinate are distinct artifacts. A standard `mvn deploy` PUTs
/// the jar, then the pom, then sidecars; the pom must not collide with the jar under a GAV-only
/// key. Drives the REAL store (in-memory DHT + memory tier) through `MavenProtocolHandler`, so
/// the observable Maven semantics are what is pinned, not the key shape.
class MavenFileRoundTripTest {
    private static final String BASE = "/repository/org/example/lib/1.0.0/lib-1.0.0";
    private static final byte[] JAR = "jar-bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] POM = "<project/>".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SOURCES = "sources-bytes".getBytes(StandardCharsets.UTF_8);

    private ConcurrentHashMap<String, byte[]> dht;
    private MavenProtocolHandler handler;

    @BeforeEach
    void setup() {
        dht = new ConcurrentHashMap<>();
        var storage = StorageInstance.storageInstance("test-artifacts", List.of(MemoryTier.memoryTier(64 * 1024 * 1024)));
        handler = MavenProtocolHandler.mavenProtocolHandler(ArtifactStore.artifactStore(mapDht(), storage));
    }

    @Test
    void jarThenPom_bothUploaded_andEachGetServesItsOwnBytes() {
        assertThat(body(put(BASE + ".jar", JAR))).contains("\"status\":\"uploaded\"");
        assertThat(body(put(BASE + ".pom", POM))).as("the pom is a second file, not a duplicate of the jar")
                                                 .contains("\"status\":\"uploaded\"");

        var pom = get(BASE + ".pom");

        assertThat(pom.statusCode()).isEqualTo(200);
        assertThat(pom.content()).as("GET .pom serves the pom bytes, not the jar's").isEqualTo(POM);

        var jar = get(BASE + ".jar");

        assertThat(jar.statusCode()).isEqualTo(200);
        assertThat(jar.content()).isEqualTo(JAR);
    }

    @Test
    void classifiedJar_isDistinctFromTheMainJar() {
        put(BASE + ".jar", JAR);

        assertThat(body(put(BASE + "-sources.jar", SOURCES))).contains("\"status\":\"uploaded\"");
        assertThat(get(BASE + "-sources.jar").content()).isEqualTo(SOURCES);
        assertThat(get(BASE + ".jar").content()).isEqualTo(JAR);
    }

    @Test
    void secondPutOfTheSameFile_isAlreadyPresent() {
        put(BASE + ".pom", POM);

        assertThat(body(put(BASE + ".pom", POM))).contains("\"status\":\"already-present\"");
    }

    @Test
    void fileNeverDeployed_is404_evenWhenSiblingExists() {
        put(BASE + ".jar", JAR);

        assertThat(get(BASE + ".pom").statusCode()).as("no pom was deployed").isEqualTo(404);
        assertThat(get(BASE + "-javadoc.jar").statusCode()).isEqualTo(404);
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
