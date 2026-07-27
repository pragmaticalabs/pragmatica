// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Option;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// #488 — the declarative `[streams.X]` consumer actually receives published events.
///
/// Before this wiring, `NodeDeploymentState` wrote a `StreamRegistrationKey` on every deployment and
/// NOTHING read it back: a slice could declare `@ResourceQualifier(type = StreamSubscriber.class)`
/// and silently receive nothing forever. This test is the end-to-end proof that it now receives.
///
/// **Non-vacuity.** The fixture slice records deliveries into an in-memory queue reported by
/// `POST /api/stream-consumer/received`, and `onConsumerEvent` is deliberately ABSENT from the
/// fixture's `routes.toml` — nothing but the framework's delivery path can invoke it. Against the
/// pre-fix runtime this suite fails on the first assertion with `received == 0`, because there is no
/// delivery loop at all. If it ever passes without a delivery loop, the fixture is broken, not the
/// runtime.
///
/// **The gating discriminator.** `test-stream-consumer` declares `partitions = 1`, and the blueprint
/// deploys the slice to every node. Exactly ONE node is the HRW owner of that single partition, so a
/// correctly gated consumer delivers each event exactly once CLUSTER-WIDE. An ungated implementation —
/// one that consumed wherever the ring happens to be materialized — would deliver each event once per
/// replica, and the total would be a multiple of the published count. Asserting the exact total is
/// therefore simultaneously a delivery proof and a duplication proof.
///
/// **Honest scope.** Forge is a single-JVM Ember cluster: this proves dispatch, ownership gating,
/// registration-driven attach, and the observability surface. It does NOT prove cross-node owner
/// failover or cursor resume across a real node death — that needs the cluster harness.
///
/// **Two streams, two mechanisms.** `streams.consumer-events` is `String`-typed and carries the #488
/// proof. `streams.order-events` is `OrderPlaced`-typed — an application-defined record — and carries
/// the #526 proof: until stream resources were provisioned with the deployed SLICE's codec instead of
/// the node-wide one, publishing it threw "No codec registered for class" before routing was even
/// reached. The two are deliberately independent so a regression in either cannot mask the other.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeclarativeStreamConsumerTest {
    private static final int BASE_PORT = 14000;
    private static final int BASE_MGMT_PORT = 14100;
    private static final int BASE_APP_HTTP_PORT = 14200;
    private static final int NODES = 5;
    private static final int INSTANCES = 5;
    private static final int EVENT_COUNT = 30;
    private static final int ORDER_COUNT = 10;

    private static final String CONSUMER_EVENTS_STREAM = "consumer-events";
    private static final String ORDER_EVENTS_STREAM = "order-events";

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    private static final String CONSUMER_SLICE = TestArtifacts.STREAM_CONSUMER_SLICE;
    private static final String BLUEPRINT_ID = "forge.test:declarative-consumer:1.0.0";
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";

    private static final Pattern COUNT_FIELD = Pattern.compile("\"count\"\\s*:\\s*(\\d+)");
    private static final Pattern ATTACHED_FIELD = Pattern.compile("\"attachedSubscriptions\"\\s*:\\s*(\\d+)");

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();

    @BeforeAll
    void setUp() {
        // Resource provisioning (StreamPublisher / the [streams.X] declaration) is gated on a
        // ConfigurationProvider being present; without it the node installs a no-op facade and every
        // resource-backed slice fails to load. Mirrors ForgeServer.buildConfigurationProvider.
        var configProvider = ConfigurationProvider.builder()
                                                  .withSystemProperties("aether.")
                                                  .withEnvironment("AETHER_")
                                                  .build();
        cluster = emberCluster(NODES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "dsc", Option.some(configProvider));
        cluster.start()
               .await()
               .onFailure(cause -> {
                   throw new AssertionError("Cluster start failed: " + cause.message());
               });

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(this::allNodesHealthy);

        deployConsumerSlice();

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(this::failIfSliceFailed)
               .until(this::appHttpReady);

        // A publish can land before the partition owner has materialized its ring, so gate on a real
        // publish succeeding before any test runs.
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(this::failIfSliceFailed)
               .until(this::publishReady);

        // The consumer attaches on the manager's ownership tick, which is independent of publish
        // readiness — gate on the registration actually having produced a subscription somewhere.
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(this::failIfSliceFailed)
               .until(() -> totalAttachedSubscriptions() > 0);

        // Gate on EACH stream separately. A subscription attaches at the stream TAIL, so an event
        // published before its consumer attached is skipped, not queued — and with two streams the
        // aggregate gate above is satisfied by whichever attaches first. Waiting per stream is what
        // makes the delivery assertions measure delivery rather than attach timing.
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(this::failIfSliceFailed)
               .until(() -> consumerAttachedFor(CONSUMER_EVENTS_STREAM) && consumerAttachedFor(ORDER_EVENTS_STREAM));
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            var leaderPort = cluster.getLeaderManagementPort().or(anyMgmtPort());

            httpDelete(leaderPort, "/api/blueprints/" + BLUEPRINT_ID);
            cluster.stop()
                   .await();
        }
    }

    @Nested
    class Delivery {

        /// The headline #488 assertion: events published to the stream reach the declared method.
        @Test
        void declaredConsumer_receivesPublishedEvents_withoutAnyExplicitSubscribe() {
            var baseline = totalReceived();

            publishBatch(EVENT_COUNT);

            await().atMost(DELIVERY_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .untilAsserted(() -> assertThat(totalReceived() - baseline)
                           .describedAs("declarative consumer must receive every published event — before #488 this stayed at 0 forever")
                           .isEqualTo(EVENT_COUNT));
        }

        /// Ownership gating: with one partition and the slice on every node, exactly one node consumes.
        /// An ungated implementation would multiply this by the number of nodes holding a materialized ring.
        @Test
        void declaredConsumer_deliversEachEventExactlyOnceClusterWide() {
            var baseline = totalReceived();

            publishBatch(EVENT_COUNT);

            await().atMost(DELIVERY_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .untilAsserted(() -> assertThat(totalReceived() - baseline).isEqualTo(EVENT_COUNT));

            // Hold past several reconcile ticks: a second node attaching late would push the total above
            // EVENT_COUNT, which a single sampled assertion would miss.
            sleep(Duration.ofSeconds(12));

            assertThat(totalReceived() - baseline)
                    .describedAs("no duplicate delivery — only the partition OWNER consumes, and it stays the only one")
                    .isEqualTo(EVENT_COUNT);
        }
    }

    @Nested
    class Observability {

        /// The operator surface must agree with reality: one attached subscription per declared
        /// consumer, each on a single-partition stream, so two cluster-wide for the fixture's two
        /// streams — never more, which is what a duplicate attach would look like.
        @Test
        void declarativeConsumersEndpoint_reportsOneAttachedSubscriptionPerStream() {
            assertThat(totalAttachedSubscriptions())
                    .describedAs("two streams, one partition each, one owner each")
                    .isEqualTo(2);
        }

        /// Every node knows the declaration (it is cluster-wide KV), and the endpoint answers on all of
        /// them — the non-owning nodes truthfully report zero assigned partitions rather than nothing.
        @Test
        void declarativeConsumersEndpoint_answersOnEveryNode_namingStreamAndMethod() {
            var bodies = mgmtPorts().stream()
                                    .map(port -> httpGet(port, "/api/streams/declarative-consumers"))
                                    .toList();

            assertThat(bodies).allSatisfy(body -> {
                assertThat(body).doesNotContain("\"error\"");
                assertThat(body).contains("consumer-events");
                assertThat(body).contains("onConsumerEvent");
            });
        }

        /// #526 surface: the event type here IS publishable (String is in the node codec), so the
        /// endpoint must say so rather than warning spuriously.
        @Test
        void declarativeConsumersEndpoint_reportsEventTypePublishable_forStringEvents() {
            var body = httpGet(ownerMgmtPort(), "/api/streams/declarative-consumers");

            assertThat(body).contains("\"eventTypePublishable\":true");
            assertThat(body).contains("java.lang.String");
        }
    }

    /// #526 — an APPLICATION-DEFINED record survives the full stream round trip.
    ///
    /// Every other stream fixture in the corpus is `String`-typed, a type the node-wide codec already
    /// knows, which is exactly why nothing caught the defect. `OrderPlaced` is known only to the
    /// slice's own codec, so these assertions fail against the pre-fix runtime at the PUBLISH call
    /// with "No codec registered for class" — before delivery is ever reached.
    @Nested
    class ApplicationTypedDelivery {

        /// The headline #526 assertion: publishing an app-defined record succeeds at all.
        @Test
        void publishOrder_succeeds_forApplicationDefinedEventType() {
            var response = httpPost(appPort(),
                                    "/api/stream-consumer/publish-order",
                                    "{\"orderId\":\"order-probe\",\"customer\":\"acme\",\"amount\":7}");

            assertThat(response).describedAs("app-typed publish must succeed — before #526 this threw "
                                             + "'No codec registered for class' because the publisher held the node codec")
                                .doesNotContain("\"error\"")
                                .contains("published");
        }

        /// Both ends of the stream must resolve the slice codec: the publisher to ENCODE the record
        /// and the consumer's reader to DECODE it. Delivery proves both halves at once.
        @Test
        void declaredConsumer_receivesApplicationTypedEvents_endToEnd() {
            var baseline = totalOrdersReceived();

            publishOrderBatch(ORDER_COUNT);

            await().atMost(DELIVERY_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .untilAsserted(() -> assertThat(totalOrdersReceived() - baseline)
                           .describedAs("every app-typed event must round-trip through the slice codec")
                           .isEqualTo(ORDER_COUNT));
        }

        /// The record's fields must survive encode/decode intact — delivery of a corrupted or
        /// default-constructed value would still satisfy a bare count assertion.
        @Test
        void declaredConsumer_preservesEveryRecordComponent_throughTheStream() {
            publishOrderBatch(ORDER_COUNT);

            await().atMost(DELIVERY_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .untilAsserted(() -> assertThat(receivedOrdersBody())
                           .describedAs("the decoded record must carry the published field values, not defaults")
                           .contains("\"orderId\":\"order-0\"")
                           .contains("\"customer\":\"customer-0\"")
                           .contains("\"amount\":100"));
        }

        /// The operator surface must stop warning about app types now that they genuinely publish.
        @Test
        void declarativeConsumersEndpoint_reportsEventTypePublishable_forApplicationType() {
            var fragments = mgmtPorts().stream()
                                       .map(port -> consumerFragment(httpGet(port, "/api/streams/declarative-consumers"),
                                                                     ORDER_EVENTS_STREAM))
                                       .filter(fragment -> !fragment.isEmpty())
                                       .toList();

            assertThat(fragments).describedAs("every node knows the app-typed declaration — it is cluster-wide KV")
                                 .isNotEmpty();
            assertThat(fragments).allSatisfy(fragment -> {
                assertThat(fragment).describedAs("the slice's own codec registers OrderPlaced, so it IS publishable")
                                    .contains("\"eventTypePublishable\":true");
                assertThat(fragment).doesNotContain("cannot be PUBLISHED");
            });
        }
    }

    // --- publish / receive ---------------------------------------------------

    private void publishBatch(int count) {
        for (var i = 0; i < count; i++) {
            var response = httpPost(appPort(), "/api/stream-consumer/publish", "{\"payload\":\"event-" + i + "\"}");

            assertThat(response).describedAs("publish must succeed").contains("published");
        }
    }

    private void publishOrderBatch(int count) {
        for (var i = 0; i < count; i++) {
            var body = "{\"orderId\":\"order-" + i + "\",\"customer\":\"customer-" + i + "\",\"amount\":" + (100 + i) + "}";
            var response = httpPost(appPort(), "/api/stream-consumer/publish-order", body);

            assertThat(response).describedAs("app-typed publish must succeed").contains("published");
        }
    }

    /// Sum of app-typed deliveries across every node. Only the partition owner's queue is non-empty;
    /// summing keeps the assertion independent of WHICH node owns it.
    private int totalOrdersReceived() {
        return cluster.getAvailableAppHttpPorts()
                      .stream()
                      .map(port -> httpPost(port, "/api/stream-consumer/received-orders", "{}"))
                      .mapToInt(body -> firstInt(COUNT_FIELD, body))
                      .sum();
    }

    /// The one node that actually received app-typed events, so field assertions read real deliveries.
    private String receivedOrdersBody() {
        return cluster.getAvailableAppHttpPorts()
                      .stream()
                      .map(port -> httpPost(port, "/api/stream-consumer/received-orders", "{}"))
                      .filter(body -> firstInt(COUNT_FIELD, body) > 0)
                      .findFirst()
                      .orElse("{\"count\":0,\"orders\":[]}");
    }

    /// Sum of what every node's slice instance actually received. Only the owner's queue is non-empty,
    /// but summing keeps the assertion independent of WHICH node owns the partition.
    private int totalReceived() {
        return cluster.getAvailableAppHttpPorts()
                      .stream()
                      .map(port -> httpPost(port, "/api/stream-consumer/received", "{}"))
                      .mapToInt(body -> firstInt(COUNT_FIELD, body))
                      .sum();
    }

    private int totalAttachedSubscriptions() {
        return mgmtPorts().stream()
                          .map(port -> httpGet(port, "/api/streams/declarative-consumers"))
                          .mapToInt(body -> firstInt(ATTACHED_FIELD, body))
                          .sum();
    }

    /// True once SOME node reports a live subscription for `stream`. Per-stream, because the
    /// aggregate count cannot distinguish "both consumers attached" from "one attached twice".
    private boolean consumerAttachedFor(String stream) {
        return mgmtPorts().stream()
                          .map(port -> consumerFragment(httpGet(port, "/api/streams/declarative-consumers"), stream))
                          .anyMatch(fragment -> fragment.contains("\"assignedPartitions\""));
    }

    /// The slice of the declarative-consumers JSON describing one stream. Substring-scoped rather than
    /// parsed: the response carries one object per declared consumer and a whole-body `contains` would
    /// happily match a field belonging to the OTHER stream.
    private static String consumerFragment(String body, String stream) {
        return Arrays.stream(body.split("\\{\"stream\":\""))
                     .filter(fragment -> fragment.startsWith(stream + "\""))
                     .findFirst()
                     .orElse("");
    }

    private int ownerMgmtPort() {
        return mgmtPorts().stream()
                          .filter(port -> firstInt(ATTACHED_FIELD, httpGet(port, "/api/streams/declarative-consumers")) > 0)
                          .findFirst()
                          .orElseThrow(() -> new AssertionError("No node reports an attached declarative consumer"));
    }

    private static int firstInt(Pattern pattern, String body) {
        var matcher = pattern.matcher(body);

        return matcher.find()
               ? Integer.parseInt(matcher.group(1))
               : 0;
    }

    // --- deployment + readiness ---------------------------------------------

    private void deployConsumerSlice() {
        var blueprint = """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = %d
            """.formatted(BLUEPRINT_ID, CONSUMER_SLICE, INSTANCES);
        var leaderPort = cluster.getLeaderManagementPort().or(anyMgmtPort());
        var response = httpPostToml(leaderPort, "/api/blueprints", blueprint);

        assertThat(response).describedAs("declarative-consumer slice deployment")
                            .doesNotContain("\"error\"")
                            .contains("\"status\":\"applied\"");
    }

    private boolean appHttpReady() {
        var ports = cluster.getAvailableAppHttpPorts();

        if (ports.isEmpty()) {
            return false;
        }

        var body = httpPost(ports.getFirst(), "/api/stream-consumer/received", "{}");

        return !body.contains("\"error\"") && body.contains("count");
    }

    private boolean publishReady() {
        var ports = cluster.getAvailableAppHttpPorts();

        if (ports.isEmpty()) {
            return false;
        }

        var response = httpPost(ports.getFirst(), "/api/stream-consumer/publish", "{\"payload\":\"__warmup__\"}");

        return !response.contains("\"error\"") && response.contains("published");
    }

    private void failIfSliceFailed() {
        var failed = cluster.slicesStatus()
                            .stream()
                            .anyMatch(status -> status.artifact().equals(CONSUMER_SLICE) && status.state().equals("FAILED"));

        if (failed) {
            throw new AssertionError("Declarative-consumer slice deployment FAILED: " + CONSUMER_SLICE);
        }
    }

    private int appPort() {
        return cluster.getAvailableAppHttpPorts()
                      .stream()
                      .findFirst()
                      .orElseThrow(() -> new AssertionError("No app-http route is ready"));
    }

    private List<Integer> mgmtPorts() {
        return cluster.status()
                      .nodes()
                      .stream()
                      .map(node -> node.mgmtPort())
                      .toList();
    }

    private int anyMgmtPort() {
        return cluster.status().nodes().getFirst().mgmtPort();
    }

    private boolean allNodesHealthy() {
        return cluster.status()
                      .nodes()
                      .stream()
                      .allMatch(node -> checkNodeHealth(node.mgmtPort()));
    }

    private boolean checkNodeHealth(int port) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/health"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(response -> response.statusCode() == 200 && response.body().contains("\"quorum\":true"))
                   .or(false);
    }

    private static void sleep(Duration duration) {
        java.util.concurrent.locks.LockSupport.parkNanos(duration.toNanos());
    }

    // --- HTTP ----------------------------------------------------------------

    private String httpPostToml(int port, String path, String body) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .header("Content-Type", "application/toml")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(10))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    private String httpPost(int port, String path, String body) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(15))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    private String httpGet(int port, String path) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .GET()
                                 .timeout(Duration.ofSeconds(10))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    private String httpDelete(int port, String path) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .DELETE()
                                 .timeout(Duration.ofSeconds(10))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }
}
