// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.http.QueryParams;
import org.pragmatica.http.routing.RequestContext;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpHeaders;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #542 sibling — the Schema Management endpoints declared their failures as plain
/// `Causes.cause(...)` constants. `ProblemResponses.resolveStatus` tests
/// `cause instanceof HttpStatusAware` and silently defaults everything else to 500, so a missing
/// datasource, a refused retry and a genuine node fault were indistinguishable on the wire. The
/// causes now carry their own status via [SchemaRouteError].
///
/// The proof has to be taken at the ROUTE level, not at the cause level: asserting that
/// `SchemaRouteError.SchemaNotFailed.httpStatus() == CONFLICT` proves only that the record was typed
/// correctly. What actually breaks in production is a hop between the raising site and the response
/// funnel that re-wraps the cause — a composite, an `HttpError` re-wrap or a `mapError` — because
/// any of those erase the mixin and silently restore the 500. So every test here drives the real
/// `Route` handler and then feeds the emerging cause through the exact `ProblemResponses.writeProblem`
/// call `ManagementRouter.writeError` makes.
///
/// Note on the "unwrapped" assertion: `BlueprintPublishConflictStatusTest` can use `isSameAs`
/// because its fixture SUPPLIES the cause instance to a stubbed service. These causes are minted
/// inside `SchemaRoutes` (they name the datasource), so the fixture never holds the original.
/// `isEqualTo` against a freshly built expected record is the faithful stand-in and is in fact
/// stricter: records compare by value, so a wrapper, a re-typed cause or a drifted message all fail,
/// and the message content is pinned at the same time.
class SchemaRouteStatusTest {
    private static final String DATASOURCE = "orders_db";
    private static final String COORDS = "org.example:orders-app:1.0.0";
    private static final BlueprintId OWNER = BlueprintId.blueprintId(COORDS).unwrap();
    private static final String INSTANCE = "/api/v1/schema/status/" + DATASOURCE;
    private static final String REQUEST_ID = "req-1";

    private InMemoryKvStore store;
    private SchemaRoutes routes;

    @BeforeEach
    void setUp() {
        store = new InMemoryKvStore(MessageRouter.mutable());
        routes = SchemaRoutes.schemaRoutes(() -> nodeOver(store));
    }

    /// Every route in the group reads an existing record through `lookupSchemaVersion`, so all six
    /// must answer 404 rather than 500 for an unknown datasource. One test per route: `aether/node`
    /// has no `junit-jupiter-params`, and a loop would report a single opaque failure.
    @Nested
    class RecordNotFound {
        @Test
        void statusRoute_respondsNotFound_whenDatasourceHasNoRecord() {
            assertRespondsNotFound(ManagementRoute.SCHEMA_STATUS_ONE);
        }

        @Test
        void historyRoute_respondsNotFound_whenDatasourceHasNoRecord() {
            assertRespondsNotFound(ManagementRoute.SCHEMA_HISTORY);
        }

        @Test
        void migrateRoute_respondsNotFound_whenDatasourceHasNoRecord() {
            assertRespondsNotFound(ManagementRoute.SCHEMA_MIGRATE);
        }

        @Test
        void retryRoute_respondsNotFound_whenDatasourceHasNoRecord() {
            assertRespondsNotFound(ManagementRoute.SCHEMA_RETRY);
        }

        @Test
        void undoRoute_respondsNotFound_whenDatasourceHasNoRecord() {
            assertRespondsNotFound(ManagementRoute.SCHEMA_UNDO, "targetVersion", "2");
        }

        @Test
        void baselineRoute_respondsNotFound_whenDatasourceHasNoRecord() {
            assertRespondsNotFound(ManagementRoute.SCHEMA_BASELINE, "version", "3");
        }

        @Test
        void statusRoute_propagatesCauseUnwrapped_whenDatasourceHasNoRecord() {
            assertThat(causeFrom(ManagementRoute.SCHEMA_STATUS_ONE)).as("any wrapping on the way out erases the HttpStatusAware mixin and restores the 500")
                      .isEqualTo(SchemaRouteError.SchemaRecordNotFound.schemaRecordNotFound(DATASOURCE));
        }

