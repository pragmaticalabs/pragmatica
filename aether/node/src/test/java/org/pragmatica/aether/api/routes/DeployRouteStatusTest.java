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

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.update.CleanupPolicy;
import org.pragmatica.aether.update.Deployment;
import org.pragmatica.aether.update.DeploymentError;
import org.pragmatica.aether.update.DeploymentManager;
import org.pragmatica.aether.update.DeploymentStrategy;
import org.pragmatica.aether.update.HealthThresholds;
import org.pragmatica.aether.update.StrategyConfig;
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
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import io.netty.handler.codec.http.HttpHeaders;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #569 — `POST /api/deploy` answered `500 Internal Server Error` for client errors. Both halves of
/// the funnel were status-blind: the domain causes ([DeploymentError]) were typed but status-less,
/// and the route's own request-validation failures were bare `Causes.cause(...)` constants.
/// `ProblemResponses.resolveStatus` tests `cause instanceof HttpStatusAware` and silently defaults
/// everything else to 500, so "your blueprint is not registered" and "the cluster broke" were
/// indistinguishable on the wire. [DeploymentError] now declares `httpStatus()` (abstract, per-variant
/// overrides) and [DeployRouteError] replaces the bare constants.
///
/// The proof has to be taken at the ROUTE level, not at the cause level: asserting that
/// `DeploymentError.BlueprintNotFound.httpStatus() == NOT_FOUND` proves only that the record was typed
/// correctly. What actually breaks in production is a hop between the raising site and the response
/// funnel that RE-WRAPS the cause — a composite, an `HttpError` re-wrap or a `mapError` — because any
/// of those erase the mixin and silently restore the 500. So every test here drives the real `Route`
/// handler obtained from `DeployRoutes.routes()` and then feeds the emerging cause through the exact
/// `ProblemResponses.writeProblem` call `ManagementRouter.writeError` makes.
///
/// That methodology earned its keep immediately: see [StrategyRejection] below, where the route-level
/// drive shows the 400 does NOT reach the wire because `Result.all` in `buildParsedRequest` wraps the
/// cause in a `CompositeCause`. A cause-level assertion would have passed and shipped the defect.
///
/// Note on the "unwrapped" assertions: `BlueprintPublishConflictStatusTest` can use `isSameAs` because
/// its fixture SUPPLIES the cause instance to a stubbed service. `DeploymentNotFound` is minted inside
/// `DeployRoutes` (it names the deployment id), so `isEqualTo` against a freshly built expected record
/// is the faithful stand-in and is in fact stricter: records compare by value, so a wrapper, a
/// re-typed cause or a drifted message all fail, and the message content is pinned at the same time.
class DeployRouteStatusTest {
    private static final String COORDS = "org.example:orders-app:1.0.0";
    private static final String DEPLOYMENT_ID = "deploy-7f3a";
    private static final String START_INSTANCE = "/api/deploy";
    private static final String STATUS_INSTANCE = "/api/deploy/" + DEPLOYMENT_ID;
    private static final String REQUEST_ID = "req-1";

    private static final Cause BLUEPRINT_NOT_FOUND = DeploymentError.BlueprintNotFound.blueprintNotFound(COORDS);

    private static final Cause CONSENSUS_FAILURE = DeploymentError.ConsensusFailure.consensusFailure(Causes.cause(
        "quorum not reached"));

    private static final Cause NOT_CALLED = Causes.cause("DeploymentManager.start must not be reached by this request");

    /// A well-formed body: `instances`/`thresholds`/`cleanupPolicy`/`canary`/`blueGreen`/`rolling` are
    /// absent JSON fields, which the wire deserializer materializes as `null`. ROLLING is the strategy
    /// with no required sub-config, so parsing succeeds and the failure can only come from the manager.
    private static final DeployRoutes.DeployRequest ROLLING_REQUEST = new DeployRoutes.DeployRequest(COORDS,
                                                                                                     "rolling",
                                                                                                     null,
                                                                                                     null,
                                                                                                     null,
                                                                                                     null,
                                                                                                     null,
                                                                                                     null);

    private static final DeployRoutes.DeployRequest NO_BLUEPRINT_REQUEST = new DeployRoutes.DeployRequest(null,
                                                                                                          "rolling",
                                                                                                          null,
                                                                                                          null,
                                                                                                          null,
                                                                                                          null,
                                                                                                          null,
                                                                                                          null);

    private static final DeployRoutes.DeployRequest UNKNOWN_STRATEGY_REQUEST = new DeployRoutes.DeployRequest(COORDS,
                                                                                                              "sideways",
                                                                                                              null,
                                                                                                              null,
                                                                                                              null,
                                                                                                              null,
                                                                                                              null,
                                                                                                              null);

