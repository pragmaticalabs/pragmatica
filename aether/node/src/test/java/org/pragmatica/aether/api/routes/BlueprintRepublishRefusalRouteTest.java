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
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaReach;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.blueprint.Blueprint;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.deployment.cluster.PublishedBlueprint;
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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #834 acceptance: the republish guard's refusal reaches the wire as a 409 with the operator-actionable problem body,
/// through BOTH blueprint entry points (`/deploy` and `/publish`). The guard itself is pinned in
/// `BlueprintPublishOwnershipTest`; this pins the hop after it: `SliceRoutes` must hand the typed
/// `SchemaError.RepublishWouldReapply` to the response funnel UNWRAPPED, because `ProblemResponses.resolveStatus`
/// answers 500 for any cause that is not `HttpStatusAware` (a composite, re-wrapped or re-typed cause).
class BlueprintRepublishRefusalRouteTest {
    private static final String COORDS = "org.example:orders-app:1.0.0:blueprint";
    private static final String DATASOURCE = "database";
    private static final Cause REFUSAL = SchemaError.RepublishWouldReapply.republishWouldReapply(DATASOURCE,
                                                                                                 SchemaReach.UNDONE,
                                                                                                 1,
                                                                                                 List.of(2));

    @ParameterizedTest
    @EnumSource(value = ManagementRoute.class, names = {"BLUEPRINT_DEPLOY", "BLUEPRINT_PUBLISH_ARTIFACT"})
    void route_respondsConflictWithTypedCauseAndProblemBody_whenRepublishWouldReapply(ManagementRoute route) {
        var cause = causeFrom(route);
        var recorder = new RecordingResponseWriter();

        ProblemResponses.writeProblem(recorder, cause, "/api/v1/blueprints", "req-1");

        assertThat(cause).as("any wrapping on the way out erases the HttpStatusAware mixin")
                         .isSameAs(REFUSAL)
                         .isInstanceOf(SchemaError.RepublishWouldReapply.class);
        assertThat(recorder.status.get()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(recorder.body()).contains("409")
                                   .contains(DATASOURCE)
                                   .contains("by an operator undo")
                                   .contains("[2]")
                                   .contains("force");
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
        return SliceRoutes.sliceRoutes(BlueprintRepublishRefusalRouteTest::nodeWithRefusingBlueprintService)
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
    /// `SchemaVersionKey(DATASOURCE)` record is already owned by `billing-app`.
    private static BlueprintService refusingBlueprintService() {
        return new BlueprintService() {
            @Override public Promise<PublishedBlueprint> publish(String dsl) {
                return REFUSAL.promise();
            }

            @Override public Promise<PublishedBlueprint> publishFromArtifact(String artifactCoords) {
                return REFUSAL.promise();
            }

            @Override public Promise<PublishedBlueprint> publishFromArtifact(String artifactCoords,
                                                                            boolean registerOnly) {
                return REFUSAL.promise();
            }

            @Override public Option<ExpandedBlueprint> get(BlueprintId id) {
                return Option.none();
            }

            @Override
            public Option<DeploymentOutcomeValue> attributedOutcome(BlueprintId id) {
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
                return REFUSAL.result();
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
                                                                                 ? Result.success(new SliceRoutes.BlueprintDeployRequest(COORDS, Option.none()))
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
