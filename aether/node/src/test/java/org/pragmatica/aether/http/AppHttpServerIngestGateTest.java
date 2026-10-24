// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SecurityMode;
import org.pragmatica.aether.config.TimeoutsConfig.ForwardingTimeouts;
import org.pragmatica.aether.config.UploadConfig;
import org.pragmatica.aether.http.HttpRoutePublisher.LocalResolution;
import org.pragmatica.aether.http.HttpRoutePublisher.LocalRouteInfo;
import org.pragmatica.aether.http.adapter.RouteDecorator;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpRequestHandler;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.slice.ObservabilityCellRegistrar;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.blueprint.SecurityOverrides;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpNodeRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue.RouteEntry;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.aether.update.DeploymentManager;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.QueryParams;
import org.pragmatica.http.routing.SliceVersionRegistry;
import org.pragmatica.http.routing.UploadSpec;
import org.pragmatica.http.routing.VersioningMetricsSink;
import org.pragmatica.http.server.BodyBounds;
import org.pragmatica.http.server.Disposition;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.tcp.TlsConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// The ingest gate in the real app listener, over HTTP/1.1 (a raw socket) and HTTP/3 (a real QUIC client), through the
/// real pipeline and the node's own classification. Every behaviour runs on both protocols:
/// - auth before body: an anonymous 50 MiB upload with `Expect: 100-continue` to a protected route is answered 401, with
///   no `100`, and never reaches the slice; the same request with a key does;
/// - per-route limits: one 2 MiB body passes a 100 MiB route and is refused 413 on a 1 MiB route (declared length and, on
///   HTTP/1.1, chunked); an undeclared route takes `[app-http] max_request_size`; `max_size_ceiling` caps a larger
///   declaration;
/// - concurrency caps: a second upload past `max_concurrent_per_principal` or `max_concurrent` is answered 429 on its
///   HEADERS, and is admitted once the first body completes;
/// - upload bounds from config: `idle_timeout` and a route's `max_duration` answer 408.
@Timeout(180)
class AppHttpServerIngestGateTest {
    private static final int MIB = 1024 * 1024;
    private static final String KEY_A = "key-a";
    private static final String KEY_B = "key-b";
    private static final Artifact ARTIFACT = Artifact.artifact("com.example:svc:1.0.0").unwrap();

    enum Protocol {
        H1,
        H3
    }