    private static final DeployRoutes.DeployRequest NO_STRATEGY_REQUEST = new DeployRoutes.DeployRequest(COORDS,
                                                                                                         null,
                                                                                                         null,
                                                                                                         null,
                                                                                                         null,
                                                                                                         null,
                                                                                                         null,
                                                                                                         null);

    private static final DeployRoutes.DeployRequest BAD_CANARY_STAGE_REQUEST = new DeployRoutes.DeployRequest(COORDS,
                                                                                                              "canary",
                                                                                                              null,
                                                                                                              null,
                                                                                                              null,
                                                                                                              Map.of("stages",
                                                                                                                     List.of(Map.of("trafficPercent",
                                                                                                                                    500,
                                                                                                                                    "observationMinutes",
                                                                                                                                    10))),
                                                                                                              null,
                                                                                                              null);

    /// THE variant behind #569: `POST /api/deploy` naming a blueprint the cluster does not hold. The
    /// caller addressed a resource that does not exist, which is 404, not "the cluster broke".
    @Nested
    class BlueprintMissing {
        @Test
        void startRoute_respondsNotFound_whenBlueprintIsNotRegistered() {
            assertThat(statusOf(startCauseFrom(BLUEPRINT_NOT_FOUND, ROLLING_REQUEST), START_INSTANCE)).as(
                "an unregistered blueprint is a missing resource, not a server fault")
                      .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void startRoute_propagatesCauseUnwrapped_whenBlueprintIsNotRegistered() {
            assertThat(startCauseFrom(BLUEPRINT_NOT_FOUND, ROLLING_REQUEST)).as(
                "any wrapping on the way out erases the HttpStatusAware mixin and restores the 500")
                      .isEqualTo(DeploymentError.BlueprintNotFound.blueprintNotFound(COORDS));
        }

        @Test
        void problemBody_namesBlueprintCoordinates_whenBlueprintIsNotRegistered() {
            assertThat(bodyOf(startCauseFrom(BLUEPRINT_NOT_FOUND, ROLLING_REQUEST), START_INSTANCE)).contains(COORDS)
                      .contains("Blueprint not found")
                      .contains("404");
        }
    }

    /// `GET /api/deploy/{id}` for an id the manager does not know. The cause is minted per request via
    /// `DeploymentNotFound.deploymentNotFound(id)` — it used to be an id-less shared constant, so the
    /// operator could not tell WHICH deployment the 404 referred to.
    @Nested
    class DeploymentMissing {
        @Test
        void statusRoute_respondsNotFoundAndNamesDeploymentId_whenDeploymentIsUnknown() {
            var cause = statusCauseFrom(DEPLOYMENT_ID);

            assertThat(statusOf(cause, STATUS_INSTANCE)).as("an unknown deployment id is a missing resource")
                      .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(bodyOf(cause, STATUS_INSTANCE)).as("the 404 must say WHICH deployment was not found")
                      .contains(DEPLOYMENT_ID)
                      .contains("Deployment not found")
                      .contains("404");
        }
    }

