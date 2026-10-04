// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.metrics.artifact.ArtifactMetricsCollector;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.resource.artifact.ArtifactStore;
import org.pragmatica.aether.resource.artifact.ArtifactStore.ArtifactMetadata;
import org.pragmatica.aether.resource.artifact.ArtifactStore.ResolvedArtifact;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.QueryParams;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1102: `GET /repository/info/<group>/<artifact>/<version>` positional-parsed its coordinates, so a group
/// that spans segments (`org/example`, what `aether artifact info` sends for `org.example`) matched no
/// route at all. The group is now every segment before the last two, as on `ARTIFACT_GET`.
class MavenProtocolRoutesInfoTest {
    private static final TimeSpan SHORT_TIMEOUT = timeSpan(2).seconds();
    private static final String INFO = ManagementRoute.ARTIFACT_INFO.prefix();

    private final CopyOnWriteArrayList<String> resolved = new CopyOnWriteArrayList<>();

    @Test
    void handle_infoWithDottedGroup_resolvesTheWholeGroup() {
        var response = new CapturingResponseWriter();

        var handled = routes().handle(request(INFO + "/org/example/hello/1.0.0"), response);

        assertThat(handled).isTrue();
        assertThat(response.awaitStatus()).isEqualTo(HttpStatus.OK);
        assertThat(resolved).containsExactly("org.example:hello:1.0.0");
        assertThat(response.body()).contains("org.example:hello:1.0.0");
    }

    @Test
    void handle_infoWithDeeperGroup_resolvesTheWholeGroup() {
        var response = new CapturingResponseWriter();

        routes().handle(request(INFO + "/org/example/deep/hello/1.0.0"), response);

        assertThat(response.awaitStatus()).isEqualTo(HttpStatus.OK);
        assertThat(resolved).containsExactly("org.example.deep:hello:1.0.0");
    }

    @Test
    void handle_infoWithGroupAsOneDottedSegment_stillResolves() {
        var response = new CapturingResponseWriter();

        routes().handle(request(INFO + "/org.example/hello/1.0.0"), response);

        assertThat(response.awaitStatus()).isEqualTo(HttpStatus.OK);
        assertThat(resolved).containsExactly("org.example:hello:1.0.0");
    }

    /// A group needs at least two dot-separated parts, so `example` alone is refused, not guessed.
    @Test
    void handle_infoWithSingleWordGroup_isBadRequest() {
        var response = new CapturingResponseWriter();

        routes().handle(request(INFO + "/example/hello/1.0.0"), response);

        assertThat(response.awaitStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resolved).isEmpty();
    }

    @Test
    void handle_infoWithTooFewSegments_isBadRequest() {
        var response = new CapturingResponseWriter();

        var handled = routes().handle(request(INFO + "/hello/1.0.0"), response);

        assertThat(handled).isTrue();
        assertThat(response.awaitStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resolved).isEmpty();
    }

    private MavenProtocolRoutes routes() {
        return MavenProtocolRoutes.mavenProtocolRoutes(this::node, SHORT_TIMEOUT, () -> false, () -> true);
    }

    private ManageableNode node() {
        var store = (ArtifactStore) Proxy.newProxyInstance(ArtifactStore.class.getClassLoader(),
                                                           new Class[]{ArtifactStore.class},
                                                           (_, method, args) -> resolve(method.getName(), args));
        var metrics = (ArtifactMetricsCollector) Proxy.newProxyInstance(ArtifactMetricsCollector.class.getClassLoader(),
                                                                        new Class[]{ArtifactMetricsCollector.class},
                                                                        (_, method, _) -> false);

        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> switch (method.getName()) {
                                                           case "artifactStore" -> store;
                                                           case "artifactMetricsCollector" -> metrics;
                                                           default -> throw new UnsupportedOperationException(method.getName());
                                                       });
    }

    private Object resolve(String methodName, Object[] args) {
        if (!"resolveWithMetadata".equals(methodName)) {
            throw new UnsupportedOperationException(methodName);
        }

        resolved.add(((Artifact) args[0]).asString());

        return Promise.success(new ResolvedArtifact(new byte[0],
                                                    new ArtifactMetadata(42L, 1, "md5", "sha1", "sha256", 0L, List.of("blk"))));
    }

    private static HttpRequest request(String path) {
        return new HttpRequest() {
            @Override
            public String requestId() {
                return "req_test";
            }

            @Override
            public HttpMethod method() {
                return HttpMethod.GET;
            }

            @Override
            public String path() {
                return path;
            }

            @Override
            public Headers headers() {
                return Headers.empty();
            }

            @Override
            public QueryParams queryParams() {
                return QueryParams.empty();
            }

            @Override
            public byte[] body() {
                return new byte[0];
            }
        };
    }

    private static final class CapturingResponseWriter implements ResponseWriter {
        private final CountDownLatch written = new CountDownLatch(1);
        private volatile HttpStatus status;
        private volatile String body = "";

        String body() {
            return body;
        }

        HttpStatus awaitStatus() {
            try {
                written.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            return status;
        }

        @Override
        public void write(HttpStatus status, byte[] bodyBytes, ContentType contentType) {
            this.status = status;
            this.body = new String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8);
            written.countDown();
        }

        @Override
        public ResponseWriter header(String name, String value) {
            return this;
        }
    }
}
