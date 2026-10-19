// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.SharedTierConfig;
import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #249 / spec §A5.4: a configured S3 shared tier that cannot be admitted fails node storage construction --
/// it is a misconfiguration, never a silent fallback to the DHT or to no shared tier. No container needed: the
/// endpoint is a port nothing listens on, so these run in the default build. The happy path, ciphertext at rest
/// and the encryption-marker refusal run against MinIO in [StorageFactoryS3SharedTierTest] (`@Tag("S3")`).
class StorageFactorySharedTierBootTest {
    private static final String NODE_ID = "node-1";

    @TempDir
    Path tempDir;

    private StorageConfig defaults() {
        return StorageConfig.storageConfig(8L * 1024 * 1024,
                                           64L * 1024 * 1024,
                                           tempDir.resolve("storage").toString(),
                                           tempDir.resolve("metadata-snapshots").toString(),
                                           1000,
                                           "60s",
                                           5);
    }

    /// A port nothing listens on: the shared test-port helper issues a free one (#2016).
    private static int closedPort() {
        return ClusterTestPorts.freeClusterPort();
    }

    private Path credentialsFile() throws IOException {
        var file = tempDir.resolve("s3-credentials");
        Files.writeString(file, "[default]\naws_access_key_id = test\naws_secret_access_key = test-secret\n");
        return file;
    }

    private static SharedTierConfig.S3 s3(String endpoint, SharedTierConfig.Credentials credentials) {
        return new SharedTierConfig.S3(endpoint, "us-east-1", "aether-boot", "cluster-a/", true, credentials, 2, timeSpan(2).seconds());
    }

    private Cause refusal(Map<String, StorageConfig> configs) {
        return boot(configs).fold(cause -> cause, setups -> fail("storage construction must fail, built " + setups.keySet()));
    }

    /// Storage construction over `configs` derived as `Main` derives them, keyring-less.
    private Result<Map<String, StorageFactory.StorageSetup>> boot(Map<String, StorageConfig> configs) {
        return StorageFactory.createAll(StorageFactory.withDerivedPaths(configs, defaults(), false),
                                        NODE_ID,
                                        Option.none(),
                                        Option.none(),
                                        defaults());
    }

    /// Mutation that reddens it: skip `S3SharedTier#admit` (no probe): construction then succeeds, because nothing
    /// touches the bucket until the first read or write.
    @Test
    void createAll_unreachableS3Endpoint_failsConstruction_namingInstanceEndpointAndBucket() throws IOException {
        var endpoint = "http://127.0.0.1:" + closedPort();
        var cause = refusal(Map.of("content",
                                   StorageConfig.sharedOnly(s3(endpoint, new SharedTierConfig.Credentials.File(credentialsFile())))));

        assertThat(cause.message()).contains("'content'")
                                   .contains(endpoint)
                                   .contains("aether-boot")
                                   .contains("misconfiguration")
                                   .doesNotContain("test-secret");
        assertThat(sharedTierCause(cause).isPresent()).as("the typed boot cause travels in the chain").isTrue();
    }

    @Test
    void createAll_unreadableCredentialsFile_failsConstruction() throws IOException {
        var endpoint = "http://127.0.0.1:" + closedPort();
        var missing = tempDir.resolve("no-such-credentials");

        var cause = refusal(Map.of("content", StorageConfig.sharedOnly(s3(endpoint, new SharedTierConfig.Credentials.File(missing)))));

        assertThat(cause.message()).contains("'content'").contains(missing.toString());
    }

