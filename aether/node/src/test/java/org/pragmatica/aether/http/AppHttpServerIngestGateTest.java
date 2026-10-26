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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.api.ClusterEvent;
import org.pragmatica.aether.api.ClusterEventAggregator;
import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.stream.FrameworkStreamConsumer;
import org.pragmatica.aether.slice.stream.FrameworkStreamPublisher;
import org.pragmatica.aether.slice.stream.SystemStreams;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.SystemStreamFactories;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
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
    private static final Pattern CONTENT_LENGTH = Pattern.compile("(?im)^content-length:\\s*(\\d+)");
    private static final String KEY_A = "key-a";
    private static final String KEY_B = "key-b";
    private static final Artifact ARTIFACT = Artifact.artifact("com.example:svc:1.0.0").unwrap();

    enum Protocol {
        H1,
        H3
    }

    private final AtomicInteger served = new AtomicInteger();
    private final AtomicLong servedBytes = new AtomicLong();
    private final AtomicReference<String> dispatchThread = new AtomicReference<>("");
    private final AtomicInteger entered = new AtomicInteger();
    private volatile CountDownLatch gate = new CountDownLatch(0);
    private volatile boolean failDispatch;
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
            assertThat(run.firstRefusalText()).as("and which cap refused it").contains("Too many concurrent uploads for this principal");
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

    /// The role check is part of the classification: an authenticated principal without the role is refused on the
    /// headers, and the same principal on a route with no role requirement is admitted.
    @Test
    void classification_refusesAPrincipalWithoutTheRole_onTheHeaders() throws IOException {
        start(Protocol.H1, defaults(), 10 * MIB);

        assertThat(classify(HttpMethod.POST, "/api/admin/x", Option.some(KEY_A))).as("alice has no admin role").isInstanceOf(Disposition.Reject.class);
        assertThat(classify(HttpMethod.POST, "/api/undeclared/x", Option.some(KEY_A))).as("control: the same key is admitted elsewhere")
                                                                                       .isInstanceOf(Disposition.Aggregate.class);
    }

    /// The health endpoint is answered by the listener itself: it takes the listener's request size and none of the
    /// upload limits.
    @Test
    void classification_ofTheHealthEndpoint_ignoresTheUploadCeilingAndNeedsNoKey() throws IOException {
        start(Protocol.H1, defaults().withMaxSizeCeiling(MIB), 10 * MIB);

        var health = classify(HttpMethod.GET, "/health", Option.none());

        assertThat(health).isInstanceOf(Disposition.Aggregate.class);
        assertThat(((Disposition.Aggregate) health).maxBytes()).isEqualTo(10L * MIB);
    }

    private Disposition classify(HttpMethod method, String path, Option<String> key) {
        var headers = key.map(value -> Map.of("x-api-key", List.of(value))).or(Map.<String, List<String>>of());
        var head = new Head("req-head", method, path, Headers.headers(headers), QueryParams.queryParams(Map.of()));

        return ((AppHttpServerAdapter) server).classify(head);
    }

    private Disposition.Aggregate aggregate(String path) {
        return switch (classify(HttpMethod.POST, path, Option.some(KEY_A))) {
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

        /// Posts `body` with the given Content-Type, declared length.
        Reply postBody(String path, String key, String contentType, byte[] body) throws Exception;

        /// Sends only the head of a POST declaring `length` bytes, and reads the answer.
        Reply postDeclaredOnly(String path, String key, long length) throws Exception;
    }

    private Client client(Protocol protocol) {
        return protocol == Protocol.H1
               ? new H1Client()
               : new H3Client();
    }

    private void awaitGate() {
        try {
            assertThat(gate.await(30, TimeUnit.SECONDS)).as("the gate was opened").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private final class CountingRouter implements SliceRouter {
        @Override
        public Promise<HttpResponseData> handle(HttpRequestContext request) {
            dispatchThread.set(Thread.currentThread().getName());
            entered.incrementAndGet();
            awaitGate();
            if (failDispatch) {
                throw new IllegalStateException("dispatch failed");
            }
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
                var text = readResponse(socket);

                writer.join(10_000);

                return new Reply(status(text), text);
            }
        }

        @Override
        public Streamed postStreaming(String path, Option<String> key, int size) throws Exception {
            try (var socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(15_000);
                var text = new StringBuilder();
                var reader = new Thread(() -> text.append(readResponse(socket)));
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
                    if ((reply.status() == 429 || reply.status() == 503) && refusal.isEmpty()) {
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
                var text = readResponse(socket);

                return new Reply(status(text), text);
            }
        }

        @Override
        public Reply postSteady(String path, String key) throws Exception {
            try (var socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(15_000);
                var text = new StringBuilder();
                var reader = new Thread(() -> text.append(readResponse(socket)));

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

        @Override
        public Reply postBody(String path, String key, String contentType, byte[] body) throws Exception {
            try (var socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(15_000);
                var head = head(path, Option.some(key), "Content-Length: " + body.length, "Content-Type: " + contentType + "\r\n");

                send(socket, head);
                socket.getOutputStream().write(body);
                socket.getOutputStream().flush();
                var text = readResponse(socket);

                return new Reply(status(text), text);
            }
        }

        @Override
        public Reply postDeclaredOnly(String path, String key, long length) throws Exception {
            try (var socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(15_000);
                send(socket, head(path, Option.some(key), "Content-Length: " + length, ""));
                var text = readResponse(socket);

                return new Reply(status(text), text);
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

        /// Reads until the final response is complete, framed by its `Content-Length` (an interim `100` is read past), or the
        /// connection ends. The listener keeps an HTTP/1.1 connection open after an answer, so waiting for end-of-stream
        /// would wait out the socket timeout on every answer that is not a close.
        private String readResponse(Socket socket) {
            var out = new ByteArrayOutputStream();
            var buffer = new byte[4096];

            try {
                int read;

                while ((read = socket.getInputStream().read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    if (finalResponseComplete(out.toString(StandardCharsets.ISO_8859_1))) {
                        break;
                    }
                }
            } catch (IOException e) {
                // reset after the answer: what arrived is what counts
            }

            return out.toString(StandardCharsets.ISO_8859_1);
        }

        private boolean finalResponseComplete(String text) {
            var position = 0;

            while (true) {
                var headEnd = text.indexOf("\r\n\r\n", position);

                if (headEnd < 0) {
                    return false;
                }

                var head = text.substring(position, headEnd);

                if (status(head) / 100 == 1) {
                    position = headEnd + 4;
                    continue;
                }

                var length = CONTENT_LENGTH.matcher(head);

                return length.find() && text.length() >= headEnd + 4 + Integer.parseInt(length.group(1));
            }
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
                for (int index = 0; index < 60 && stream.isActive(); index++) {
                    stream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(new byte[2048])));
                    Thread.sleep(100);
                }
            });

            return new Reply(Integer.parseInt(answer.status()), answer.body());
        }

        @Override
        public Reply postBody(String path, String key, String contentType, byte[] body) throws Exception {
            var answer = H3Probe.exchange(port, stream -> {
                var frame = headers(path, Option.some(key), body.length);

                frame.headers().add("content-type", contentType);
                stream.write(frame);
                stream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(body))).sync();
                stream.shutdownOutput();
            });

            return new Reply(Integer.parseInt(answer.status()), answer.body());
        }

        @Override
        public Reply postDeclaredOnly(String path, String key, long length) throws Exception {
            var answer = H3Probe.exchange(port, stream -> stream.writeAndFlush(headers(path, Option.some(key), length)).sync());

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

    // ---- multipart part limits: checked on the decoded body, 413 on a breach

    private static final String BOUNDARY = "gate-boundary";
    private static final String MULTIPART = "multipart/form-data; boundary=" + BOUNDARY;

    private record Part(String name, String filename, byte[] content) {
        static Part file(String name, int bytes) {
            return new Part(name, name + ".bin", new byte[bytes]);
        }

        static Part field(String name, String value) {
            return new Part(name, null, value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static byte[] multipart(Part... parts) {
        var out = new ByteArrayOutputStream();

        for (var part : parts) {
            var disposition = "Content-Disposition: form-data; name=\"" + part.name() + "\""
                              + (part.filename() == null
                                 ? ""
                                 : "; filename=\"" + part.filename() + "\"");
            var contentType = part.filename() == null
                              ? ""
                              : "Content-Type: application/octet-stream\r\n";

            out.writeBytes(("--" + BOUNDARY + "\r\n" + disposition + "\r\n" + contentType + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.writeBytes(part.content());
            out.writeBytes("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        }

        out.writeBytes(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.ISO_8859_1));

        return out.toByteArray();
    }

    private int statusOf(Protocol protocol, String path, byte[] body) throws Exception {
        return client(protocol).postBody(path, KEY_A, MULTIPART, body).status();
    }

    @Test
    void maxFiles_allowsTheLimitAndRefuses413OneMore_overHttp1() throws Exception {
        maxFiles_allowsTheLimitAndRefuses413OneMore(Protocol.H1);
    }

    @Test
    void maxFiles_allowsTheLimitAndRefuses413OneMore_overHttp3() throws Exception {
        maxFiles_allowsTheLimitAndRefuses413OneMore(Protocol.H3);
    }

    private void maxFiles_allowsTheLimitAndRefuses413OneMore(Protocol protocol) throws Exception {
        start(protocol, defaults(), 10 * MIB);

        assertThat(statusOf(protocol, "/api/files/", multipart(Part.file("a", 10), Part.file("b", 10)))).as("two files: at the limit").isEqualTo(200);
        assertThat(served.get()).isEqualTo(1);
        assertThat(statusOf(protocol, "/api/files/", multipart(Part.file("a", 10), Part.file("b", 10), Part.file("c", 10)))).as("three files").isEqualTo(413);
        assertThat(served.get()).as("the refused request never reached the slice").isEqualTo(1);
    }

    @Test
    void maxFileSize_allowsTheLimitAndRefuses413OneByteMore_overHttp1() throws Exception {
        maxFileSize_allowsTheLimitAndRefuses413OneByteMore(Protocol.H1);
    }

    @Test
    void maxFileSize_allowsTheLimitAndRefuses413OneByteMore_overHttp3() throws Exception {
        maxFileSize_allowsTheLimitAndRefuses413OneByteMore(Protocol.H3);
    }

    private void maxFileSize_allowsTheLimitAndRefuses413OneByteMore(Protocol protocol) throws Exception {
        start(protocol, defaults(), 10 * MIB);

        assertThat(statusOf(protocol, "/api/filesize/", multipart(Part.file("a", 100)))).as("100 bytes: at the limit").isEqualTo(200);
        assertThat(statusOf(protocol, "/api/filesize/", multipart(Part.file("a", 5), Part.file("b", 101)))).as("one file of 101 bytes").isEqualTo(413);
        assertThat(served.get()).isEqualTo(1);
    }

    @Test
    void maxFields_allowsTheLimitAndRefuses413OneMore_overHttp1() throws Exception {
        maxFields_allowsTheLimitAndRefuses413OneMore(Protocol.H1);
    }

    @Test
    void maxFields_allowsTheLimitAndRefuses413OneMore_overHttp3() throws Exception {
        maxFields_allowsTheLimitAndRefuses413OneMore(Protocol.H3);
    }

    private void maxFields_allowsTheLimitAndRefuses413OneMore(Protocol protocol) throws Exception {
        start(protocol, defaults(), 10 * MIB);

        assertThat(statusOf(protocol, "/api/fields/", multipart(Part.field("a", "1"), Part.field("b", "2")))).as("two fields: at the limit").isEqualTo(200);
        assertThat(statusOf(protocol, "/api/fields/", multipart(Part.field("a", "1"), Part.field("b", "2"), Part.field("c", "3")))).isEqualTo(413);
        assertThat(statusOf(protocol, "/api/fields/", multipart(Part.field("a", "1"), Part.field("a", "2"), Part.field("a", "3"))))
            .as("one name three times is three fields").isEqualTo(413);
        assertThat(served.get()).isEqualTo(1);
    }

    @Test
    void maxFieldSize_allowsTheLimitAndRefuses413OneByteMore_overHttp1() throws Exception {
        maxFieldSize_allowsTheLimitAndRefuses413OneByteMore(Protocol.H1);
    }

    @Test
    void maxFieldSize_allowsTheLimitAndRefuses413OneByteMore_overHttp3() throws Exception {
        maxFieldSize_allowsTheLimitAndRefuses413OneByteMore(Protocol.H3);
    }

    private void maxFieldSize_allowsTheLimitAndRefuses413OneByteMore(Protocol protocol) throws Exception {
        start(protocol, defaults(), 10 * MIB);

        assertThat(statusOf(protocol, "/api/fieldsize/", multipart(Part.field("a", "0123456789")))).as("10 bytes: at the limit").isEqualTo(200);
        assertThat(statusOf(protocol, "/api/fieldsize/", multipart(Part.field("a", "0123456789x")))).as("11 bytes").isEqualTo(413);
        assertThat(served.get()).isEqualTo(1);
    }

    /// The limit a route declares is the only one enforced on it: a body that would break another route's limit passes.
    @Test
    void eachRoutesPartLimitIsItsOwn_overHttp1() throws Exception {
        eachRoutesPartLimitIsItsOwn(Protocol.H1);
    }

    @Test
    void eachRoutesPartLimitIsItsOwn_overHttp3() throws Exception {
        eachRoutesPartLimitIsItsOwn(Protocol.H3);
    }

    private void eachRoutesPartLimitIsItsOwn(Protocol protocol) throws Exception {
        start(protocol, defaults(), 10 * MIB);
        assertThat(statusOf(protocol, "/api/fields/", multipart(Part.file("a", 500), Part.file("b", 500), Part.file("c", 500)))).as("fields route: files are not its business").isEqualTo(200);
        assertThat(statusOf(protocol, "/api/files/", multipart(Part.field("a", "1"), Part.field("b", "2"), Part.field("c", "3")))).as("files route: fields are not its business").isEqualTo(200);
        assertThat(statusOf(protocol, "/api/fieldsize/", multipart(Part.file("a", 500), Part.file("b", 500)))).as("field-size route: file sizes are not its business").isEqualTo(200);
        assertThat(statusOf(protocol, "/api/filesize/", multipart(Part.field("a", "x".repeat(500))))).as("file-size route: field sizes are not its business").isEqualTo(200);
    }

    /// A route may declare more files than the decoder's default part cap (128): the cap follows the declaration, so 150
    /// files on a `max_files = 200` route are within the limit, and 250 are over it.
    @Test
    void aRouteMayDeclareMoreFilesThanTheDecodersDefaultCap_overHttp1() throws Exception {
        aRouteMayDeclareMoreFilesThanTheDecodersDefaultCap(Protocol.H1);
    }

    @Test
    void aRouteMayDeclareMoreFilesThanTheDecodersDefaultCap_overHttp3() throws Exception {
        aRouteMayDeclareMoreFilesThanTheDecodersDefaultCap(Protocol.H3);
    }

    private void aRouteMayDeclareMoreFilesThanTheDecodersDefaultCap(Protocol protocol) throws Exception {
        start(protocol, defaults(), 10 * MIB);

        assertThat(statusOf(protocol, "/api/manyfiles/", multipart(filesOf(150)))).as("150 files, limit 200").isEqualTo(200);
        assertThat(statusOf(protocol, "/api/manyfiles/", multipart(filesOf(250)))).as("250 files, limit 200").isEqualTo(413);
        assertThat(served.get()).isEqualTo(1);
    }

    private static Part[] filesOf(int count) {
        var parts = new Part[count];

        for (int index = 0; index < count; index++) {
            parts[index] = Part.file("f" + index, 4);
        }

        return parts;
    }

    /// More parts than the decoder's default cap (128) on a route that declares part limits is a breach of them, not a
    /// malformed body: 413.
    @Test
    void aBodyWithMoreThanTheDecodersPartCap_onAPartLimitedRoute_isRefused413_overHttp1() throws Exception {
        aBodyWithMoreThanTheDecodersPartCap_onAPartLimitedRoute_isRefused413(Protocol.H1);
    }

    @Test
    void aBodyWithMoreThanTheDecodersPartCap_onAPartLimitedRoute_isRefused413_overHttp3() throws Exception {
        aBodyWithMoreThanTheDecodersPartCap_onAPartLimitedRoute_isRefused413(Protocol.H3);
    }

    private void aBodyWithMoreThanTheDecodersPartCap_onAPartLimitedRoute_isRefused413(Protocol protocol) throws Exception {
        start(protocol, defaults(), 10 * MIB);
        var parts = new Part[200];

        for (int index = 0; index < parts.length; index++) {
            parts[index] = Part.field("f" + index, "v");
        }

        assertThat(statusOf(protocol, "/api/files/", multipart(parts))).isEqualTo(413);
        assertThat(served.get()).isZero();
    }

    /// Only a multipart body is decoded for its parts: another Content-Type on a part-limited route, and a multipart body
    /// that does not decode, are left to the handler as before.
    @Test
    void aBodyThatIsNotDecodableMultipart_isLeftToTheHandler_overHttp1() throws Exception {
        aBodyThatIsNotDecodableMultipart_isLeftToTheHandler(Protocol.H1);
    }

    @Test
    void aBodyThatIsNotDecodableMultipart_isLeftToTheHandler_overHttp3() throws Exception {
        aBodyThatIsNotDecodableMultipart_isLeftToTheHandler(Protocol.H3);
    }

    private void aBodyThatIsNotDecodableMultipart_isLeftToTheHandler(Protocol protocol) throws Exception {
        start(protocol, defaults(), 10 * MIB);
        var client = client(protocol);

        assertThat(client.postBody("/api/files/", KEY_A, "application/octet-stream", new byte[4096]).status()).as("not multipart: not decoded").isEqualTo(200);
        assertThat(client.postBody("/api/files/", KEY_A, MULTIPART, "not a multipart body".getBytes(StandardCharsets.UTF_8)).status())
            .as("multipart that does not decode: the handler's to answer").isEqualTo(200);
        assertThat(served.get()).isEqualTo(2);
    }

    /// The ingress that does not host the route enforces the part limits the serving node declared.
    @Test
    void remoteRoutesPartLimits_areEnforcedAtTheIngress_overHttp1() throws Exception {
        remoteRoutesPartLimits_areEnforcedAtTheIngress(Protocol.H1);
    }

    @Test
    void remoteRoutesPartLimits_areEnforcedAtTheIngress_overHttp3() throws Exception {
        remoteRoutesPartLimits_areEnforcedAtTheIngress(Protocol.H3);
    }

    private void remoteRoutesPartLimits_areEnforcedAtTheIngress(Protocol protocol) throws Exception {
        start(protocol, defaults(), 10 * MIB);
        var node = NodeId.nodeId("serving-node").unwrap();
        var entry = RouteEntry.activeRoute("POST", "/remote/", "create", "UNSPECIFIED", "UNSPECIFIED", 0, List.of(), List.of(), true, 10L * MIB, 0, 1, 0, 0, 0);

        registry.onNodeRoutesPut(new ValuePut<>(new KVCommand.Put<>(NodeRoutesKey.nodeRoutesKey(node, ARTIFACT),
                                                                    NodeRoutesValue.nodeRoutesValue(List.of(entry))),
                                                Option.none()));
        server.rebuildRouter();

        assertThat(statusOf(protocol, "/remote/", multipart(Part.file("a", 10)))).as("one file: past the gate, to the absent forwarder").isEqualTo(503);
        assertThat(statusOf(protocol, "/remote/", multipart(Part.file("a", 10), Part.file("b", 10)))).as("two files, the serving node allows one").isEqualTo(413);
    }

    // ---- routes without upload limits behave exactly as before

    /// A route that declares nothing is held to the listener's own size, takes no upload permit, has no upload bounds, and
    /// has its multipart bodies left alone, even while the node's upload cap is exhausted and another route's part limits are
    /// in force.
    @Test
    void routeWithoutUploadLimits_behavesAsBefore_classification() throws IOException {
        start(Protocol.H1, defaults().withMaxConcurrent(1), 64 * 1024);

        var permit = aggregate("/api/upload/held");
        var plain = aggregate("/api/undeclared/x");

        assertThat(classify(HttpMethod.POST, "/api/upload/second", Option.some(KEY_B))).as("control: the node cap is exhausted for upload routes")
                                                                                       .isInstanceOf(Disposition.Reject.class);
        assertThat(plain.maxBytes()).as("the listener's own max_request_size").isEqualTo(64 * 1024);
        assertThat(plain.bounds()).as("the listener's general bounds, not the upload ones").isEqualTo(Option.empty());
        assertThat(classify(HttpMethod.POST, "/api/undeclared/y", Option.some(KEY_B))).as("no permit taken, so none to refuse").isInstanceOf(Disposition.Aggregate.class);
        assertThat(permit.release()).isNotNull();
    }

    @Test
    void routeWithoutUploadLimits_behavesAsBefore_overHttp1() throws Exception {
        routeWithoutUploadLimits_behavesAsBefore(Protocol.H1);
    }

    @Test
    void routeWithoutUploadLimits_behavesAsBefore_overHttp3() throws Exception {
        routeWithoutUploadLimits_behavesAsBefore(Protocol.H3);
    }

    private void routeWithoutUploadLimits_behavesAsBefore(Protocol protocol) throws Exception {
        start(protocol, defaults(), 256 * 1024);
        var many = new Part[] {Part.file("a", 4000), Part.file("b", 4000), Part.file("c", 4000), Part.file("d", 4000), Part.field("x", "x".repeat(5000)),
                               Part.field("y", "1"), Part.field("z", "2")};
        var reply = client(protocol).postBody("/api/undeclared/", KEY_A, MULTIPART, multipart(many));

        assertThat(reply.status()).as("any number and size of parts").isEqualTo(200);
        assertThat(reply.text()).contains("served");
        var overTheDecodersCap = new Part[200];

        for (int index = 0; index < overTheDecodersCap.length; index++) {
            overTheDecodersCap[index] = Part.field("f" + index, "v");
        }

        assertThat(client(protocol).postBody("/api/undeclared/", KEY_A, MULTIPART, multipart(overTheDecodersCap)).status())
            .as("200 parts, more than the decoder's cap: not this gate's to refuse, the handler answers it").isEqualTo(200);
        assertThat(client(protocol).postBody("/api/upload/", KEY_A, MULTIPART, multipart(overTheDecodersCap)).status())
            .as("an upload route that declares no part limits is the same").isEqualTo(200);
        assertThat(client(protocol).post("/api/undeclared/", Option.some(KEY_A), 512 * 1024, Mode.DECLARED).status()).as("over max_request_size: 413 as before").isEqualTo(413);
        assertThat(client(protocol).post("/api/undeclared/", Option.none(), 16, Mode.DECLARED).status()).as("authentication as before").isEqualTo(401);
        assertThat(served.get()).as("the three admitted requests were served, the refused two were not").isEqualTo(3);
    }

    // ---- F1: the part-limit check leaves the event loop; F2/F3: the memory model's bounds

    private static boolean onPartLimitWorker(String thread) {
        return thread.startsWith("app-http-part-limit-");
    }

    private void partLimitedMultipart_isCheckedAndDispatchedOffTheEventLoop(Protocol protocol) throws Exception {
        start(protocol, defaults(), 10 * MIB);

        assertThat(statusOf(protocol, "/api/files/", multipart(Part.file("a", 10), Part.file("b", 10)))).isEqualTo(200);
        assertThat(onPartLimitWorker(dispatchThread.get())).as("a part-limited multipart body is checked on the worker, dispatched from it: " + dispatchThread.get()).isTrue();
        assertThat(statusOf(protocol, "/api/upload/", multipart(Part.file("a", 10), Part.file("b", 10)))).isEqualTo(200);
        assertThat(onPartLimitWorker(dispatchThread.get())).as("an upload route without part limits is dispatched where it arrived: " + dispatchThread.get()).isFalse();
        assertThat(client(protocol).postBody("/api/files/", KEY_A, "application/octet-stream", new byte[100]).status()).isEqualTo(200);
        assertThat(onPartLimitWorker(dispatchThread.get())).as("a part-limited route's non-multipart body has nothing to scan: " + dispatchThread.get()).isFalse();
    }

    @Test
    void partLimitedMultipart_isCheckedAndDispatchedOffTheEventLoop_overHttp1() throws Exception {
        partLimitedMultipart_isCheckedAndDispatchedOffTheEventLoop(Protocol.H1);
    }

    @Test
    void partLimitedMultipart_isCheckedAndDispatchedOffTheEventLoop_overHttp3() throws Exception {
        partLimitedMultipart_isCheckedAndDispatchedOffTheEventLoop(Protocol.H3);
    }

    /// One worker and one queued check: the third multipart body is refused 503 with `Retry-After`, and the two held ones
    /// are answered once the gate opens.
    private void partLimitExecutorFull_isRefused503_andTheAcceptedOnesAreAnswered(Protocol protocol) throws Exception {
        start(protocol, defaults(), 10 * MIB);
        ((AppHttpServerAdapter) server).usePartLimitExecutorForTest(PartLimitExecutor.partLimitExecutor(1, 1));
        gate = new CountDownLatch(1);
        var body = multipart(Part.file("a", 10));
        var held = new ArrayList<java.util.concurrent.CompletableFuture<Reply>>();

        held.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> post(protocol, "/api/files/", body)));
        awaitEntered(1);
        held.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> post(protocol, "/api/files/", body)));
        Thread.sleep(500);

        var refused = client(protocol).postBody("/api/files/", KEY_A, MULTIPART, body);

        assertThat(refused.status()).as("worker busy and queue full").isEqualTo(503);
        if (protocol == Protocol.H1) {
            assertThat(refused.text()).containsIgnoringCase("retry-after: 5");
        }

        gate.countDown();
        for (var future : held) {
            assertThat(future.get(20, TimeUnit.SECONDS).status()).as("the accepted ones are answered").isEqualTo(200);
        }

        assertThat(served.get()).as("the refused one never reached the slice").isEqualTo(2);
    }

    private Reply post(Protocol protocol, String path, byte[] body) {
        try {
            return client(protocol).postBody(path, KEY_A, MULTIPART, body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void awaitEntered(int count) throws InterruptedException {
        for (int attempt = 0; attempt < 200 && entered.get() < count; attempt++) {
            Thread.sleep(50);
        }

        assertThat(entered.get()).as("the router was entered").isGreaterThanOrEqualTo(count);
    }

    @Test
    void partLimitExecutorFull_isRefused503_andTheAcceptedOnesAreAnswered_overHttp1() throws Exception {
        partLimitExecutorFull_isRefused503_andTheAcceptedOnesAreAnswered(Protocol.H1);
    }

    @Test
    void partLimitExecutorFull_isRefused503_andTheAcceptedOnesAreAnswered_overHttp3() throws Exception {
        partLimitExecutorFull_isRefused503_andTheAcceptedOnesAreAnswered(Protocol.H3);
    }

    /// What a worker throws is answered, not lost with the worker: the client gets a 500 instead of waiting for nothing.
    private void aFailureOnThePartLimitWorker_isAnswered500(Protocol protocol) throws Exception {
        start(protocol, defaults(), 10 * MIB);
        failDispatch = true;

        assertThat(statusOf(protocol, "/api/files/", multipart(Part.file("a", 10)))).isEqualTo(500);
        failDispatch = false;
        assertThat(statusOf(protocol, "/api/files/", multipart(Part.file("a", 10)))).as("the worker lives on").isEqualTo(200);
    }

    @Test
    void aFailureOnThePartLimitWorker_isAnswered500_overHttp1() throws Exception {
        aFailureOnThePartLimitWorker_isAnswered500(Protocol.H1);
    }

    @Test
    void aFailureOnThePartLimitWorker_isAnswered500_overHttp3() throws Exception {
        aFailureOnThePartLimitWorker_isAnswered500(Protocol.H3);
    }

    /// The default ceiling is 256 MiB until streaming storage (U2); a route declaring more is capped to it.
    @Test
    void defaultCeiling_is256MiB() {
        assertThat(UploadConfig.DEFAULT.maxSizeCeiling()).isEqualTo(256L * MIB);
        assertThat(UploadConfig.DEFAULT.maxInFlightBytes()).as("a quarter of the maximum heap").isEqualTo(Runtime.getRuntime().maxMemory() / 4);
    }

    /// A `Content-Length` above 2 GiB, and above Integer.MAX_VALUE, on a route that declares more (4 GiB, under a raised 8 GiB
    /// ceiling) is refused 413 on the head: the effective limit never passes what one in-memory array holds.
    private void contentLengthOverTwoGiB_isRefused413OnTheHead_withoutOverflow(Protocol protocol) throws Exception {
        start(protocol, defaults().withMaxSizeCeiling(8L * 1024 * MIB), 10 * MIB);
        var client = client(protocol);

        assertThat(client.postDeclaredOnly("/api/huge/", KEY_A, (long) Integer.MAX_VALUE + 1).status()).as("2 GiB").isEqualTo(413);
        assertThat(client.postDeclaredOnly("/api/huge/", KEY_A, 3L * 1024 * MIB).status()).as("3 GiB").isEqualTo(413);
        assertThat(client.postDeclaredOnly("/api/huge/", KEY_A, 4L * 1024 * MIB).status()).as("exactly what the route declares").isEqualTo(413);
        assertThat(client.postDeclaredOnly("/api/huge/", KEY_A, Long.MAX_VALUE).status()).as("a length that would overflow an int a thousand times").isIn(400, 413);
        assertThat(uploadAdmissionOf().inFlight()).as("every refusal returned its permit").isZero();
        assertThat(uploadAdmissionOf().reservedBytes()).as("and its reserved bytes").isZero();
        assertThat(client.post("/api/huge/", Option.some(KEY_A), 1024, Mode.DECLARED).status()).as("control: a small body on the same route").isEqualTo(200);
    }

    private UploadAdmission uploadAdmissionOf() {
        return ((AppHttpServerAdapter) server).uploadAdmissionForTest();
    }

    @Test
    void contentLengthOverTwoGiB_isRefused413OnTheHead_withoutOverflow_overHttp1() throws Exception {
        contentLengthOverTwoGiB_isRefused413OnTheHead_withoutOverflow(Protocol.H1);
    }

    @Test
    void contentLengthOverTwoGiB_isRefused413OnTheHead_withoutOverflow_overHttp3() throws Exception {
        contentLengthOverTwoGiB_isRefused413OnTheHead_withoutOverflow(Protocol.H3);
    }

    // ---- F3: the node-wide budget of declared bytes in flight

    private static final String EXHAUSTED = OperatorWarningCode.UPLOAD_BYTE_BUDGET_EXHAUSTED.code();
    private static final String BUDGET_RESTORED = OperatorWarningCode.UPLOAD_BYTE_BUDGET_RESTORED.code();

    /// The holder declares 10 bytes of a 20-byte budget: a 16-byte upload does not fit and is refused 503 on its head, with
    /// one event for the four refusals; once the holder is done the budget is free again and says so.
    private void byteBudget_refuses503OnTheHead_raisesOneEventPerEpisode(Protocol protocol) throws Exception {
        start(protocol, defaults().withMaxInFlightBytes(20), 10 * MIB);
        var warnings = new CopyOnWriteArrayList<OperatorWarning>();

        server.setOperatorWarningSink(OperatorWarningSink.handingOffTo(warnings::add));
        var run = client(protocol).holdOne("/api/upload/", KEY_A, List.of(KEY_B, KEY_B, KEY_B, KEY_B));

        assertThat(run.duringStatuses()).as("four refusals by the budget, not by a count").containsExactly(503, 503, 503, 503);
        assertThat(run.afterReleaseStatus()).as("admitted once the holder's bytes are back").isEqualTo(200);
        awaitCodes(warnings, 2);
        assertThat(codesOf(warnings)).containsExactly(EXHAUSTED, BUDGET_RESTORED);
        assertThat(warnings.getFirst().subject()).isEqualTo("ingest-gate-node");
        assertThat(warnings.getFirst().message()).contains("max_in_flight_size").contains("20");
        assertThat(uploadAdmissionOf().reservedBytes()).as("nothing stays reserved").isZero();
    }

    @Test
    void byteBudget_refuses503OnTheHead_raisesOneEventPerEpisode_overHttp1() throws Exception {
        byteBudget_refuses503OnTheHead_raisesOneEventPerEpisode(Protocol.H1);
    }

    @Test
    void byteBudget_refuses503OnTheHead_raisesOneEventPerEpisode_overHttp3() throws Exception {
        byteBudget_refuses503OnTheHead_raisesOneEventPerEpisode(Protocol.H3);
    }

    /// What an upload reserves from the budget: its `Content-Length` (never more than the route's limit), the route's limit
    /// when it declares none (chunked), nothing for an empty body. The test holds part of a 150 MiB budget itself.
    private void byteBudget_reservesTheDeclaredLength_theRouteLimitWhenUndeclared_nothingForAnEmptyBody(Protocol protocol) throws Exception {
        start(protocol, defaults().withMaxInFlightBytes(150L * MIB), 10 * MIB);
        var client = client(protocol);
        var held = uploadAdmissionOf().tryAcquire("someone", 60L * MIB).unwrap();

        assertThat(client.post("/api/upload/", Option.some(KEY_A), 1024, Mode.DECLARED).status()).as("1 KiB of the 90 MiB left").isEqualTo(200);
        assertThat(client.post("/api/upload/", Option.some(KEY_A), 0, Mode.DECLARED).status()).as("an empty body").isEqualTo(200);
        assertThat(client.post("/api/upload/", Option.some(KEY_A), 1024, Mode.UNDECLARED).status())
            .as("chunked: the route's 100 MiB is reserved, 60 + 100 passes 150").isEqualTo(503);
        assertThat(client.postDeclaredOnly("/api/upload/", KEY_A, 1024L * MIB).status())
            .as("1 GiB declared on a 100 MiB route reserves 100 MiB: 60 + 100 passes 150").isEqualTo(503);
        held.run();

        var fortyMiB = uploadAdmissionOf().tryAcquire("someone", 40L * MIB).unwrap();

        assertThat(client.postDeclaredOnly("/api/upload/", KEY_A, 1024L * MIB).status())
            .as("40 + 100 fits 150, so the answer is the size refusal, and not the budget's").isEqualTo(413);
        assertThat(uploadAdmissionOf().reservedBytes()).as("the refusal returned its bytes; the held ones remain").isEqualTo(40L * MIB);
        fortyMiB.run();
        assertThat(uploadAdmissionOf().reservedBytes()).isZero();
    }

    @Test
    void byteBudget_reservesTheDeclaredLength_theRouteLimitWhenUndeclared_nothingForAnEmptyBody_overHttp1() throws Exception {
        byteBudget_reservesTheDeclaredLength_theRouteLimitWhenUndeclared_nothingForAnEmptyBody(Protocol.H1);
    }

    @Test
    void byteBudget_reservesTheDeclaredLength_theRouteLimitWhenUndeclared_nothingForAnEmptyBody_overHttp3() throws Exception {
        byteBudget_reservesTheDeclaredLength_theRouteLimitWhenUndeclared_nothingForAnEmptyBody(Protocol.H3);
    }

    @Test
    void byteBudget_answersWithRetryAfter_overHttp1() throws Exception {
        start(Protocol.H1, defaults().withMaxInFlightBytes(20), 10 * MIB);

        var run = client(Protocol.H1).holdOne("/api/upload/", KEY_A, List.of(KEY_B));

        assertThat(run.duringStatuses()).containsExactly(503);
        assertThat(run.firstRefusalText()).containsIgnoringCase("retry-after: 5").contains("Too many bytes of uploads in flight on this node");
    }

    // ---- operator events: node-cap saturation only, once per episode

    private static final String SATURATED = OperatorWarningCode.UPLOAD_NODE_CAP_SATURATED.code();
    private static final String RESTORED = OperatorWarningCode.UPLOAD_NODE_CAP_RESTORED.code();

    private static List<String> codesOf(List<OperatorWarning> warnings) {
        return warnings.stream().map(warning -> warning.code().code()).toList();
    }

    private static void awaitCodes(List<OperatorWarning> warnings, int count) throws InterruptedException {
        for (int attempt = 0; attempt < 100 && warnings.size() < count; attempt++) {
            Thread.sleep(50);
        }
    }

    @Test
    void nodeCapSaturation_raisesOneWarningForManyRefusals_andOneRecovery_overHttp1() throws Exception {
        nodeCapSaturation_raisesOneWarningForManyRefusals_andOneRecovery(Protocol.H1);
    }

    @Test
    void nodeCapSaturation_raisesOneWarningForManyRefusals_andOneRecovery_overHttp3() throws Exception {
        nodeCapSaturation_raisesOneWarningForManyRefusals_andOneRecovery(Protocol.H3);
    }

    private void nodeCapSaturation_raisesOneWarningForManyRefusals_andOneRecovery(Protocol protocol) throws Exception {
        start(protocol, defaults().withMaxConcurrent(1), 10 * MIB);
        var warnings = new CopyOnWriteArrayList<OperatorWarning>();

        server.setOperatorWarningSink(OperatorWarningSink.handingOffTo(warnings::add));
        var run = client(protocol).holdOne("/api/upload/", KEY_A, List.of(KEY_B, KEY_B, KEY_B, KEY_B));

        assertThat(run.duringStatuses()).as("four refusals").containsExactly(429, 429, 429, 429);
        awaitCodes(warnings, 2);
        assertThat(codesOf(warnings)).as("one saturation for the four refusals, then one recovery").containsExactly(SATURATED, RESTORED);
        assertThat(warnings.getFirst().subject()).as("the subject is this node").isEqualTo("ingest-gate-node");
        assertThat(warnings.getFirst().message()).as("names the cap in force: max_concurrent is 1 here, the per-principal cap 8").contains("holds 1 concurrent uploads").contains("max_concurrent");
    }

    /// End to end: the node's own sink is the cluster event aggregator, and the log gets one saturation event for the
    /// episode and then its recovery.
    @Test
    void nodeCapSaturation_reachesTheClusterEventLog_asOneEvent_andItsRecovery_overHttp1() throws Exception {
        nodeCapSaturation_reachesTheClusterEventLog_asOneEvent_andItsRecovery(Protocol.H1);
    }

    @Test
    void nodeCapSaturation_reachesTheClusterEventLog_asOneEvent_andItsRecovery_overHttp3() throws Exception {
        nodeCapSaturation_reachesTheClusterEventLog_asOneEvent_andItsRecovery(Protocol.H3);
    }

    private void nodeCapSaturation_reachesTheClusterEventLog_asOneEvent_andItsRecovery(Protocol protocol) throws Exception {
        start(protocol, defaults().withMaxConcurrent(1), 10 * MIB);
        var log = EventLog.create();

        server.setOperatorWarningSink(OperatorWarningSink.handingOffTo(log.aggregator()::onOperatorWarning));
        client(protocol).holdOne("/api/upload/", KEY_A, List.of(KEY_B, KEY_B, KEY_B));

        var events = log.awaitEvents(2);

        assertThat(events.stream().map(event -> event.details().get("code")).toList()).containsExactly(SATURATED, RESTORED);
        assertThat(events.getFirst().severity()).isEqualTo(ClusterEvent.Severity.WARNING);
        assertThat(events.getFirst().details()).containsEntry("subject", "ingest-gate-node");
        assertThat(events.getLast().severity()).isEqualTo(ClusterEvent.Severity.INFO);
    }

    @Test
    void perPrincipalRefusal_raisesNoEvent_overHttp1() throws Exception {
        perPrincipalRefusal_raisesNoEvent(Protocol.H1);
    }

    @Test
    void perPrincipalRefusal_raisesNoEvent_overHttp3() throws Exception {
        perPrincipalRefusal_raisesNoEvent(Protocol.H3);
    }

    private void perPrincipalRefusal_raisesNoEvent(Protocol protocol) throws Exception {
        start(protocol, defaults().withMaxConcurrentPerPrincipal(1), 10 * MIB);
        var warnings = new CopyOnWriteArrayList<OperatorWarning>();

        server.setOperatorWarningSink(OperatorWarningSink.handingOffTo(warnings::add));
        var run = client(protocol).holdOne("/api/upload/", KEY_A, List.of(KEY_A, KEY_A));

        assertThat(run.duringStatuses()).as("control: refused by the principal's cap").containsExactly(429, 429);
        Thread.sleep(300);
        assertThat(warnings).as("a client error: no operator event").isEmpty();
    }

    @Test
    void sizeAndTimeRefusals_raiseNoEvent_overHttp1() throws Exception {
        sizeAndTimeRefusals_raiseNoEvent(Protocol.H1);
    }

    @Test
    void sizeAndTimeRefusals_raiseNoEvent_overHttp3() throws Exception {
        sizeAndTimeRefusals_raiseNoEvent(Protocol.H3);
    }

    private void sizeAndTimeRefusals_raiseNoEvent(Protocol protocol) throws Exception {
        start(protocol, defaults().withIdleTimeout(timeSpan(1).seconds()), 10 * MIB);
        var warnings = new CopyOnWriteArrayList<OperatorWarning>();

        server.setOperatorWarningSink(OperatorWarningSink.handingOffTo(warnings::add));
        var client = client(protocol);

        assertThat(client.post("/api/small/", Option.some(KEY_A), 2 * MIB, Mode.DECLARED).status()).as("control: 413").isEqualTo(413);
        assertThat(client.postStalled("/api/upload/", KEY_A).status()).as("control: 408").isEqualTo(408);
        assertThat(statusOf(protocol, "/api/files/", multipart(Part.file("a", 1), Part.file("b", 1), Part.file("c", 1)))).as("control: part limit 413").isEqualTo(413);
        Thread.sleep(300);
        assertThat(warnings).as("client errors: no operator event").isEmpty();
    }

    /// The real aggregator over a real (in-memory) cluster-events stream, as `ClusterEventAggregatorTest` builds it.
    private record EventLog(ClusterEventAggregator aggregator) {
        private static final NodeId SELF = NodeId.nodeId("ingest-gate-node").unwrap();
        private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());

        static EventLog create() {
            var manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
            var config = StreamConfig.streamConfig(SystemStreams.CLUSTER_EVENTS.asString(),
                                                   1,
                                                   RetentionPolicy.retentionPolicy(10_000, 64L * 1024 * 1024, Long.MAX_VALUE, RetentionMode.ANY),
                                                   "earliest",
                                                   64L * 1024,
                                                   ConsistencyMode.EVENTUAL,
                                                   1);
            var publisher = SystemStreamFactories.<ClusterEvent>systemStreamPublisher(SystemStreams.CLUSTER_EVENTS, manager, CODEC, config).unwrap();
            var consumer = SystemStreamFactories.<ClusterEvent>systemStreamConsumer(SystemStreams.CLUSTER_EVENTS, manager, CODEC, CODEC, config).unwrap();
            var publisherRef = new AtomicReference<FrameworkStreamPublisher<ClusterEvent>>(publisher);
            var consumerRef = new AtomicReference<FrameworkStreamConsumer<ClusterEvent>>(consumer);

            return new EventLog(ClusterEventAggregator.clusterEventAggregator(publisherRef::get,
                                                                              consumerRef::get,
                                                                              () -> true,
                                                                              SELF,
                                                                              HlcClock.hlcClock(SELF),
                                                                              () -> 1,
                                                                              () -> false,
                                                                              () -> true));
        }

        List<ClusterEvent> awaitEvents(int count) throws InterruptedException {
            var events = aggregator.events().await().or(List.of());

            for (int attempt = 0; attempt < 100 && events.size() < count; attempt++) {
                Thread.sleep(50);
                events = aggregator.events().await().or(List.of());
            }

            return events;
        }
    }
}
