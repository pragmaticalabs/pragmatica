// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.resource.artifact;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
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
    private static final byte[] JAVADOC = "javadoc-bytes".getBytes(StandardCharsets.UTF_8);

    private ConcurrentHashMap<String, byte[]> dht;
    private MavenProtocolHandler handler;
    private ArtifactStore store;

    @BeforeEach
    void setup() {
        dht = new ConcurrentHashMap<>();
        var storage = StorageInstance.storageInstance("test-artifacts", List.of(MemoryTier.memoryTier(64 * 1024 * 1024)));
        store = ArtifactStore.artifactStore(mapDht(), storage);
        handler = MavenProtocolHandler.mavenProtocolHandler(store);
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

    // verify-1132 B1 parsed Maven 3's timestamped SNAPSHOT names file by file; #1778 refuses SNAPSHOT versions in
    // the built-in store, so each of those PUTs is now a 400 that names the SNAPSHOT policy and stores nothing.
    @Test
    void timestampedSnapshotDeploy_isRefusedForEveryFile_andStoresNothing() {
        var dir = "/repository/org/example/lib/1.0.0-SNAPSHOT/lib-1.0.0-20260914.010203-1";

        for (var suffix : List.of(".jar", ".pom", "-sources.jar", "-javadoc.jar")) {
            var refused = put(dir + suffix, JAR);

            assertThat(refused.statusCode()).as(suffix).isEqualTo(400);
            assertThat(body(refused)).contains("SNAPSHOT");
        }

        assertThat(dht).as("nothing was written for a refused SNAPSHOT").isEmpty();
        assertThat(get("/repository/org/example/lib/maven-metadata.xml").statusCode()).isEqualTo(404);
    }

    @Test
    void mavenMetadata_latestAndReleaseFollowVersionOrder_notDeployOrder() {
        put("/repository/org/example/lib/2.0.0-rc1/lib-2.0.0-rc1.jar", JAR);
        put("/repository/org/example/lib/1.0.0/lib-1.0.0.jar", JAR);
        put("/repository/org/example/lib/1.0.0-rc4/lib-1.0.0-rc4.jar", JAR);

        var xml = body(get("/repository/org/example/lib/maven-metadata.xml"));

        assertThat(xml).contains("<latest>2.0.0-rc1</latest>");
        assertThat(xml.indexOf("<version>1.0.0-rc4</version>")).isLessThan(xml.indexOf("<version>1.0.0</version>"));
        assertThat(xml.indexOf("<version>1.0.0</version>")).isLessThan(xml.indexOf("<version>2.0.0-rc1</version>"));
    }

    private static final String METADATA = "/repository/org/example/lib/maven-metadata.xml";
    private static final java.util.Map<String, String> CHECKSUM_ALGORITHMS = java.util.Map.of(".md5", "MD5",
                                                                                              ".sha1", "SHA-1",
                                                                                              ".sha256", "SHA-256",
                                                                                              ".sha512", "SHA-512");

    /// #1833 — the request sequence of a standard `mvn deploy` followed by a resolving client: the
    /// artifact files and their sidecars, then the metadata and ITS sidecars (Gradle sends all four
    /// algorithms, Maven md5 and sha1), then a fetch of the metadata and of each checksum. Every
    /// metadata sidecar PUT used to be a 400 for sha256/sha512 and every metadata checksum GET a 400.
    @Test
    void mavenDeployShapedSequence_metadataChecksumsAreAcceptedAndServed() {
        put(BASE + ".jar", JAR);
        put(BASE + ".jar.sha1", "ignored".getBytes(StandardCharsets.UTF_8));
        put(BASE + ".pom", POM);
        assertThat(put(METADATA, "<metadata/>".getBytes(StandardCharsets.UTF_8)).statusCode()).isLessThan(300);

        for (var suffix : CHECKSUM_ALGORITHMS.keySet()) {
            var uploaded = put(METADATA + suffix, "client-side-digest".getBytes(StandardCharsets.UTF_8));

            assertThat(uploaded.statusCode()).as("PUT metadata%s", suffix).isLessThan(300);
            assertThat(body(uploaded)).as("PUT metadata%s says what happened to the bytes", suffix)
                                      .contains("\"status\":\"derived\"")
                                      .contains("not stored");
        }

        var metadata = get(METADATA);

        assertThat(metadata.statusCode()).isEqualTo(200);

        for (var entry : CHECKSUM_ALGORITHMS.entrySet()) {
            var checksum = get(METADATA + entry.getKey());

            assertThat(checksum.statusCode()).as("GET metadata%s", entry.getKey()).isEqualTo(200);
            assertThat(body(checksum)).as("metadata%s is the digest of the exact GET body", entry.getKey())
                                      .isEqualTo(digest(entry.getValue(), metadata.content()));
        }
    }

    /// The metadata and its checksum are two requests. A wall-clock `<lastUpdated>` made the second
    /// render differ from the first whenever a second boundary fell between them, so the sidecar could
    /// never be trusted to match; the rendering must be a pure function of the version set.
    @Test
    void metadata_isIdenticalAcrossASecondBoundary_soItsChecksumCanBeTrusted() throws InterruptedException {
        put(BASE + ".jar", JAR);

        var first = get(METADATA).content();

        Thread.sleep(1100);

        assertThat(get(METADATA).content()).isEqualTo(first);
        assertThat(new String(first, StandardCharsets.UTF_8)).as("<lastUpdated> stays, derived from the stored deploy time")
                                                             .contains("<lastUpdated>" + lastUpdatedOf("org.example:lib:1.0.0") + "</lastUpdated>");
        assertThat(body(get(METADATA + ".sha256"))).isEqualTo(digest("SHA-256", first));
    }

    @Test
    void metadataChecksums_ofAnArtifactWithNoVersions_are404() {
        for (var suffix : CHECKSUM_ALGORITHMS.keySet()) {
            assertThat(get(METADATA + suffix).statusCode()).as("GET metadata%s", suffix).isEqualTo(404);
        }
    }

    /// What the store persisted for the version's primary file at deploy time, formatted the way Maven writes it.
    private String lastUpdatedOf(String coordinates) {
        var deployedAt = store.metadata(Artifact.artifact(coordinates).unwrap()).await().unwrap().unwrap().deployedAt();

        return java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                                                 .format(java.time.Instant.ofEpochMilli(deployedAt).atOffset(java.time.ZoneOffset.UTC));
    }

    private static String digest(String algorithm, byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance(algorithm).digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
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