    private final AtomicInteger served = new AtomicInteger();
    private final AtomicLong servedBytes = new AtomicLong();
    private final HttpRouteRegistry registry = HttpRouteRegistry.httpRouteRegistry();
    private AppHttpServer server;
    private int port;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop().await(timeSpan(30).seconds());
        }
    }

    private void start(Protocol protocol, UploadConfig upload, int maxRequestSize) throws IOException {
        start(protocol, upload, maxRequestSize, SecurityMode.API_KEY);
    }

    private void start(Protocol protocol, UploadConfig upload, int maxRequestSize, SecurityMode securityMode) throws IOException {
        port = TlsProbe.freeTcpPort();
        var config = AppHttpConfig.appHttpConfig(true,
                                                 port,
                                                 Map.of(KEY_A, org.pragmatica.aether.config.ApiKeyEntry.apiKeyEntry("alice", Set.of("service")),
                                                        KEY_B, org.pragmatica.aether.config.ApiKeyEntry.apiKeyEntry("bob", Set.of("service"))),
                                                 maxRequestSize,
                                                 securityMode,
                                                 Option.none(),
                                                 protocol == Protocol.H1
                                                 ? HttpProtocol.H1
                                                 : HttpProtocol.H3).unwrap().withUpload(upload);
        var router = new CountingRouter();
        var publisher = new IngestGateRoutes(router, SecurityPolicy.apiKeyRequired());

        server = AppHttpServer.appHttpServer(config,
                                             ForwardingTimeouts.forwardingTimeouts(),
                                             NodeId.nodeId("ingest-gate-node").unwrap(),
                                             registry,
                                             Option.some(publisher),
                                             Option.none(),
                                             Option.none(),
                                             Option.none(),
                                             protocol == Protocol.H3
                                             ? Option.some(TlsConfig.selfSignedServer())
                                             : Option.none(),
                                             Option.none(),
                                             Option.none(),
                                             Option.none(),
                                             Option.<DeploymentManager> none());
        assertThat(server.start().await(timeSpan(30).seconds()).isSuccess()).as("control: the server starts").isTrue();
        server.onQuorumStateChange(ClusterStateNotification.active());
    }

    private static UploadConfig defaults() {
        return UploadConfig.DEFAULT;
    }

    /// Auth before body: no credentials, and the body is never read; with a key, the same request is served.
    @Test
    void anonymousUpload_isAnswered401WithoutContinue_andNeverReachesTheSlice_overHttp1() throws Exception {
        anonymousUpload_isAnswered401WithoutContinue_andNeverReachesTheSlice(Protocol.H1);
    }

    @Test
    void anonymousUpload_isAnswered401WithoutContinue_andNeverReachesTheSlice_overHttp3() throws Exception {
        anonymousUpload_isAnswered401WithoutContinue_andNeverReachesTheSlice(Protocol.H3);
    }

    private void anonymousUpload_isAnswered401WithoutContinue_andNeverReachesTheSlice(Protocol protocol) throws Exception {
        start(protocol, defaults(), 10 * MIB);
        var client = client(protocol);

        var anonymous = client.post("/api/big/", Option.none(), 50 * MIB, Mode.EXPECT_CONTINUE_NOT_SENT);

        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.text()).doesNotContain("100 Continue");
        assertThat(served.get()).as("the refused request never reached the slice").isZero();

        var authenticated = client.post("/api/big/", Option.some(KEY_A), 4096, Mode.DECLARED);

        assertThat(authenticated.status()).as("control: the same route with a key is served").isEqualTo(200);
        assertThat(served.get()).isEqualTo(1);
    }

    /// A protected route on a listener with no validator configured (`security_mode = "none"`) is refused on its headers as
    /// well: the answer is the handler's own, and the body is not read.
    @Test
    void protectedRouteWithNoValidator_isRefusedOnTheHeaders_overHttp1() throws Exception {
        protectedRouteWithNoValidator_isRefusedOnTheHeaders(Protocol.H1);
    }

    @Test
    void protectedRouteWithNoValidator_isRefusedOnTheHeaders_overHttp3() throws Exception {
        protectedRouteWithNoValidator_isRefusedOnTheHeaders(Protocol.H3);
    }

    private void protectedRouteWithNoValidator_isRefusedOnTheHeaders(Protocol protocol) throws Exception {
        start(protocol, defaults(), 10 * MIB, SecurityMode.NONE);
        var client = client(protocol);
        var handlersAnswer = client.post("/api/big/", Option.some(KEY_A), 16, Mode.DECLARED).status();
        var outcome = client.postStreaming("/api/big/", Option.some(KEY_A), 50 * MIB);

        assertThat(handlersAnswer).as("control: a protected route with no validator is refused").isGreaterThanOrEqualTo(400);
        assertThat(outcome.status()).isEqualTo(handlersAnswer);
        assertThat(outcome.sent()).as("the body was not read: the client stopped short of 50 MiB").isLessThan(50L * MIB);
        assertThat(served.get()).isZero();
    }

    /// The refusal is not drained: an anonymous client streaming 50 MiB cannot finish sending it.
    @Test
    void anonymousUpload_isNotDrained_overHttp1() throws Exception {
        anonymousUpload_isNotDrained(Protocol.H1);
    }

    @Test
    void anonymousUpload_isNotDrained_overHttp3() throws Exception {
        anonymousUpload_isNotDrained(Protocol.H3);
    }

    private void anonymousUpload_isNotDrained(Protocol protocol) throws Exception {
        start(protocol, defaults(), 10 * MIB);

        var outcome = client(protocol).postStreaming("/api/big/", Option.none(), 50 * MIB);

        assertThat(outcome.status()).isEqualTo(401);
        assertThat(outcome.sent()).as("the server did not read the body: the client stopped short of 50 MiB")
                                  .isLessThan(50L * MIB);
        assertThat(served.get()).isZero();
    }

    /// One body, two routes: 100 MiB passes, 1 MiB refuses, by declared length and (HTTP/1.1) chunked.
    @Test
    void sameBody_passesTheBigRoute_andIsRefused413OnTheSmallRoute_overHttp1() throws Exception {
        sameBody_passesTheBigRoute_andIsRefused413OnTheSmallRoute(Protocol.H1);
    }

    @Test
    void sameBody_passesTheBigRoute_andIsRefused413OnTheSmallRoute_overHttp3() throws Exception {
        sameBody_passesTheBigRoute_andIsRefused413OnTheSmallRoute(Protocol.H3);
    }

    private void sameBody_passesTheBigRoute_andIsRefused413OnTheSmallRoute(Protocol protocol) throws Exception {
        start(protocol, defaults(), 10 * MIB);
        var client = client(protocol);
        var twoMiB = 2 * MIB;

        assertThat(client.post("/api/big/", Option.some(KEY_A), twoMiB, Mode.DECLARED).status()).isEqualTo(200);
        assertThat(servedBytes.get()).isEqualTo(twoMiB);
        assertThat(client.post("/api/small/", Option.some(KEY_A), twoMiB, Mode.DECLARED).status()).isEqualTo(413);
        assertThat(client.post("/api/small/", Option.some(KEY_A), twoMiB, Mode.UNDECLARED).status()).isEqualTo(413);
        assertThat(served.get()).as("only the big route's request reached the slice").isEqualTo(1);
    }

    /// An undeclared route takes `[app-http] max_request_size` (64 KiB here); a declared one is not held to it.
    @Test
    void undeclaredRoute_takesTheListenersMaxRequestSize_overHttp1() throws Exception {
        undeclaredRoute_takesTheListenersMaxRequestSize(Protocol.H1);
    }

    @Test
    void undeclaredRoute_takesTheListenersMaxRequestSize_overHttp3() throws Exception {
        undeclaredRoute_takesTheListenersMaxRequestSize(Protocol.H3);
    }

    private void undeclaredRoute_takesTheListenersMaxRequestSize(Protocol protocol) throws Exception {
        start(protocol, defaults(), 64 * 1024);
        var client = client(protocol);

        assertThat(client.post("/api/undeclared/", Option.some(KEY_A), 32 * 1024, Mode.DECLARED).status()).isEqualTo(200);
        assertThat(client.post("/api/undeclared/", Option.some(KEY_A), 128 * 1024, Mode.DECLARED).status()).isEqualTo(413);
        assertThat(client.post("/api/small/", Option.some(KEY_A), 512 * 1024, Mode.DECLARED).status()).as("declared 1 MiB beats 64 KiB")
                                                                                                           .isEqualTo(200);
    }

    /// The operator's ceiling caps what a slice declares: min(route, ceiling).
    @Test
    void ceiling_capsALargerDeclaration_overHttp1() throws Exception {
        ceiling_capsALargerDeclaration(Protocol.H1);
    }

    @Test
    void ceiling_capsALargerDeclaration_overHttp3() throws Exception {
        ceiling_capsALargerDeclaration(Protocol.H3);
    }

    private void ceiling_capsALargerDeclaration(Protocol protocol) throws Exception {
        start(protocol, defaults().withMaxSizeCeiling(MIB), 10 * MIB);
        var client = client(protocol);

        assertThat(client.post("/api/big/", Option.some(KEY_A), 512 * 1024, Mode.DECLARED).status()).isEqualTo(200);
        assertThat(client.post("/api/big/", Option.some(KEY_A), 2 * MIB, Mode.DECLARED).status()).as("the route declares 100 MiB, the ceiling is 1 MiB")
                                                                                                    .isEqualTo(413);
    }

    @Test
    void perPrincipalCap_refusesThePrincipalsSecondUpload_butNotAnotherPrincipals_overHttp1() throws Exception {
        perPrincipalCap_refusesThePrincipalsSecondUpload_butNotAnotherPrincipals(Protocol.H1);
    }

    @Test
    void perPrincipalCap_refusesThePrincipalsSecondUpload_butNotAnotherPrincipals_overHttp3() throws Exception {
        perPrincipalCap_refusesThePrincipalsSecondUpload_butNotAnotherPrincipals(Protocol.H3);
    }

    private void perPrincipalCap_refusesThePrincipalsSecondUpload_butNotAnotherPrincipals(Protocol protocol) throws Exception {
        start(protocol, defaults().withMaxConcurrentPerPrincipal(1), 10 * MIB);

        var run = client(protocol).holdOne("/api/upload/", KEY_A, List.of(KEY_A, KEY_B));

        assertThat(run.duringStatuses()).as("same principal refused, another principal admitted").containsExactly(429, 200);
        assertThat(run.afterReleaseStatus()).as("admitted once the first body completed").isEqualTo(200);
        if (protocol == Protocol.H1) {
            assertThat(run.firstRefusalText()).as("a 429 says when to retry").containsIgnoringCase("retry-after: 5");
        }
    }

    @Test
    void nodeCap_refusesAnyPrincipalsUploadPastIt_overHttp1() throws Exception {
        nodeCap_refusesAnyPrincipalsUploadPastIt(Protocol.H1);
    }

    @Test
    void nodeCap_refusesAnyPrincipalsUploadPastIt_overHttp3() throws Exception {
        nodeCap_refusesAnyPrincipalsUploadPastIt(Protocol.H3);
    }

    private void nodeCap_refusesAnyPrincipalsUploadPastIt(Protocol protocol) throws Exception {
        start(protocol, defaults().withMaxConcurrent(1), 10 * MIB);

        var run = client(protocol).holdOne("/api/upload/", KEY_A, List.of(KEY_B));

        assertThat(run.duringStatuses()).containsExactly(429);
        assertThat(run.afterReleaseStatus()).isEqualTo(200);
    }

    /// An ingress that does not host the route enforces the limits the serving node declared: the replicated entry carries
    /// them. A request within them gets past the gate (and meets the missing forwarder); one over them is refused 413 before
    /// anything is forwarded.
    @Test
    void remoteRoutesDeclaredLimit_isEnforcedAtTheIngress_overHttp1() throws Exception {
        remoteRoutesDeclaredLimit_isEnforcedAtTheIngress(Protocol.H1);
    }

    @Test
    void remoteRoutesDeclaredLimit_isEnforcedAtTheIngress_overHttp3() throws Exception {
        remoteRoutesDeclaredLimit_isEnforcedAtTheIngress(Protocol.H3);
    }

    private void remoteRoutesDeclaredLimit_isEnforcedAtTheIngress(Protocol protocol) throws Exception {
        start(protocol, defaults(), 10 * MIB);
        var node = NodeId.nodeId("serving-node").unwrap();
        var entry = RouteEntry.activeRoute("POST", "/remote/", "create", "UNSPECIFIED", "UNSPECIFIED", 0, List.of(), List.of(), false, MIB, 0);

        registry.onNodeRoutesPut(new ValuePut<>(new KVCommand.Put<>(NodeRoutesKey.nodeRoutesKey(node, ARTIFACT),
                                                                    NodeRoutesValue.nodeRoutesValue(List.of(entry))),
                                                Option.none()));
        server.rebuildRouter();
        var client = client(protocol);

        var within = client.post("/remote/", Option.some(KEY_A), 16 * 1024, Mode.DECLARED);
        var over = client.post("/remote/", Option.some(KEY_A), 2 * MIB, Mode.DECLARED);

        assertThat(within.status()).as("within the declared limit: past the gate, to the (absent) forwarder").isEqualTo(503);
        assertThat(within.text()).contains("forwarding not available");
        assertThat(over.status()).as("over the limit the serving node declared").isEqualTo(413);
    }

    /// `[app-http.upload] idle_timeout` reaches the upload route: a body that stalls for it is answered 408.
    @Test
    void uploadIdleTimeoutFromConfig_answers408_overHttp1() throws Exception {
        uploadIdleTimeoutFromConfig_answers408(Protocol.H1);
    }

    @Test
    void uploadIdleTimeoutFromConfig_answers408_overHttp3() throws Exception {
        uploadIdleTimeoutFromConfig_answers408(Protocol.H3);
    }

    private void uploadIdleTimeoutFromConfig_answers408(Protocol protocol) throws Exception {
        start(protocol, defaults().withIdleTimeout(timeSpan(1).seconds()), 10 * MIB);

        var answer = client(protocol).postStalled("/api/upload/", KEY_A);

        assertThat(answer.status()).isEqualTo(408);
        assertThat(served.get()).isZero();
    }

    /// A route's `max_duration` reaches the upload bounds: a steady sender is cut at it.
    @Test
    void routeMaxDuration_answers408_atASteadyRate_overHttp1() throws Exception {
        routeMaxDuration_answers408_atASteadyRate(Protocol.H1);
    }

    @Test
    void routeMaxDuration_answers408_atASteadyRate_overHttp3() throws Exception {
        routeMaxDuration_answers408_atASteadyRate(Protocol.H3);
    }

    private void routeMaxDuration_answers408_atASteadyRate(Protocol protocol) throws Exception {
        start(protocol, defaults(), 10 * MIB);

        var answer = client(protocol).postSteady("/api/timed/", KEY_A);

        assertThat(answer.status()).isEqualTo(408);
        assertThat(served.get()).isZero();
    }

    /// The bounds the classification hands the listener: `[app-http.upload]` idle timeout and minimum rate, the rate's
    /// grace and window, the route's own `max_duration`, and six hours for an upload route that declares none.
    @Test
    void classification_handsOverTheConfiguredBounds_theRoutesMaxDuration_andTheDefaultDuration() throws IOException {
        start(Protocol.H1, defaults().withIdleTimeout(timeSpan(7).seconds()).withMinBodyRate(4096), 10 * MIB);

        var timed = aggregate("/api/timed/x");
        var bounds = boundsOf(timed);

        assertThat(timed.maxBytes()).isEqualTo(100L * MIB);
        assertThat(bounds.idle().millis()).isEqualTo(7_000);
        assertThat(bounds.rate().bytesPerSecond()).isEqualTo(4096);
        assertThat(bounds.rate().grace().millis()).as("the rate is first checked after 30 s").isEqualTo(30_000);
        assertThat(bounds.rate().window().millis()).as("and over each following 30 s").isEqualTo(30_000);
        assertThat(bounds.maxDuration().millis()).isEqualTo(1_500);
        assertThat(boundsOf(aggregate("/api/upload/x")).maxDuration().millis()).as("no max_duration declared: six hours")
                                                                              .isEqualTo(6L * 60 * 60 * 1000);
    }

    private Disposition.Aggregate aggregate(String path) {
        var head = new Head("req-head", HttpMethod.POST, path, Headers.headers(Map.of("x-api-key", List.of(KEY_A))), QueryParams.queryParams(Map.of()));

        return switch (((AppHttpServerAdapter) server).classify(head)) {
            case Disposition.Aggregate aggregate -> aggregate;
            case Disposition.Reject _ -> throw new AssertionError("control: " + path + " with a valid key is admitted");
        };
    }

    private static BodyBounds boundsOf(Disposition.Aggregate aggregate) {
        return aggregate.bounds().fold(() -> {
                                           throw new AssertionError("an upload route has its own bounds");
                                       },
                                       bounds -> bounds);
    }

    private record Head(String requestId, HttpMethod method, String path, Headers headers, QueryParams queryParams) implements HttpRequest {
        @Override
        public byte[] body() {
            return new byte[0];
        }
    }

    // ---- the slice and its routes

    private enum Mode {
        /// Content-Length (HTTP/1.1) or content-length (HTTP/3) declared, body sent.
        DECLARED,
        /// HTTP/1.1: chunked. HTTP/3: no content-length.
        UNDECLARED,
        /// `Expect: 100-continue` with a declared length, and the body is never sent.
        EXPECT_CONTINUE_NOT_SENT
    }

    private record Reply(int status, String text) {}

    private record Streamed(int status, long sent) {}

    private record Held(List<Integer> duringStatuses, int afterReleaseStatus, String firstRefusalText) {}

    private interface Client {
        Reply post(String path, Option<String> key, int size, Mode mode) throws Exception;

        Streamed postStreaming(String path, Option<String> key, int size) throws Exception;

        Held holdOne(String path, String holderKey, List<String> others) throws Exception;

        Reply postStalled(String path, String key) throws Exception;

        Reply postSteady(String path, String key) throws Exception;
    }

    private Client client(Protocol protocol) {
        return protocol == Protocol.H1
               ? new H1Client()
               : new H3Client();
    }

    private final class CountingRouter implements SliceRouter {
        @Override
        public Promise<HttpResponseData> handle(HttpRequestContext request) {
            served.incrementAndGet();
            servedBytes.set(request.body().length);

            return Promise.success(HttpResponseData.httpResponseData(200, "served"));
        }

        @Override
        public SliceVersionRegistry versionRegistry() {
            return SliceVersionRegistry.UNVERSIONED;
        }

        @Override
        public SliceRouter withObservability(String sliceName, VersioningMetricsSink sink) {
            return this;
        }

        @Override
        public SliceRouter withInvocationCells(RouteDecorator decorator) {
            return this;
        }
    }

    // ---- HTTP/1.1 client: a raw socket

    private final class H1Client implements Client {
        @Override
        public Reply post(String path, Option<String> key, int size, Mode mode) throws Exception {
            try (var socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(15_000);
                var chunked = mode == Mode.UNDECLARED;
                var head = head(path, key, chunked
                                           ? "Transfer-Encoding: chunked"
                                           : "Content-Length: " + size,
                                mode == Mode.EXPECT_CONTINUE_NOT_SENT
                                ? "Expect: 100-continue\r\n"
                                : "");
                var writer = new Thread(() -> writeBody(socket, head, mode == Mode.EXPECT_CONTINUE_NOT_SENT
                                                                      ? 0
                                                                      : size, chunked));

                writer.start();
                var text = readAll(socket);

                writer.join(10_000);

                return new Reply(status(text), text);
            }
        }

        @Override
        public Streamed postStreaming(String path, Option<String> key, int size) throws Exception {
            try (var socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(15_000);
                var text = new StringBuilder();
                var reader = new Thread(() -> text.append(readAll(socket)));
                var sent = 0L;

                send(socket, head(path, key, "Content-Length: " + size, ""));
                reader.start();
                try {
                    var chunk = new byte[64 * 1024];

                    while (sent < size) {
                        socket.getOutputStream().write(chunk);
                        sent += chunk.length;
                    }
                } catch (IOException e) {
                    // the server closed on us: what was sent so far is the measure
                }

                reader.join(10_000);

                return new Streamed(status(text.toString()), sent);
            }
        }

        @Override
        public Held holdOne(String path, String holderKey, List<String> others) throws Exception {
            var during = new ArrayList<Integer>();
            var refusal = new StringBuilder();

            try (var holder = new Socket("127.0.0.1", port)) {
                send(holder, head(path, Option.some(holderKey), "Content-Length: 10", "") + "01234");
                Thread.sleep(300);
                for (var other : others) {
                    var reply = post(path, Option.some(other), 16, Mode.DECLARED);

                    during.add(reply.status());
                    if (reply.status() == 429 && refusal.isEmpty()) {
                        refusal.append(reply.text());
                    }
                }

                send(holder, "56789");
            }

            return new Held(during, admittedAfterRelease(this, path, holderKey), refusal.toString());
        }

        @Override
        public Reply postStalled(String path, String key) throws Exception {
            try (var socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(15_000);
                send(socket, head(path, Option.some(key), "Content-Length: 1000", "") + "0123456789");
                var text = readAll(socket);

                return new Reply(status(text), text);
            }
        }

        @Override
        public Reply postSteady(String path, String key) throws Exception {
            try (var socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(15_000);
                var text = new StringBuilder();
                var reader = new Thread(() -> text.append(readAll(socket)));

                send(socket, head(path, Option.some(key), "Content-Length: 100000", ""));
                reader.start();
                try {
                    for (int index = 0; index < 100 && reader.isAlive(); index++) {
                        socket.getOutputStream().write(new byte[2048]);
                        Thread.sleep(100);
                    }
                } catch (IOException e) {
                    // refused and closed
                }

                reader.join(10_000);

                return new Reply(status(text.toString()), text.toString());
            }
        }

        private String head(String path, Option<String> key, String lengthHeader, String extra) {
            return "POST " + path + "x HTTP/1.1\r\nHost: x\r\n" + key.map(value -> "X-API-Key: " + value + "\r\n").or("")
                   + lengthHeader + "\r\n" + extra + "\r\n";
        }

        private void writeBody(Socket socket, String head, int size, boolean chunked) {
            try {
                var out = socket.getOutputStream();
                var chunk = new byte[16 * 1024];

                out.write(head.getBytes(StandardCharsets.ISO_8859_1));
                for (int sent = 0; sent < size; sent += chunk.length) {
                    var length = Math.min(chunk.length, size - sent);

                    if (chunked) {
                        out.write((Integer.toHexString(length) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    }

                    out.write(chunk, 0, length);
                    if (chunked) {
                        out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    }
                }

                if (chunked) {
                    out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                }

                out.flush();
            } catch (IOException e) {
                // refused: expected for the refused requests
            }
        }

        private void send(Socket socket, String text) throws IOException {
            socket.getOutputStream().write(text.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
        }

        private String readAll(Socket socket) {
            var out = new ByteArrayOutputStream();
            var buffer = new byte[4096];

            try {
                int read;

                while ((read = socket.getInputStream().read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            } catch (IOException e) {
                // reset after the answer: what arrived is what counts
            }

            return out.toString(StandardCharsets.ISO_8859_1);
        }

        private int status(String text) {
            var parts = text.lines().findFirst().orElse("").split(" ");

            return parts.length > 1
                   ? Integer.parseInt(parts[1])
                   : -1;
        }
    }

    // ---- HTTP/3 client: a real QUIC client

    private final class H3Client implements Client {
        @Override
        public Reply post(String path, Option<String> key, int size, Mode mode) throws Exception {
            var answer = H3Probe.exchange(port, stream -> {
                stream.write(headers(path, key, mode == Mode.UNDECLARED
                                                ? -1
                                                : size));
                if (mode != Mode.EXPECT_CONTINUE_NOT_SENT) {
                    sendBody(stream, size, true);
                }
            });

            return new Reply(Integer.parseInt(answer.status()), answer.body());
        }

        @Override
        public Streamed postStreaming(String path, Option<String> key, int size) throws Exception {
            var sent = new AtomicLong();
            var answer = H3Probe.exchange(port, stream -> {
                stream.write(headers(path, key, size));
                sent.set(sendBody(stream, size, false));
            });

            return new Streamed(Integer.parseInt(answer.status()), sent.get());
        }

        @Override
        public Held holdOne(String path, String holderKey, List<String> others) throws Exception {
            var during = new ArrayList<Integer>();
            var after = new AtomicInteger();

            H3Probe.exchange(port, stream -> {
                stream.write(headers(path, Option.some(holderKey), 10));
                stream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[5]))).sync();
                Thread.sleep(300);
                for (var other : others) {
                    during.add(Integer.parseInt(H3Probe.exchange(port, second -> {
                        second.write(headers(path, Option.some(other), 16));
                        sendBody(second, 16, true);
                    }).status()));
                }

                stream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[5])))
                      .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
            });

            return new Held(during, admittedAfterRelease(this, path, holderKey), "");
        }

        @Override
        public Reply postStalled(String path, String key) throws Exception {
            var answer = H3Probe.exchange(port, stream -> {
                stream.write(headers(path, Option.some(key), 1000));
                stream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[10])));
            });

            return new Reply(Integer.parseInt(answer.status()), answer.body());
        }

        @Override
        public Reply postSteady(String path, String key) throws Exception {
            var answer = H3Probe.exchange(port, stream -> {
                stream.write(headers(path, Option.some(key), 100_000));
                for (int index = 0; index < 100 && stream.isActive(); index++) {
                    stream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[2048])));
                    Thread.sleep(100);
                }
            });

            return new Reply(Integer.parseInt(answer.status()), answer.body());
        }

        private io.netty.handler.codec.http3.DefaultHttp3HeadersFrame headers(String path, Option<String> key, long length) {
            var frame = H3Probe.requestHeaders(port, "POST");

            frame.headers().path(path + "x");
            key.onPresent(value -> frame.headers().add("x-api-key", value));
            if (length >= 0) {
                frame.headers().add("content-length", String.valueOf(length));
            }

            return frame;
        }

        /// Sends `size` bytes in 64 KiB frames; when `end`, ends the stream. Stops at the first write the server's window
        /// or a reset refuses, and returns the bytes whose write completed.
        private long sendBody(QuicStreamChannel stream, int size, boolean end) throws InterruptedException {
            var sent = 0L;

            while (sent < size && stream.isActive()) {
                var frame = (int) Math.min(64 * 1024, size - sent);
                var written = stream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[frame])));

                if (!written.await(2_000) || !written.isSuccess()) {
                    return sent;
                }

                sent += frame;
            }

            if (end && stream.isActive()) {
                stream.shutdownOutput();
            }

            return sent;
        }
    }

    /// Polls a small upload by `key` until it is no longer refused 429 (the permit is returned on the event loop, so it is
    /// not instant); returns the status that ended the polling.
    private static int admittedAfterRelease(Client client, String path, String key) throws Exception {
        var status = 429;

        for (int attempt = 0; attempt < 50 && status == 429; attempt++) {
            status = client.post(path, Option.some(key), 16, Mode.DECLARED).status();
            if (status == 429) {
                Thread.sleep(100);
            }
        }

        return status;
    }
}