        @Test
        void problemBody_namesDatasource_soTheOperatorKnowsWhichRecordIsMissing() {
            assertThat(problemBodyFor(ManagementRoute.SCHEMA_STATUS_ONE)).contains(DATASOURCE)
                      .contains("Schema status not found")
                      .contains("404");
        }
    }

    /// `retry` against a datasource that exists but is not `FAILED` is a state conflict, not a
    /// server fault and not a malformed request.
    @Nested
    class RetryConflict {
        @Test
        void retryRoute_respondsConflict_whenSchemaIsNotFailed() {
            seed(SchemaStatus.COMPLETED);
            assertThat(statusFor(ManagementRoute.SCHEMA_RETRY)).as("a refused retry is a state conflict, not a server fault")
                      .isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        void retryRoute_propagatesCauseUnwrapped_whenSchemaIsNotFailed() {
            seed(SchemaStatus.COMPLETED);
            assertThat(causeFrom(ManagementRoute.SCHEMA_RETRY)).isEqualTo(SchemaRouteError.SchemaNotFailed.schemaNotFailed(DATASOURCE,
                                                                                                                           SchemaStatus.COMPLETED));
        }

        @Test
        void problemBody_namesDatasourceAndObservedStatus_soTheOperatorCanAct() {
            seed(SchemaStatus.MIGRATING);
            assertThat(problemBodyFor(ManagementRoute.SCHEMA_RETRY)).contains(DATASOURCE)
                      .contains("MIGRATING")
                      .contains("409");
        }

        /// The two integration suites that assert this contract
        /// (`10-database/test-schema-retry.sh`, `06-deployment/test-schema-migration.sh`) grep the
        /// response body for this exact phrase. Enriching the message must not break them.
        @Test
        void problemBody_retainsScriptedClientPhrase_whenSchemaIsNotFailed() {
            seed(SchemaStatus.COMPLETED);
            assertThat(problemBodyFor(ManagementRoute.SCHEMA_RETRY)).contains("not in FAILED state");
        }

        @Test
        void retryRoute_succeeds_whenSchemaIsFailed() {
            seed(SchemaStatus.FAILED);
            handle(ManagementRoute.SCHEMA_RETRY, Option.none(), Option.none()).onFailure(cause -> Assertions.fail("Retry against a FAILED record must succeed: " + cause.message()));
        }
    }