    /// A body missing a required field is the caller's error. `parseBlueprint` runs before
    /// `buildParsedRequest`, so its cause reaches the funnel through a plain `flatMap` and survives.
    @Nested
    class MalformedRequest {
        @Test
        void startRoute_respondsBadRequest_whenBlueprintFieldIsMissing() {
            var cause = startCauseFrom(NOT_CALLED, NO_BLUEPRINT_REQUEST);

            assertThat(cause).as("any wrapping on the way out erases the HttpStatusAware mixin")
                      .isEqualTo(DeployRouteError.MISSING_BLUEPRINT);
            assertThat(statusOf(cause, START_INSTANCE)).as("a missing required field is a malformed request")
                      .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    /// An unrecognized `strategy` is equally the caller's error and [DeployRouteError#INVALID_STRATEGY]
    /// declares 400. Delivering it required a production change beyond typing the cause: `parseStrategy`
    /// was one of four results handed to `Result.all(...)` in `buildParsedRequest`, and `Result.all`
    /// replaces the emerging cause with `Causes.composite(...)` whenever ANY input failed — even a single
    /// one. `CompositeCause` extends `Cause` only, so the mixin was erased and `resolveStatus` fell back
    /// to 500 despite the cause being typed correctly.
    ///
    /// That is exactly the re-wrapping hop the class doc warns about, and it is why these tests drive the
    /// route rather than the cause: a cause-level assertion passed throughout while the wire answered 500.
    /// `buildParsedRequest` is now a sequential first-failure-wins chain, which preserves the cause
    /// identity. `MISSING_STRATEGY` and `Version` parse failures travelled that same erased path and are
    /// exercised below rather than left to mechanism.
    @Nested
    class StrategyRejection {
        @Test
        void startRoute_respondsBadRequest_whenStrategyIsUnrecognized() {
            assertThat(statusOf(startCauseFrom(NOT_CALLED, UNKNOWN_STRATEGY_REQUEST), START_INSTANCE)).as(
                "an unrecognized strategy is the caller's error, not a cluster fault")
                      .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        /// The regression that matters: the cause must arrive UNWRAPPED. `Result.all` reaching this path
        /// again would restore the 500 while every cause-level assertion kept passing.
        @Test
        void startRoute_propagatesCauseUnwrapped_whenStrategyIsUnrecognized() {
            var cause = startCauseFrom(NOT_CALLED, UNKNOWN_STRATEGY_REQUEST);

            assertThat(cause).as("an accumulating combinator here erases the HttpStatusAware mixin")
                      .isEqualTo(DeployRouteError.INVALID_STRATEGY)
                      .isNotInstanceOf(Causes.CompositeCause.class);
        }

        @Test
        void problemBody_namesTheAcceptedStrategies_soTheCallerCanCorrectTheRequest() {
            assertThat(bodyOf(startCauseFrom(NOT_CALLED, UNKNOWN_STRATEGY_REQUEST), START_INSTANCE)).contains("canary")
                      .contains("blue_green")
                      .contains("rolling")
                      .contains("400");
        }

        /// The sibling cause that travelled the same erased path. Asserted rather than left to
        /// "mechanism": both reached the funnel through the one `Result.all` call, so both regress
        /// together if it returns.
        @Test
        void startRoute_respondsBadRequestUnwrapped_whenStrategyFieldIsMissing() {
            var cause = startCauseFrom(NOT_CALLED, NO_STRATEGY_REQUEST);

            assertThat(cause).isEqualTo(DeployRouteError.MISSING_STRATEGY)
                      .isNotInstanceOf(Causes.CompositeCause.class);
            assertThat(statusOf(cause, START_INSTANCE)).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    /// A canary stage carrying an out-of-range `trafficPercent` reached the funnel through a SECOND
    /// accumulating combinator — `Result.allOf` in `parseCanaryConfig`, which composites exactly like
    /// `Result.all`. Fixing only the first site would have left this one answering 500, which is why
    /// the erasure was chased to every accumulating call on the request path rather than just the one
    /// the original report happened to hit.
    @Nested
    class CanaryStageRejection {
        @Test
        void startRoute_respondsBadRequest_whenTrafficPercentIsOutOfRange() {
            assertThat(statusOf(startCauseFrom(NOT_CALLED, BAD_CANARY_STAGE_REQUEST), START_INSTANCE)).as(
                "an out-of-range stage value is the caller's error, not a cluster fault")
                      .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void startRoute_propagatesCauseUnwrapped_whenTrafficPercentIsOutOfRange() {
            assertThat(startCauseFrom(NOT_CALLED, BAD_CANARY_STAGE_REQUEST)).as("Result.allOf composites identically to Result.all")
                      .isNotInstanceOf(Causes.CompositeCause.class);
        }

        @Test
        void problemBody_explainsTheAcceptedRange_whenTrafficPercentIsOutOfRange() {
            assertThat(bodyOf(startCauseFrom(NOT_CALLED, BAD_CANARY_STAGE_REQUEST), START_INSTANCE)).contains("between 1 and 100")
                      .contains("400");
        }
    }

    /// Load-bearing negative control. Without it, the 404s above could equally be explained by the
    /// change having blanket-downgraded every failure into a 4xx. A genuine cluster fault must still
    /// answer 500 — that is what makes the other codes informative.
    @Nested
    class GenuineServerFault {
        @Test
        void startRoute_respondsServerError_whenConsensusFails() {
            assertThat(statusOf(startCauseFrom(CONSENSUS_FAILURE, ROLLING_REQUEST), START_INSTANCE)).as(
                "a consensus failure IS a server fault and must not be downgraded to a client error")
                      .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        void problemStatus_fallsBackToServerError_forCauseWithoutStatusMixin() {
            var recorder = new RecordingResponseWriter();

            ProblemResponses.writeProblem(recorder, Causes.cause("plain failure"), START_INSTANCE, REQUEST_ID);

            assertThat(recorder.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // --- helpers ---
    /// Runs the emerging cause through the exact funnel call `ManagementRouter.writeError` makes.
    private static HttpStatus statusOf(Cause cause, String instance) {
        return writeProblem(cause, instance).status();
    }

    private static String bodyOf(Cause cause, String instance) {
        return writeProblem(cause, instance).body();
    }

    private static RecordingResponseWriter writeProblem(Cause cause, String instance) {
        var recorder = new RecordingResponseWriter();

        ProblemResponses.writeProblem(recorder, cause, instance, REQUEST_ID);

        return recorder;
    }

    private static Cause startCauseFrom(Cause managerFailure, DeployRoutes.DeployRequest request) {
        return causeFrom(ManagementRoute.DEPLOY_START,
                         new StubDeploymentManager(managerFailure),
                         new StubRequestContext(List.of(), Option.some(request), START_INSTANCE));
    }

    private static Cause statusCauseFrom(String deploymentId) {
        return causeFrom(ManagementRoute.DEPLOY_STATUS,
                         new StubDeploymentManager(NOT_CALLED),
                         new StubRequestContext(List.of(deploymentId), Option.none(), STATUS_INSTANCE));
    }

    /// Drives the REAL route handler and returns the cause the routing layer would hand to
    /// `ManagementRouter.writeError`.
    private static Cause causeFrom(ManagementRoute route, DeploymentManager manager, RequestContext context) {
        var holder = new AtomicReference<Cause>();

        deployRoute(route, manager).handler()
                                   .handle(context)
                                   .await()
                                   .onSuccess(value -> Assertions.fail("Route " + route.name()
                                                                       + " must fail, got: " + value))
                                   .onFailure(holder::set);

        return holder.get();
    }

    private static Route<?> deployRoute(ManagementRoute route, DeploymentManager manager) {
        return DeployRoutes.deployRoutes(() -> nodeOver(manager))
                           .routes()
                           .filter(candidate -> candidate.name()
                                                         .equals(route.name()))
                           .findFirst()
                           .orElseThrow();
    }

    /// `DeployRoutes` resolves its collaborator through `nodeSupplier.get().deploymentManager()`;
    /// everything else on the node must stay untouched.
    private static ManageableNode nodeOver(DeploymentManager manager) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> "deploymentManager".equals(method.getName())
                                                                         ? manager
                                                                         : unsupported(method.getName()));
    }

    private static <T> T unsupported(String methodName) {
        throw new UnsupportedOperationException("Not touched by the deploy route handlers: " + methodName);
    }

    /// Fails every operation the tests exercise with the supplied cause, and reports every deployment
    /// id as unknown. `status` returning `none()` is precisely what `getDeployment` converts into
    /// `DeploymentNotFound(id)`.
    private record StubDeploymentManager(Cause failure) implements DeploymentManager {
        @Override
        public Promise<Unit> activate() {
            return unsupported("activate");
        }

        @Override
        public Promise<Unit> deactivate() {
            return unsupported("deactivate");
        }

        @Override
        public boolean isActive() {
            return unsupported("isActive");
        }

        @Override
        public Result<Deployment> start(String blueprintId,
                                        Version newVersion,
                                        DeploymentStrategy strategy,
                                        StrategyConfig config,
                                        HealthThresholds thresholds,
                                        CleanupPolicy cleanupPolicy,
                                        int instances) {
            return failure.result();
        }

        @Override
        public Result<Deployment> promote(String deploymentId) {
            return unsupported("promote");
        }

        @Override
        public Result<Deployment> rollback(String deploymentId) {
            return unsupported("rollback");
        }

        @Override
        public Result<Deployment> complete(String deploymentId) {
            return unsupported("complete");
        }

        @Override
        public Option<Deployment> status(String deploymentId) {
            return Option.none();
        }

        @Override
        public List<Deployment> list() {
            return List.of();
        }

        @Override
        public Option<ActiveRouting> activeRouting(ArtifactBase artifactBase) {
            return Option.none();
        }
    }

    /// `DEPLOY_START` reads its payload through `fromJson`; `DEPLOY_STATUS` reads the id through
    /// `matchPath`, which is a DEFAULT method over `pathParams()`. A `Proxy` intercepts default methods
    /// too, so this is a real implementation rather than a proxy, letting the genuine routing logic
    /// run. Everything the handlers must not touch throws.
    private record StubRequestContext(List<String> pathParams,
                                      Option<DeployRoutes.DeployRequest> requestBody,
                                      String path) implements RequestContext {
        @Override
        public QueryParams queryParams() {
            return QueryParams.queryParams(Map.of());
        }

        @Override
        public Route<?> route() {
            return unsupported("route");
        }

        @Override
        public <T> Result<T> fromJson(TypeToken<T> literal) {
            return requestBody.toResult(NO_BODY)
                              .map(StubRequestContext::asRequested);
        }

        @SuppressWarnings("unchecked")
        private static <T> T asRequested(DeployRoutes.DeployRequest request) {
            return (T) request;
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
        public Headers headers() {
            return unsupported("headers");
        }

        @Override
        public byte[] body() {
            return unsupported("body");
        }

        private static final Cause NO_BODY = Causes.cause("This request carries no body");
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
}
