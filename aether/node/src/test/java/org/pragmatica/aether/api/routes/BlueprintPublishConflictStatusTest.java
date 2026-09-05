// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.deployment.cluster.BlueprintService;
import org.pragmatica.aether.deployment.schema.SchemaError;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.blueprint.Blueprint;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.routing.RequestContext;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #542 — the deploy-time single-migrator gate is only useful if the operator is told the publish
/// was REFUSED, not that the node broke. `SchemaError.DatasourceOwnershipConflict` declares HTTP 409
/// via `HttpStatusAware`, but the declaration only reaches the wire if every hop between
/// `BlueprintService` and the response funnel propagates the cause UNWRAPPED:
/// `ProblemResponses.resolveStatus` tests `cause instanceof HttpStatusAware` and silently defaults
/// to 500 for anything else — a composite, a re-wrapped, or a re-typed cause all downgrade to a
/// server error indistinguishable from a genuine fault.
///
/// This drives the real `SliceRoutes` handler through the real `Route` and then through the exact
/// funnel call `ManagementRouter.writeError` makes, so a future `map`/`flatMap` that re-wraps the
/// cause fails here rather than in production.
class BlueprintPublishConflictStatusTest {
    private static final String COORDS = "org.example:orders-app:1.0.0:blueprint";
    private static final String DATASOURCE = "database";
    private static final BlueprintId CURRENT_OWNER = BlueprintId.blueprintId("org.example:billing-app:1.0.0").unwrap();
    private static final BlueprintId REJECTED = BlueprintId.blueprintId("org.example:orders-app:1.0.0").unwrap();
    private static final Cause CONFLICT = SchemaError.DatasourceOwnershipConflict.datasourceOwnershipConflict(DATASOURCE,
                                                                                                               CURRENT_OWNER,
                                                                                                               REJECTED);

    /// Both blueprint entry points run the gate — `/api/v1/blueprints/deploy` and
    /// `/api/v1/blueprints/publish` share `publishFromArtifact`, so both must answer 409.
    @Test
    void deployRoute_respondsConflict_whenDatasourceIsOwnedByAnotherBlueprint() {
        assertRespondsConflict(ManagementRoute.BLUEPRINT_DEPLOY);
    }

    @Test
    void publishRoute_respondsConflict_whenDatasourceIsOwnedByAnotherBlueprint() {
        assertRespondsConflict(ManagementRoute.BLUEPRINT_PUBLISH_ARTIFACT);
    }

    @Test
    void deployRoute_propagatesCauseUnwrapped_whenDatasourceIsOwnedByAnotherBlueprint() {
        assertPropagatesUnwrapped(ManagementRoute.BLUEPRINT_DEPLOY);
    }

    @Test
    void publishRoute_propagatesCauseUnwrapped_whenDatasourceIsOwnedByAnotherBlueprint() {
        assertPropagatesUnwrapped(ManagementRoute.BLUEPRINT_PUBLISH_ARTIFACT);
    }

    private static void assertRespondsConflict(ManagementRoute route) {
        var recorder = new RecordingResponseWriter();

        ProblemResponses.writeProblem(recorder, causeFrom(route), "/api/v1/blueprints", "req-1");

        assertThat(recorder.status.get()).as("a rejected publish is a state conflict, not a server fault")
                                         .isEqualTo(HttpStatus.CONFLICT);
    }

    private static void assertPropagatesUnwrapped(ManagementRoute route) {
        assertThat(causeFrom(route)).as("any wrapping on the way out erases the HttpStatusAware mixin")
                                    .isSameAs(CONFLICT);
    }

    @Test
    void problemBody_namesDatasourceAndIncumbentOwner_soTheOperatorCanAct() {
        var recorder = new RecordingResponseWriter();

        ProblemResponses.writeProblem(recorder,
                                      causeFrom(ManagementRoute.BLUEPRINT_PUBLISH_ARTIFACT),
                                      "/api/v1/blueprints",
                                      "req-1");

        assertThat(recorder.body()).contains(DATASOURCE)
                                   .contains(CURRENT_OWNER.asString())
                                   .contains("409");
    }