    /// A present-but-unparseable version parameter used to throw `NumberFormatException` out of the
    /// handler; nothing between the route builder and `ManagementRouter` lifts, so it was caught only
    /// by the outermost Netty guard, which answers 500 with a bare `{"error":"Internal Server Error"}`
    /// envelope — outside the RFC 9457 funnel entirely.
    @Nested
    class InvalidVersionParameter {
        @Test
        void baselineRoute_respondsBadRequest_whenVersionIsNotAnInteger() {
            seed(SchemaStatus.COMPLETED);
            assertThat(statusFor(ManagementRoute.SCHEMA_BASELINE, "version", "abc")).as("an unparseable parameter is the caller's error, not the cluster's")
                      .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void undoRoute_respondsBadRequest_whenTargetVersionIsNotAnInteger() {
            seed(SchemaStatus.COMPLETED);
            assertThat(statusFor(ManagementRoute.SCHEMA_UNDO, "targetVersion", "not-a-number")).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void baselineRoute_propagatesCauseUnwrapped_whenVersionIsNotAnInteger() {
            seed(SchemaStatus.COMPLETED);
            assertThat(causeFrom(ManagementRoute.SCHEMA_BASELINE, "version", "abc")).isEqualTo(SchemaRouteError.InvalidVersionParameter.invalidVersionParameter("version",
                                                                                                                                                                "abc"));
        }

        @Test
        void problemBody_namesOffendingParameterAndValue_whenVersionIsNotAnInteger() {
            seed(SchemaStatus.COMPLETED);
            assertThat(problemBodyFor(ManagementRoute.SCHEMA_BASELINE, "version", "abc")).contains("version")
                      .contains("abc")
                      .contains("400");
        }

        /// An ABSENT parameter is not an error — it keeps the documented default (baseline 1,
        /// undo 0). Only a present-but-invalid value became a 400.
        @Test
        void baselineRoute_succeeds_whenVersionIsAbsent() {
            seed(SchemaStatus.COMPLETED);
            handle(ManagementRoute.SCHEMA_BASELINE, Option.none(), Option.none()).onFailure(cause -> Assertions.fail("An absent version must fall back to the default: " + cause.message()));
            assertThat(recorded().currentVersion()).isEqualTo(1);
        }

        @Test
        void baselineRoute_writesNothing_whenVersionIsNotAnInteger() {
            seed(SchemaStatus.COMPLETED);
            handle(ManagementRoute.SCHEMA_BASELINE, Option.some("version"), Option.some("abc"));
            assertThat(recorded().currentVersion()).as("a rejected request must not have written").isEqualTo(3);
        }
    }

    /// Without this, the 404/409/400 above could equally be explained by the funnel defaulting
    /// everything to those codes. This pins the default so the codes are shown to come from the
    /// mixin.
    @Nested
    class NegativeControl {
        @Test
        void problemStatus_fallsBackToServerError_forCauseWithoutStatusMixin() {
            var recorder = new RecordingResponseWriter();

            ProblemResponses.writeProblem(recorder, Causes.cause("plain failure"), INSTANCE, REQUEST_ID);
            assertThat(recorder.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        void plainCause_doesNotCarryStatusMixin() {
            assertThat(Causes.cause("plain failure")).isNotInstanceOf(HttpStatusAware.class);
        }
    }

    // --- assertions ---
    private void assertRespondsNotFound(ManagementRoute route) {
        assertThat(statusFor(route)).as("an unknown datasource is a missing resource, not a server fault")
                  .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private void assertRespondsNotFound(ManagementRoute route, String queryName, String queryValue) {
        assertThat(statusFor(route, queryName, queryValue)).as("an unknown datasource is a missing resource, not a server fault")
                  .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- helpers ---
    /// Runs the emerging cause through the exact funnel call `ManagementRouter.writeError` makes.
    private HttpStatus statusFor(ManagementRoute route) {
        return statusFor(route, Option.none(), Option.none());
    }

    private HttpStatus statusFor(ManagementRoute route, String queryName, String queryValue) {
        return statusFor(route, Option.some(queryName), Option.some(queryValue));
    }

    private HttpStatus statusFor(ManagementRoute route, Option<String> queryName, Option<String> queryValue) {
        return writeProblemFor(route, queryName, queryValue).status();
    }

    private String problemBodyFor(ManagementRoute route) {
        return writeProblemFor(route, Option.none(), Option.none()).body();
    }

    private String problemBodyFor(ManagementRoute route, String queryName, String queryValue) {
        return writeProblemFor(route, Option.some(queryName), Option.some(queryValue)).body();
    }

    private RecordingResponseWriter writeProblemFor(ManagementRoute route,
                                                    Option<String> queryName,
                                                    Option<String> queryValue) {
        var recorder = new RecordingResponseWriter();

        ProblemResponses.writeProblem(recorder, causeFrom(route, queryName, queryValue), INSTANCE, REQUEST_ID);

        return recorder;
    }

    private Cause causeFrom(ManagementRoute route) {
        return causeFrom(route, Option.none(), Option.none());
    }

    private Cause causeFrom(ManagementRoute route, String queryName, String queryValue) {
        return causeFrom(route, Option.some(queryName), Option.some(queryValue));
    }

    /// Drives the REAL route handler and returns the cause the routing layer would hand to
    /// `ManagementRouter.writeError`.
    private Cause causeFrom(ManagementRoute route, Option<String> queryName, Option<String> queryValue) {
        var holder = new AtomicReference<Cause>();

        handle(route, queryName, queryValue).onSuccess(value -> Assertions.fail("Route " + route.name()
                                                                               + " must fail, got: " + value))
              .onFailure(holder::set);

        return holder.get();
    }

    private Result<?> handle(ManagementRoute route, Option<String> queryName, Option<String> queryValue) {
        return schemaRoute(route).handler()
                          .handle(requestContext(queryName, queryValue))
                          .await();
    }

    private Route<?> schemaRoute(ManagementRoute route) {
        return routes.routes()
                     .filter(candidate -> candidate.name()
                                                   .equals(route.name()))
                     .findFirst()
                     .orElseThrow();
    }

    private void seed(SchemaStatus status) {
        store.put(SchemaVersionKey.schemaVersionKey(DATASOURCE),
                  SchemaVersionValue.schemaVersionValue(DATASOURCE, 3, "V003__add_index.sql", status, COORDS, OWNER));
    }

    private SchemaVersionValue recorded() {
        return store.get(SchemaVersionKey.schemaVersionKey(DATASOURCE))
                    .filter(SchemaVersionValue.class::isInstance)
                    .map(SchemaVersionValue.class::cast)
                    .or(SchemaRouteStatusTest::noRecord);
    }

    private static SchemaVersionValue noRecord() {
        return Assertions.fail("No schema version record was written");
    }

    private ManageableNode nodeOver(InMemoryKvStore kvStore) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, args) -> switch (method.getName()) {
            case "kvStore" -> kvStore;
            case "apply" -> applyBatch(args);
            default -> throw new UnsupportedOperationException("Not implemented in test proxy: " + method.getName());
        });
    }

    @SuppressWarnings("unchecked")
    private Promise<List<Long>> applyBatch(Object[] args) {
        ((List<KVCommand<AetherKey>>) args[0]).forEach(store::apply);

        return Promise.success(List.of());
    }

    /// The path parameter carries the datasource and the query parameter (when the route declares
    /// one) the version. `matchPath`/`matchQuery` are DEFAULT methods on `RequestContext`, and a
    /// `Proxy` intercepts those too — so this is a real implementation rather than a proxy, letting
    /// the genuine routing logic run. Everything the handler must not touch throws.
    private static RequestContext requestContext(Option<String> queryName, Option<String> queryValue) {
        return new StubRequestContext(QueryParams.queryParams(queryParameters(queryName, queryValue)));
    }

    private static Map<String, List<String>> queryParameters(Option<String> queryName, Option<String> queryValue) {
        return Option.all(queryName, queryValue)
                     .map(SchemaRouteStatusTest::singleParameter)
                     .or(Map.of());
    }

    private static Map<String, List<String>> singleParameter(String name, String value) {
        return Map.of(name, List.of(value));
    }

    private record StubRequestContext(QueryParams queryParams) implements RequestContext {
        @Override
        public List<String> pathParams() {
            return List.of(DATASOURCE);
        }

        @Override
        public Route<?> route() {
            return unsupported("route");
        }

        @Override
        public <T> Result<T> fromJson(TypeToken<T> literal) {
            return unsupported("fromJson");
        }

        @Override
        public HttpHeaders responseHeaders() {
            return unsupported("responseHeaders");
        }

        @Override
        public String requestId() {
            return REQUEST_ID;
        }

        @Override
        public HttpMethod method() {
            return unsupported("method");
        }

        @Override
        public String path() {
            return INSTANCE;
        }

        @Override
        public Headers headers() {
            return unsupported("headers");
        }

        @Override
        public byte[] body() {
            return unsupported("body");
        }

        private static <T> T unsupported(String methodName) {
            throw new UnsupportedOperationException("Not touched by the schema route handlers: " + methodName);
        }
    }

    private static final class RecordingResponseWriter implements ResponseWriter {
        private final AtomicReference<HttpStatus> status = new AtomicReference<>();
        private final AtomicReference<byte[]> body = new AtomicReference<>(new byte[0]);

        @Override
        public void write(HttpStatus status, byte[] body, ContentType contentType) {
            this.status.set(status);
            this.body.set(body);
        }

        @Override
        public ResponseWriter header(String name, String value) {
            return this;
        }

        HttpStatus status() {
            return status.get();
        }

        String body() {
            return new String(body.get(), StandardCharsets.UTF_8);
        }
    }

    private static final class InMemoryKvStore extends KVStore<AetherKey, AetherValue> {
        InMemoryKvStore(MessageRouter router) {
            super(router, stubSerializer(), stubDeserializer());
        }

        void put(AetherKey key, AetherValue value) {
            apply(new KVCommand.Put<>(key, value));
        }

        void apply(KVCommand<AetherKey> command) {
            process(createBatch(List.of(command)));
        }
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