    /// Isolates the probe from the encryption-marker read that follows it: this endpoint answers every GET with
    /// 404 (so a keyring-less marker check alone passes) and refuses every PUT. Only the probe's PUT can fail the
    /// boot. Mutation that reddens it: skip the probe in `S3SharedTier#admit` -- construction then succeeds.
    @Test
    void createAll_endpointRefusingWrites_failsOnTheProbe() throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", StorageFactorySharedTierBootTest::readOnlyBucket);
        server.start();
        try {
            var endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            var cause = refusal(Map.of("content",
                                       StorageConfig.sharedOnly(s3(endpoint, new SharedTierConfig.Credentials.File(credentialsFile())))));

            assertThat(cause.message()).contains("'content'").contains("rejected");
        } finally {
            server.stop(0);
        }
    }

    private static void readOnlyBucket(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        var status = switch (exchange.getRequestMethod()) {
            case "PUT" -> 403;
            case "DELETE" -> 204;
            default -> 404;
        };
        var body = status == 403
                   ? "<Error><Code>AccessDenied</Code><Message>read-only</Message></Error>".getBytes(StandardCharsets.UTF_8)
                   : new byte[0];
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    /// An explicit instance keeps its own paths and still gets its shared tier (and its probe).
    @Test
    void createAll_explicitInstanceWithS3Section_failsOnItsProbe() throws IOException {
        var endpoint = "http://127.0.0.1:" + closedPort();
        var vault = StorageConfig.storageConfig(8L * 1024 * 1024,
                                                64L * 1024 * 1024,
                                                tempDir.resolve("vault/blocks").toString(),
                                                tempDir.resolve("vault/snapshots").toString(),
                                                1000,
                                                "60s",
                                                5)
                                 .withShared(s3(endpoint, new SharedTierConfig.Credentials.File(credentialsFile())));

        assertThat(refusal(Map.of("vault", vault)).message()).contains("'vault'").contains(endpoint);
    }

    /// B20: redirects are not followed. S3 answers a request for another region with a 301, and following it would
    /// send the request, signed for this host, to wherever the answer points. The redirect target here is a working
    /// bucket, so a followed redirect would also admit the tier. Mutation that reddens it: `HttpClient.Redirect.NORMAL`
    /// in `S3SharedTier#httpOperations`.
    @Test
    void createAll_endpointAnsweringARedirect_failsWithoutFollowingIt() throws IOException {
        var target = FakeBucket.start(Duration.ZERO);
        var redirecting = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        redirecting.createContext("/", exchange -> redirectTo(exchange, target.endpoint()));
        redirecting.start();
        try {
            var endpoint = "http://127.0.0.1:" + redirecting.getAddress().getPort();
            var cause = refusal(Map.of("content",
                                       StorageConfig.sharedOnly(s3(endpoint, new SharedTierConfig.Credentials.File(credentialsFile())))));

            assertThat(cause.message()).contains("'content'").contains("301");
            assertThat(target.requests()).as("the redirect target must never be contacted").isZero();
        } finally {
            redirecting.stop(0);
            target.stop();
        }
    }

    private static void redirectTo(HttpExchange exchange, String targetEndpoint) throws IOException {
        exchange.getRequestBody().readAllBytes();
        exchange.getResponseHeaders().add("Location", targetEndpoint + exchange.getRequestURI());
        exchange.sendResponseHeaders(301, -1);
        exchange.close();
    }

    /// B29: admission (probe PUT, GET and DELETE, then the marker GET) is bounded by [S3SharedTier#ADMISSION_REQUESTS]
    /// request timeouts, not one: four requests that each answer well within the 1 s request timeout take longer than
    /// one timeout together, and must still admit the tier. Mutation that reddens it: `ADMISSION_REQUESTS = 1`.
    @Test
    void createAll_admissionSlowerThanOneRequestTimeout_isAdmitted() throws IOException {
        var bucket = FakeBucket.start(Duration.ofMillis(400));
        try {
            var shared = new SharedTierConfig.S3(bucket.endpoint(),
                                                 "us-east-1",
                                                 "aether-boot",
                                                 "cluster-a/",
                                                 true,
                                                 new SharedTierConfig.Credentials.File(credentialsFile()),
                                                 2,
                                                 timeSpan(1).seconds());

            boot(Map.of("content", StorageConfig.sharedOnly(shared))).onFailure(cause -> fail("admission must fit its bound: "
                                                                                              + cause.message()));
            assertThat(bucket.requests()).as("probe PUT, GET, DELETE and the marker GET").isGreaterThanOrEqualTo(4);
        } finally {
            bucket.stop();
        }
    }

    /// A path-style bucket in memory: PUT stores, GET answers the object or a `NoSuchKey` 404, a list answers no
    /// keys, DELETE removes. Every answer waits `delay` first. Signatures are not checked.
    private record FakeBucket(HttpServer server, Map<String, byte[]> objects, AtomicInteger counter, Duration delay) {
        static FakeBucket start(Duration delay) throws IOException {
            var bucket = new FakeBucket(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0),
                                        new ConcurrentHashMap<>(),
                                        new AtomicInteger(),
                                        delay);
            bucket.server().createContext("/", bucket::handle);
            bucket.server().start();
            return bucket;
        }

        String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        int requests() {
            return counter.get();
        }

        void stop() {
            server.stop(0);
        }

        private void handle(HttpExchange exchange) throws IOException {
            counter.incrementAndGet();
            var body = exchange.getRequestBody().readAllBytes();
            pause();
            var path = exchange.getRequestURI().getPath();
            var listing = Option.option(exchange.getRequestURI().getQuery()).filter(query -> query.contains("list-type")).isPresent();
            switch (exchange.getRequestMethod()) {
                case "PUT" -> {
                    objects.put(path, body);
                    answer(exchange, 200, new byte[0]);
                }
                case "DELETE" -> {
                    objects.remove(path);
                    answer(exchange, 204, new byte[0]);
                }
                default -> answerGet(exchange, path, listing);
            }
        }

        private void answerGet(HttpExchange exchange, String path, boolean listing) throws IOException {
            if (listing) {
                answer(exchange, 200, "<ListBucketResult></ListBucketResult>".getBytes(StandardCharsets.UTF_8));
                return;
            }
            var stored = objects.get(path);
            if (stored == null) {
                answer(exchange, 404, "<Error><Code>NoSuchKey</Code><Message>absent</Message></Error>".getBytes(StandardCharsets.UTF_8));
                return;
            }
            answer(exchange, 200, stored);
        }

        private void pause() {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private static void answer(HttpExchange exchange, int status, byte[] body) throws IOException {
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        }
    }

    private static Option<S3SharedTier.SharedTierUnavailable> sharedTierCause(Cause cause) {
        return cause instanceof S3SharedTier.SharedTierUnavailable unavailable
               ? Option.some(unavailable)
               : cause.source().flatMap(StorageFactorySharedTierBootTest::sharedTierCause);
    }
}