    /// Negative control: an ordinary cause with no status mixin still funnels to 500, so the 409
    /// above is produced by the mixin rather than by the funnel defaulting everything to CONFLICT.
    @Test
    void problemStatus_fallsBackToServerError_forCauseWithoutStatusMixin() {
        var recorder = new RecordingResponseWriter();

        ProblemResponses.writeProblem(recorder,
                                      Causes.cause("plain failure"),
                                      "/api/v1/blueprints",
                                      "req-1");

        assertThat(recorder.status.get()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // --- helpers ---

    /// Runs the REAL route handler over a `BlueprintService` whose gate refused the publish, and
    /// returns the cause the routing layer would hand to `ManagementRouter.writeError`.
    private static Cause causeFrom(ManagementRoute route) {
        var holder = new AtomicReference<Cause>();

        publishRoute(route).handler()
                           .handle(requestContext())
                           .await()
                           .onSuccess(value -> fail("Publish must be refused, got: " + value))
                           .onFailure(holder::set);

        return holder.get();
    }

    private static Route<?> publishRoute(ManagementRoute route) {
        return SliceRoutes.sliceRoutes(BlueprintPublishConflictStatusTest::nodeWithRefusingBlueprintService)
                          .routes()
                          .filter(candidate -> candidate.name()
                                                        .equals(route.name()))
                          .findFirst()
                          .orElseThrow();
    }

    private static ManageableNode nodeWithRefusingBlueprintService() {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> "blueprintService".equals(method.getName())
                                                                         ? refusingBlueprintService()
                                                                         : unsupported(method.getName()));
    }

    private static Object unsupported(String methodName) {
        throw new UnsupportedOperationException("Not implemented in test proxy: " + methodName);
    }

    /// The gate lives inside `publishFromArtifact`; this stands in for a store whose
    /// `SchemaVersionKey("database")` record is already owned by `billing-app`.
    private static BlueprintService refusingBlueprintService() {
        return new BlueprintService() {
            @Override public Promise<ExpandedBlueprint> publish(String dsl) {
                return CONFLICT.promise();
            }

            @Override public Promise<ExpandedBlueprint> publishFromArtifact(String artifactCoords) {
                return CONFLICT.promise();
            }

            @Override public Promise<ExpandedBlueprint> publishFromArtifact(String artifactCoords,
                                                                            boolean registerOnly) {
                return CONFLICT.promise();
            }

            @Override public Option<ExpandedBlueprint> get(BlueprintId id) {
                return Option.none();
            }

            @Override public Option<DeploymentOutcomeValue> outcome(BlueprintId id) {
                return Option.none();
            }

            @Override public List<ExpandedBlueprint> list() {
                return List.of();
            }

            @Override public Promise<Unit> delete(BlueprintId id) {
                return Promise.unitPromise();
            }

            @Override public Result<Blueprint> validate(String dsl) {
                return CONFLICT.result();
            }
        };
    }

    /// The body route reads its payload through `fromJson`, wrapped by the `jsonBody` DEFAULT
    /// method (#772). A proxy's `InvocationHandler` intercepts every interface call, default
    /// methods included, so any other default method (`jsonBody` today, whichever comes next)
    /// must be forwarded to its real body via [InvocationHandler#invokeDefault] rather than
    /// enumerated by name here — the point of this test double is that only `fromJson` is
    /// stubbed, everything else on `RequestContext` behaves exactly as it does in production.
    private static RequestContext requestContext() {
        return (RequestContext) Proxy.newProxyInstance(RequestContext.class.getClassLoader(),
                                                       new Class[]{RequestContext.class},
                                                       (proxy, method, args) -> "fromJson".equals(method.getName())
                                                                                 ? Result.success(new SliceRoutes.BlueprintDeployRequest(COORDS))
                                                                                 : method.isDefault()
                                                                                   ? InvocationHandler.invokeDefault(proxy, method, args)
                                                                                   : unsupported(method.getName()));
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

        String body() {
            return new String(body.get(), StandardCharsets.UTF_8);
        }
    }
}
