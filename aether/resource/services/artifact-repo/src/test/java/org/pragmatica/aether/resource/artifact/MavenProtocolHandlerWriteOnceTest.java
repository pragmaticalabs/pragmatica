// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.resource.artifact;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.artifact.ArtifactStore.ArchivePolicy;
import org.pragmatica.aether.resource.artifact.MavenProtocolHandler.MavenResponse;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1778 — the HTTP answers of the built-in store's Maven protocol for the write-once rules: a re-put of the
/// same content, a re-put of different content, a SNAPSHOT, an archived version, and the archive request
/// itself. Drives the REAL store, so the status codes are what a client sees.
class MavenProtocolHandlerWriteOnceTest {
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final String JAR_PATH = "/repository/org/example/lib/1.0.0/lib-1.0.0.jar";
    private static final String VERSION_PATH = "/repository/org/example/lib/1.0.0";
    private static final byte[] CONTENT = "jar-bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER = "other-jar-bytes".getBytes(StandardCharsets.UTF_8);

    private ReplicatedTestDht dht;
    private AtomicLong now;
    private MavenProtocolHandler handler;

    @BeforeEach
    void setup() {
        dht = ReplicatedTestDht.single();
        now = new AtomicLong(1_000_000L);
        var storage = StorageInstance.storageInstance("test-artifacts", List.of(MemoryTier.memoryTier(64 * 1024 * 1024)));
        var store = new ArtifactStoreImpl(dht, storage, ArchivePolicy.archivePolicy(timeSpan(7).days()), now::get);

        handler = MavenProtocolHandler.mavenProtocolHandler(store);
    }

    @Test
    void put_answers200Uploaded_thenAlreadyPresent_forTheSameContent() {
        assertThat(body(put(JAR_PATH, CONTENT))).contains("\"status\":\"uploaded\"");

        var again = put(JAR_PATH, CONTENT);

        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(body(again)).contains("\"status\":\"already-present\"");
    }

    @Test
    void put_answers409NamingBothDigests_forDifferentContent() {
        put(JAR_PATH, CONTENT);

        var refused = put(JAR_PATH, OTHER);

        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(body(refused)).contains("stored sha1=" + sha1Of(CONTENT)).contains("offered sha1=" + sha1Of(OTHER));
        assertThat(get(JAR_PATH).content()).as("the stored content is kept").isEqualTo(CONTENT);
    }

    @Test
    void put_answers400_forASnapshotVersion_andStoresNothing() {
        var refused = put("/repository/org/example/lib/1.0.0-SNAPSHOT/lib-1.0.0-SNAPSHOT.jar", CONTENT);

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(body(refused)).contains("SNAPSHOT versions are not accepted").contains("Local repository");
        assertThat(dht.union()).isEmpty();
    }

    @Test
    void get_answers404ForNeverWritten_andDeleteAnswers404() {
        assertThat(get(JAR_PATH).statusCode()).isEqualTo(404);
        assertThat(delete(VERSION_PATH).statusCode()).as("nothing stored, nothing to archive").isEqualTo(404);
    }

    @Test
    void delete_answers409_forAVersionYoungerThanTheRetention() {
        put(JAR_PATH, CONTENT);
        now.addAndGet(7 * DAY - 1);

        var refused = delete(VERSION_PATH);

        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(body(refused)).contains("minimum retention");
        assertThat(get(JAR_PATH).statusCode()).as("still served").isEqualTo(200);
    }

    @Test
    void archivedVersion_answers410OnGet_409OnPut_andIsDelisted() {
        put(JAR_PATH, CONTENT);
        now.addAndGet(7 * DAY);

        var archived = delete(VERSION_PATH);

        assertThat(archived.statusCode()).isEqualTo(200);
        assertThat(body(archived)).contains("\"status\":\"archived\"").contains("\"coords\":\"org.example:lib:1.0.0\"");
        assertThat(get(JAR_PATH).statusCode()).as("archived is gone, not missing").isEqualTo(410);
        assertThat(put(JAR_PATH, CONTENT).statusCode()).as("an archived coordinate is never reused").isEqualTo(409);
        assertThat(get("/repository/org/example/lib/maven-metadata.xml").statusCode()).as("delisted").isEqualTo(404);
        assertThat(delete(VERSION_PATH).statusCode()).as("archiving twice is a no-op").isEqualTo(200);
    }

    @Test
    void put_answers409WithTheLimitMessage_whenANewVersionExceedsTheBound() {
        var storage = StorageInstance.storageInstance("bound-artifacts", List.of(MemoryTier.memoryTier(16 * 1024 * 1024)));
        var small = MavenProtocolHandler.mavenProtocolHandler(new ArtifactStoreImpl(dht,
                                                                                    storage,
                                                                                    ArchivePolicy.archivePolicy(timeSpan(7).days()),
                                                                                    now::get,
                                                                                    ArtifactVersionIndex.inMemory(1)));

        assertThat(small.handlePut(JAR_PATH, CONTENT).await().unwrap().statusCode()).isEqualTo(200);

        var refused = small.handlePut("/repository/org/example/lib/2.0.0/lib-2.0.0.jar", CONTENT).await().unwrap();

        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(body(refused)).contains("artifact_max_versions");
    }

    @Test
    void checksumOfAnArchivedFile_answers410_whileALiveOneAnswers200() {
        put(JAR_PATH, CONTENT);

        assertThat(get(JAR_PATH + ".sha1").statusCode()).isEqualTo(200);

        now.addAndGet(7 * DAY);
        delete(VERSION_PATH);

        assertThat(get(JAR_PATH + ".sha1").statusCode()).as("the sidecar of an archived file is gone too")
                                                        .isEqualTo(410);
        assertThat(get("/repository/org/example/lib/9.9.9/lib-9.9.9.jar.sha1").statusCode()).as("never written stays 404")
                                                                                              .isEqualTo(404);
    }

    @Test
    void delete_answers400_forAPathThatIsNotAVersion() {
        assertThat(delete("/repository/org/lib").statusCode()).isEqualTo(400);
        assertThat(delete("/elsewhere/org/example/lib/1.0.0").statusCode()).isEqualTo(400);
    }

    private MavenResponse put(String path, byte[] content) {
        return handler.handlePut(path, content).await().unwrap();
    }

    private MavenResponse get(String path) {
        return handler.handleGet(path).await().unwrap();
    }

    private MavenResponse delete(String path) {
        return handler.handleDelete(path).await().unwrap();
    }

    private static String body(MavenResponse response) {
        return new String(response.content(), StandardCharsets.UTF_8);
    }

    private static String sha1Of(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
