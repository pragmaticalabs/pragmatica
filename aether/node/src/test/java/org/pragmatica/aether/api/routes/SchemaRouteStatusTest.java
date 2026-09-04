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

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.deployment.schema.SchemaError;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
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
    private static final Version SLICE_VERSION = new Version(1, 0, 0, "");
    private static final NodeId NODE = new NodeId("node-a");

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

        /// #724: a migration that never dispatched (PENDING) has no other lever short of a redeploy
        /// to re-trigger it — the retry guard now accepts PENDING alongside FAILED.
        @Test
        void retryRoute_succeeds_whenSchemaIsPending() {
            seed(SchemaStatus.PENDING);
            handle(ManagementRoute.SCHEMA_RETRY, Option.none(), Option.none()).onFailure(cause -> Assertions.fail("Retry against a PENDING record must succeed (#724): " + cause.message()));
        }
    }

    /// #760: `heldSlices` makes a schema hold visible on the management API, without reaching for
    /// DEBUG logs. Ownership matches on the base artifact coordinates (group:artifact, stripped of
    /// version) — the same rule `ClusterDeploymentState` uses to decide whether a slice's blueprint
    /// is the one a schema record blocks.
    ///
    /// #760 review BLOCKING 1: `heldSlices` used to scan ownership alone (`SliceTargetKey`/
    /// `SliceTargetValue`) with no per-node state check at all, so `seedSliceTarget` below now also
    /// plants the LIVE `SliceNodeKey`/`SliceNodeValue` state the fixed implementation actually reads —
    /// LOADED for the tests below, which model a slice genuinely waiting at the gate.
    @Nested
    class HeldSlices {
        private static final String HELD_SLICE = "org.example:orders-worker";
        private static final String UNRELATED_SLICE = "org.other:unrelated-worker";

        @Test
        void statusRoute_listsOwnedSlice_whenSchemaIsBlocking() {
            seed(SchemaStatus.PENDING);
            seedSliceTarget(HELD_SLICE, OWNER);

            assertThat(successResponseFor(ManagementRoute.SCHEMA_STATUS_ONE).heldSlices()).containsExactly(HELD_SLICE);
        }

        @Test
        void statusRoute_matchesOnBase_ignoringOwningBlueprintVersion() {
            seed(SchemaStatus.MIGRATING);
            var differentVersionOfSameBase = BlueprintId.blueprintId("org.example:orders-app:9.9.9").unwrap();
            seedSliceTarget(HELD_SLICE, differentVersionOfSameBase);

            assertThat(successResponseFor(ManagementRoute.SCHEMA_STATUS_ONE).heldSlices()).as("ownership matches the base artifact, not the exact blueprint version")
                      .containsExactly(HELD_SLICE);
        }

        @Test
        void statusRoute_omitsUnrelatedSlice_whenSchemaIsBlocking() {
            seed(SchemaStatus.FAILED);
            var unrelatedOwner = BlueprintId.blueprintId("org.other:unrelated-app:1.0.0").unwrap();
            seedSliceTarget(UNRELATED_SLICE, unrelatedOwner);

            assertThat(successResponseFor(ManagementRoute.SCHEMA_STATUS_ONE).heldSlices()).isEmpty();
        }

        @Test
        void statusRoute_reportsNoHeldSlices_whenSchemaIsNotBlocking() {
            seed(SchemaStatus.COMPLETED);
            seedSliceTarget(HELD_SLICE, OWNER);

            assertThat(successResponseFor(ManagementRoute.SCHEMA_STATUS_ONE).heldSlices()).as("a COMPLETED record holds nothing regardless of ownership")
                      .isEmpty();
        }

        /// #760 review BLOCKING 1 pinning test. Before the fix, `heldSlices` matched ownership alone
        /// with no per-node state check, so an ACTIVE slice — one that already passed the activation
        /// gate and has no transition path back through LOADED — was reported as held whenever its
        /// record carried a blocking status. Seeded from FAILED rather than COMPLETED or PENDING: a
        /// COMPLETED record with a live ACTIVE slice is refused outright by `/migrate`, and (#760/#724
        /// review round 2 item l) so is a PENDING one ([ReactivationGuard]) — so the only way `/migrate`
        /// can still legitimately land a blocking status next to an already-ACTIVE slice is a record
        /// that is neither COMPLETED nor PENDING, i.e. FAILED — exactly what this seeds, mirroring a
        /// slice that activated on an earlier schema version while this datasource's own migration
        /// attempt failed.
        @Test
        void statusRoute_omitsActiveSlice_whenMigrateReArmsFromFailed() {
            seed(SchemaStatus.FAILED);
            seedActiveSlice(HELD_SLICE, OWNER);

            handle(ManagementRoute.SCHEMA_MIGRATE, Option.none(), Option.none()).onFailure(cause -> Assertions.fail("migrate from FAILED must succeed: "
                                                                                                                    + cause.message()));

            assertThat(successResponseFor(ManagementRoute.SCHEMA_STATUS_ONE).heldSlices()).as("an ACTIVE slice already passed the gate and has no transition path back through LOADED")
                      .isEmpty();
        }

        /// #760 review round 2 item a pinning test — the exact divergent state the review named:
        /// `heldSlices` and the FSM gate (`blocksSliceActivation`/`blockingSchemaRecords`) resolved
        /// `schemaRequired` through two different paths (the gate via its in-memory `blueprints` map,
        /// the route not at all), so a slice owned by a `schema_required = false` blueprint could be
        /// reported held on the management API even though the gate itself never blocked it. Before
        /// the fix this asserted `containsExactly(HELD_SLICE)` — the divergence — instead of empty.
        @Test
        void statusRoute_omitsSlice_whenOwningBlueprintDoesNotRequireSchema() {
            seedOwningBlueprintWithoutSchemaRequirement(OWNER);
            seed(SchemaStatus.PENDING);
            seedSliceTarget(HELD_SLICE, OWNER);

            assertThat(successResponseFor(ManagementRoute.SCHEMA_STATUS_ONE).heldSlices()).as("schema_required = false means the gate never blocks this slice")
                      .isEmpty();
        }
    }

    /// #760 review BLOCKING 1's 409 decision: `/migrate` writing MIGRATING has no orchestrator effect
    /// by itself (only a PENDING record's Put dispatches `SchemaOrchestratorService.migrateIfNeeded`),
    /// so re-arming a COMPLETED record whose owning blueprint has live ACTIVE slices has no functional
    /// benefit and one real hazard: MIGRATING has no automatic clearing path, so the next slice
    /// instance to reach LOADED (scale-up, rolling redeploy, rejoining node) is held on a record the
    /// operator re-armed themselves. Refused (409) rather than allowed; a COMPLETED record with zero
    /// live ACTIVE slices is unaffected.
    @Nested
    class ReactivationGuard {
        private static final String ACTIVE_SLICE = "org.example:orders-worker";

        @Test
        void migrateRoute_respondsConflict_whenCompletedRecordHasActiveSlices() {
            seed(SchemaStatus.COMPLETED);
            seedActiveSlice(ACTIVE_SLICE, OWNER);

            assertThat(statusFor(ManagementRoute.SCHEMA_MIGRATE)).as("re-arming a serving schema would strand the next LOADED slice with no automatic recovery")
                      .isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        void migrateRoute_propagatesCauseUnwrapped_whenCompletedRecordHasActiveSlices() {
            seed(SchemaStatus.COMPLETED);
            seedActiveSlice(ACTIVE_SLICE, OWNER);

            assertThat(causeFrom(ManagementRoute.SCHEMA_MIGRATE)).isEqualTo(SchemaRouteError.SchemaAlreadyServing.schemaAlreadyServing(DATASOURCE, 1));
        }

        @Test
        void problemBody_namesActiveSliceCount_whenCompletedRecordHasActiveSlices() {
            seed(SchemaStatus.COMPLETED);
            seedActiveSlice(ACTIVE_SLICE, OWNER);

            assertThat(problemBodyFor(ManagementRoute.SCHEMA_MIGRATE)).contains(DATASOURCE)
                      .contains("already COMPLETED and serving")
                      .contains("409");
        }

        @Test
        void migrateRoute_succeeds_whenCompletedRecordHasNoActiveSlices() {
            seed(SchemaStatus.COMPLETED);

            handle(ManagementRoute.SCHEMA_MIGRATE, Option.none(), Option.none()).onFailure(cause -> Assertions.fail("A COMPLETED record with no active slices must be allowed to re-migrate: "
                                                                                                                    + cause.message()));
        }

        @Test
        void migrateRoute_succeeds_whenActiveSlicesAreOwnedByAnotherBlueprint() {
            seed(SchemaStatus.COMPLETED);
            var otherOwner = BlueprintId.blueprintId("org.other:unrelated-app:1.0.0").unwrap();
            seedActiveSlice(ACTIVE_SLICE, otherOwner);

            handle(ManagementRoute.SCHEMA_MIGRATE, Option.none(), Option.none()).onFailure(cause -> Assertions.fail("An active slice owned by a different blueprint must not block re-migration: "
                                                                                                                    + cause.message()));
        }

        /// #760 review round 2 item b pinning test — a slice mid-activation is already occupying the
        /// datasource it is about to serve from; before the fix `countIfActiveAndOwnedBy` matched only
        /// `SliceState.ACTIVE`, so a re-arm raced a slice seconds from going live instead of being
        /// refused like the ACTIVE case above.
        @Test
        void migrateRoute_respondsConflict_whenCompletedRecordHasActivatingSlices() {
            seed(SchemaStatus.COMPLETED);
            seedSliceInState(ACTIVE_SLICE, OWNER, SliceState.ACTIVATING);

            assertThat(statusFor(ManagementRoute.SCHEMA_MIGRATE)).as("a slice mid-activation is about to serve the same datasource, same as one already ACTIVE")
                      .isEqualTo(HttpStatus.CONFLICT);
        }

        /// #760/#724 review round 2 item l pinning test — before the fix, `guardReactivation` special-
        /// cased only COMPLETED and let every other status (PENDING included) fall through to
        /// `writeMigratingStatus`, silently re-arming a PENDING record to MIGRATING with no dispatch
        /// effect of its own and the same missing-clearing-path hazard `SchemaAlreadyServing` guards
        /// against above. No active-slice setup is needed: PENDING is refused unconditionally.
        @Test
        void migrateRoute_respondsConflict_whenRecordIsPending() {
            seed(SchemaStatus.PENDING);

            assertThat(statusFor(ManagementRoute.SCHEMA_MIGRATE)).as("a PENDING record already dispatches on its own Put; re-arming it gains nothing and strands the record")
                      .isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        void migrateRoute_propagatesCauseUnwrapped_whenRecordIsPending() {
            seed(SchemaStatus.PENDING);

            assertThat(causeFrom(ManagementRoute.SCHEMA_MIGRATE)).isEqualTo(SchemaRouteError.SchemaAlreadyPending.schemaAlreadyPending(DATASOURCE));
        }

        @Test
        void problemBody_namesDatasource_whenRecordIsPending() {
            seed(SchemaStatus.PENDING);

            assertThat(problemBodyFor(ManagementRoute.SCHEMA_MIGRATE)).contains(DATASOURCE)
                      .contains("already has a migration PENDING")
                      .contains("409");
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

    /// #543 condition 2: `requireLeader` must refuse a non-leader node with 409
    /// (`SchemaRouteError.SchemaNotLeader`) BEFORE the call ever reaches the orchestrator — the
    /// in-process single-flight fence only serializes calls made on one node, so a second node acting
    /// on the same datasource is a correctness gap the fence itself cannot close. This class swaps in
    /// its own `routes`/`nodeOverAsFollower` (the shared `@BeforeEach routes` hardcodes `isLeader ->
    /// true`) rather than parameterizing every existing dispatch helper. `undoRoute_neverInvokes...`
    /// and `baselineRoute_neverInvokes...` are the controls: without them, a refusal that happened
    /// AFTER a (later-discarded) orchestrator call would still report 409 and pass the status
    /// assertions alone.
    @Nested
    class LeaderBinding {
        private static final NodeId CURRENT_LEADER = new NodeId("node-b");

        private RecordingOrchestrator orchestrator;

        @BeforeEach
        void asFollower() {
            seed(SchemaStatus.COMPLETED);
            orchestrator = new RecordingOrchestrator(store);
            routes = SchemaRoutes.schemaRoutes(() -> nodeOverAsFollower(store, orchestrator, Option.some(CURRENT_LEADER)));
        }

        @Test
        void undoRoute_respondsConflict_whenNodeIsNotLeader() {
            assertThat(causeFrom(ManagementRoute.SCHEMA_UNDO, "targetVersion", "2"))
                    .isEqualTo(SchemaRouteError.SchemaNotLeader.schemaNotLeader(DATASOURCE, "undo", Option.some(CURRENT_LEADER)));
        }

        @Test
        void baselineRoute_respondsConflict_whenNodeIsNotLeader() {
            assertThat(causeFrom(ManagementRoute.SCHEMA_BASELINE, "version", "7"))
                    .isEqualTo(SchemaRouteError.SchemaNotLeader.schemaNotLeader(DATASOURCE, "baseline", Option.some(CURRENT_LEADER)));
        }

        @Test
        void problemBody_namesCurrentLeaderAndConflictStatus_whenNodeIsNotLeader() {
            assertThat(problemBodyFor(ManagementRoute.SCHEMA_UNDO, "targetVersion", "2")).contains(DATASOURCE)
                      .contains("requires the leader node")
                      .contains(CURRENT_LEADER.id())
                      .contains("409");
        }

        @Test
        void undoRoute_neverInvokesOrchestrator_whenNodeIsNotLeader() {
            causeFrom(ManagementRoute.SCHEMA_UNDO, "targetVersion", "2");

            assertThat(orchestrator.undoInvoked).as("the leader refusal must happen before the orchestrator is ever touched")
                      .isFalse();
        }

        @Test
        void baselineRoute_neverInvokesOrchestrator_whenNodeIsNotLeader() {
            causeFrom(ManagementRoute.SCHEMA_BASELINE, "version", "7");

            assertThat(orchestrator.baselineInvoked).as("the leader refusal must happen before the orchestrator is ever touched")
                      .isFalse();
        }
    }

    /// #543 condition 4: `UndoNotAvailable`/`BaselineConflict`/`ChecksumMismatch` are the schema
    /// manager's own typed refusals — a missing undo script, applied history a baseline can't
    /// discard, and a tampered/edited migration file — and each already carries its own
    /// `HttpStatusAware` mixin (422/409/422; see `SchemaError`'s per-record docs). Per this file's
    /// class-level javadoc, the risk is a hop between the raising site and the response funnel that
    /// re-wraps the cause and silently restores 500 — so, exactly like [RetryConflict] and
    /// [LeaderBinding] above, the proof is taken at the ROUTE level with a REAL failing orchestrator,
    /// never at the `SchemaError` unit level (which would only prove the record itself was typed
    /// correctly).
    @Nested
    class ErrorMapping {
        @Test
        void undoRoute_respondsUnprocessable_whenUndoScriptIsMissing() {
            routes = SchemaRoutes.schemaRoutes(() -> nodeOverWithOrchestrator(store,
                                                                              orchestratorFailingUndo(SchemaError.UndoNotAvailable.undoNotAvailable(DATASOURCE, 2))));
            seed(SchemaStatus.COMPLETED);

            assertThat(statusFor(ManagementRoute.SCHEMA_UNDO, "targetVersion", "2")).as("a missing undo script is the artifact's fault, not the cluster's — 422 not 500")
                      .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        void undoRoute_propagatesCauseUnwrapped_whenUndoScriptIsMissing() {
            routes = SchemaRoutes.schemaRoutes(() -> nodeOverWithOrchestrator(store,
                                                                              orchestratorFailingUndo(SchemaError.UndoNotAvailable.undoNotAvailable(DATASOURCE, 2))));
            seed(SchemaStatus.COMPLETED);

            assertThat(causeFrom(ManagementRoute.SCHEMA_UNDO, "targetVersion", "2")).isEqualTo(SchemaError.UndoNotAvailable.undoNotAvailable(DATASOURCE, 2));
        }

        @Test
        void undoRoute_respondsUnprocessable_whenChecksumMismatchIsFound() {
            routes = SchemaRoutes.schemaRoutes(() -> nodeOverWithOrchestrator(store,
                                                                              orchestratorFailingUndo(SchemaError.ChecksumMismatch.checksumMismatch(DATASOURCE, 2, 111L, 222L))));
            seed(SchemaStatus.COMPLETED);

            assertThat(statusFor(ManagementRoute.SCHEMA_UNDO, "targetVersion", "2")).as("an edited migration script is a content problem, not a server fault")
                      .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        void undoRoute_propagatesCauseUnwrapped_whenChecksumMismatchIsFound() {
            routes = SchemaRoutes.schemaRoutes(() -> nodeOverWithOrchestrator(store,
                                                                              orchestratorFailingUndo(SchemaError.ChecksumMismatch.checksumMismatch(DATASOURCE, 2, 111L, 222L))));
            seed(SchemaStatus.COMPLETED);

            assertThat(causeFrom(ManagementRoute.SCHEMA_UNDO, "targetVersion", "2")).isEqualTo(SchemaError.ChecksumMismatch.checksumMismatch(DATASOURCE, 2, 111L, 222L));
        }

        @Test
        void baselineRoute_respondsConflict_whenAppliedHistoryBlocksBaseline() {
            routes = SchemaRoutes.schemaRoutes(() -> nodeOverWithOrchestrator(store,
                                                                              orchestratorFailingBaseline(SchemaError.BaselineConflict.baselineConflict(DATASOURCE, 3))));
            seed(SchemaStatus.COMPLETED);

            assertThat(statusFor(ManagementRoute.SCHEMA_BASELINE, "version", "5")).as("applied history blocking a baseline is a state conflict, not a server fault")
                      .isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        void baselineRoute_propagatesCauseUnwrapped_whenAppliedHistoryBlocksBaseline() {
            routes = SchemaRoutes.schemaRoutes(() -> nodeOverWithOrchestrator(store,
                                                                              orchestratorFailingBaseline(SchemaError.BaselineConflict.baselineConflict(DATASOURCE, 3))));
            seed(SchemaStatus.COMPLETED);

            assertThat(causeFrom(ManagementRoute.SCHEMA_BASELINE, "version", "5")).isEqualTo(SchemaError.BaselineConflict.baselineConflict(DATASOURCE, 3));
        }

        /// #543/#832 review round 1 SHOULD-FIX 4: the fence/lock conflict path built an ad hoc,
        /// untyped `Cause` instead of this variant, so a lock conflict answered 500 despite
        /// `LockAcquisitionFailed` already existing and already being `HttpStatusAware` —
        /// `SchemaOrchestratorService#acquireLock` now raises it directly, so this is genuinely
        /// production-reachable through undo/baseline's shared lock-acquisition step.
        @Test
        void undoRoute_respondsConflict_whenMigrationLockIsHeld() {
            routes = SchemaRoutes.schemaRoutes(() -> nodeOverWithOrchestrator(store,
                                                                              orchestratorFailingUndo(SchemaError.LockAcquisitionFailed.lockAcquisitionFailed(DATASOURCE))));
            seed(SchemaStatus.COMPLETED);

            assertThat(statusFor(ManagementRoute.SCHEMA_UNDO, "targetVersion", "2")).as("a concurrent attempt holding the lock is a conflict, not a server fault")
                      .isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        void undoRoute_propagatesCauseUnwrapped_whenMigrationLockIsHeld() {
            routes = SchemaRoutes.schemaRoutes(() -> nodeOverWithOrchestrator(store,
                                                                              orchestratorFailingUndo(SchemaError.LockAcquisitionFailed.lockAcquisitionFailed(DATASOURCE))));
            seed(SchemaStatus.COMPLETED);

            assertThat(causeFrom(ManagementRoute.SCHEMA_UNDO, "targetVersion", "2")).isEqualTo(SchemaError.LockAcquisitionFailed.lockAcquisitionFailed(DATASOURCE));
        }

        /// #543/#832 review round 1 SHOULD-FIX 4: production-reachable through undo/baseline's own
        /// `resolveMigrationScripts` (distinct from forward migrate's `resolveAndParseMigrations`,
        /// which raises the same variant on its own, separate path) — a schema version record that
        /// declares a migration set but carries no artifact coordinates.
        @Test
        void undoRoute_respondsUnprocessable_whenMigrationArtifactIsUnresolved() {
            routes = SchemaRoutes.schemaRoutes(() -> nodeOverWithOrchestrator(store,
                                                                              orchestratorFailingUndo(SchemaError.MigrationArtifactUnresolved.migrationArtifactUnresolved(DATASOURCE, 2, "V002__add_index.sql"))));
            seed(SchemaStatus.COMPLETED);

            assertThat(statusFor(ManagementRoute.SCHEMA_UNDO, "targetVersion", "2")).as("missing artifact coordinates on a record that declares migrations is a content problem, not a server fault")
                      .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        void undoRoute_propagatesCauseUnwrapped_whenMigrationArtifactIsUnresolved() {
            routes = SchemaRoutes.schemaRoutes(() -> nodeOverWithOrchestrator(store,
                                                                              orchestratorFailingUndo(SchemaError.MigrationArtifactUnresolved.migrationArtifactUnresolved(DATASOURCE, 2, "V002__add_index.sql"))));
            seed(SchemaStatus.COMPLETED);

            assertThat(causeFrom(ManagementRoute.SCHEMA_UNDO, "targetVersion", "2")).isEqualTo(SchemaError.MigrationArtifactUnresolved.migrationArtifactUnresolved(DATASOURCE, 2, "V002__add_index.sql"));
        }

        /// #543/#832 review round 1 SHOULD-FIX 4: production-reachable through the same
        /// `resolveMigrationScripts` chain — the resolved artifact holds no scripts for a datasource
        /// that declared them (republished coordinates, or a fallback resolving a different jar).
        @Test
        void undoRoute_respondsUnprocessable_whenMigrationSetIsUnavailable() {
            routes = SchemaRoutes.schemaRoutes(() -> nodeOverWithOrchestrator(store,
                                                                              orchestratorFailingUndo(SchemaError.MigrationSetUnavailable.migrationSetUnavailable(DATASOURCE, COORDS, 2))));
            seed(SchemaStatus.COMPLETED);

            assertThat(statusFor(ManagementRoute.SCHEMA_UNDO, "targetVersion", "2")).as("a resolved artifact missing the declared scripts is a content problem, not a server fault")
                      .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        void undoRoute_propagatesCauseUnwrapped_whenMigrationSetIsUnavailable() {
            routes = SchemaRoutes.schemaRoutes(() -> nodeOverWithOrchestrator(store,
                                                                              orchestratorFailingUndo(SchemaError.MigrationSetUnavailable.migrationSetUnavailable(DATASOURCE, COORDS, 2))));
            seed(SchemaStatus.COMPLETED);

            assertThat(causeFrom(ManagementRoute.SCHEMA_UNDO, "targetVersion", "2")).isEqualTo(SchemaError.MigrationSetUnavailable.migrationSetUnavailable(DATASOURCE, COORDS, 2));
        }

        /// #543/#832 review round 1 SHOULD-FIX 4: production-reachable through `AetherSchemaManager`'s
        /// `parseAll` — undo and baseline each parse their resolved scripts through the same
        /// filename-format check migrate uses (`ParsedMigration`), so a malformed script name fails
        /// identically here.
        @Test
        void undoRoute_respondsUnprocessable_whenMigrationFilenameIsMalformed() {
            routes = SchemaRoutes.schemaRoutes(() -> nodeOverWithOrchestrator(store,
                                                                              orchestratorFailingUndo(SchemaError.InvalidMigrationFormat.invalidMigrationFormat("XYZ__bad.sql", "unknown prefix 'XYZ', expected V/R/U/B"))));
            seed(SchemaStatus.COMPLETED);

            assertThat(statusFor(ManagementRoute.SCHEMA_UNDO, "targetVersion", "2")).as("a malformed migration filename is a content problem, not a server fault")
                      .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        void undoRoute_propagatesCauseUnwrapped_whenMigrationFilenameIsMalformed() {
            routes = SchemaRoutes.schemaRoutes(() -> nodeOverWithOrchestrator(store,
                                                                              orchestratorFailingUndo(SchemaError.InvalidMigrationFormat.invalidMigrationFormat("XYZ__bad.sql", "unknown prefix 'XYZ', expected V/R/U/B"))));
            seed(SchemaStatus.COMPLETED);

            assertThat(causeFrom(ManagementRoute.SCHEMA_UNDO, "targetVersion", "2")).isEqualTo(SchemaError.InvalidMigrationFormat.invalidMigrationFormat("XYZ__bad.sql", "unknown prefix 'XYZ', expected V/R/U/B"));
        }

        /// #543/#832 review round 1 SHOULD-FIX 4: `MigrationFailed` and `DatasourceUnreachable` are
        /// NOT constructed anywhere in this repository's production code as of this review round —
        /// `classifyFailure` below references both in `instanceof` branches that are themselves dead,
        /// and the only apparent `MigrationFailed` construction site resolves to the unrelated,
        /// same-named `SchemaEvent.MigrationFailed`. These two tests prove the mapping funnel is
        /// correct in advance of either ever being wired up to a real raise site; they are NOT
        /// evidence either is currently reachable through any route. Disclosed to the #543/#832
        /// review as a scope gap — a follow-up ticket should either wire these in or retire them.
        @Test
        void undoRoute_respondsUnprocessable_whenMigrationHasFailed() {
            routes = SchemaRoutes.schemaRoutes(() -> nodeOverWithOrchestrator(store,
                                                                              orchestratorFailingUndo(SchemaError.MigrationFailed.migrationFailed(DATASOURCE, 2, "constraint violation"))));
            seed(SchemaStatus.COMPLETED);

            assertThat(statusFor(ManagementRoute.SCHEMA_UNDO, "targetVersion", "2")).as("a failed migration script is a content problem, not a server fault — not currently raised by production code (see class note above)")
                      .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        void undoRoute_propagatesCauseUnwrapped_whenMigrationHasFailed() {
            routes = SchemaRoutes.schemaRoutes(() -> nodeOverWithOrchestrator(store,
                                                                              orchestratorFailingUndo(SchemaError.MigrationFailed.migrationFailed(DATASOURCE, 2, "constraint violation"))));
            seed(SchemaStatus.COMPLETED);

            assertThat(causeFrom(ManagementRoute.SCHEMA_UNDO, "targetVersion", "2")).isEqualTo(SchemaError.MigrationFailed.migrationFailed(DATASOURCE, 2, "constraint violation"));
        }

        @Test
        void undoRoute_respondsServiceUnavailable_whenDatasourceIsUnreachable() {
            routes = SchemaRoutes.schemaRoutes(() -> nodeOverWithOrchestrator(store,
                                                                              orchestratorFailingUndo(SchemaError.DatasourceUnreachable.datasourceUnreachable(DATASOURCE, "connection refused"))));
            seed(SchemaStatus.COMPLETED);

            assertThat(statusFor(ManagementRoute.SCHEMA_UNDO, "targetVersion", "2")).as("an infrastructure fault named as such is a 503 — not currently raised by production code (see class note above)")
                      .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }

        @Test
        void undoRoute_propagatesCauseUnwrapped_whenDatasourceIsUnreachable() {
            routes = SchemaRoutes.schemaRoutes(() -> nodeOverWithOrchestrator(store,
                                                                              orchestratorFailingUndo(SchemaError.DatasourceUnreachable.datasourceUnreachable(DATASOURCE, "connection refused"))));
            seed(SchemaStatus.COMPLETED);

            assertThat(causeFrom(ManagementRoute.SCHEMA_UNDO, "targetVersion", "2")).isEqualTo(SchemaError.DatasourceUnreachable.datasourceUnreachable(DATASOURCE, "connection refused"));
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

    /// Mirror of [#causeFrom] for the success path — drives the real route handler and returns the
    /// value it produced, failing loudly if the route unexpectedly refused.
    private SchemaRoutes.SchemaStatusResponse successResponseFor(ManagementRoute route) {
        var holder = new AtomicReference<SchemaRoutes.SchemaStatusResponse>();

        handle(route, Option.none(), Option.none()).onFailure(cause -> Assertions.fail("Route " + route.name()
                                                                                       + " must succeed, got: " + cause.message()))
              .onSuccess(value -> holder.set((SchemaRoutes.SchemaStatusResponse) value));

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

    /// Plants ownership (`SliceTargetKey`/`SliceTargetValue`) alongside the LIVE per-node state
    /// (`SliceNodeKey`/`SliceNodeValue`) that `SchemaRoutes.heldSlices` reads post-#760-review — a
    /// slice genuinely waiting at the gate.
    private void seedSliceTarget(String artifactBaseString, BlueprintId owner) {
        seedSliceInState(artifactBaseString, owner, SliceState.LOADED);
    }

    /// Same join as [#seedSliceTarget], but ACTIVE: a slice that already passed the gate and has no
    /// transition path back through LOADED.
    private void seedActiveSlice(String artifactBaseString, BlueprintId owner) {
        seedSliceInState(artifactBaseString, owner, SliceState.ACTIVE);
    }

    private void seedSliceInState(String artifactBaseString, BlueprintId owner, SliceState state) {
        var artifactBase = ArtifactBase.artifactBase(artifactBaseString).unwrap();
        var artifact = Artifact.artifact(artifactBase, SLICE_VERSION);

        store.put(SliceTargetKey.sliceTargetKey(artifactBase),
                  SliceTargetValue.sliceTargetValue(SLICE_VERSION, 1, Option.some(owner)));
        store.put(SliceNodeKey.sliceNodeKey(artifact, NODE), SliceNodeValue.sliceNodeValue(state));
    }

    /// #760 review round 2 item a: plants an `AppBlueprintValue` whose embedded resources.toml
    /// declares `schema_required = false`, so `heldSlices` (via
    /// `ClusterDeploymentState.resolveSchemaRequired`) resolves ownership to non-schema-required
    /// instead of defaulting to `true`. Mirrors `SchemaRequiredResolutionTest.resourcesToml`: a
    /// non-empty `[[slices]]` and a `[deployment].strategy` are both required for `schema_required`
    /// to be read at all.
    private void seedOwningBlueprintWithoutSchemaRequirement(BlueprintId owner) {
        var resourcesToml = """
                             id = "%s"

                             [[slices]]
                             artifact = "org.example:seed-slice:1.0.0"

                             [deployment]
                             strategy = "rolling"
                             schema_required = false
                             """.formatted(owner.asString());
        var expanded = ExpandedBlueprint.expandedBlueprint(owner, List.of(), Option.some(resourcesToml));

        store.put(AppBlueprintKey.appBlueprintKey(owner), AppBlueprintValue.appBlueprintValue(expanded));
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
        var orchestrator = new RecordingOrchestrator(kvStore);

        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, args) -> switch (method.getName()) {
            case "kvStore" -> kvStore;
            case "apply" -> applyBatch(args);
            case "isLeader" -> true;
            case "leader" -> Option.none();
            case "schemaOrchestrator" -> orchestrator;
            default -> throw new UnsupportedOperationException("Not implemented in test proxy: " + method.getName());
        });
    }

    /// #543: `SchemaRoutes.baselineAtVersion`/`undoToVersion` now delegate to
    /// `ManageableNode.schemaOrchestrator()` instead of fabricating the KV write inline, so the proxy
    /// needs one to hand back. This file's tests are about ROUTE-LEVEL parameter handling (default
    /// version, 400 on unparseable input), not about `SchemaOrchestratorService`'s own script
    /// resolution — that boundary is `SchemaRoutesBaselineTest`'s job, which wires a REAL
    /// orchestrator. Faking only this collaborator (while the route logic above it stays real) is a
    /// legitimate layer boundary, not the pre-fix anti-pattern where the ROUTE ITSELF fabricated the
    /// write with no orchestrator in the loop at all.
    ///
    /// `DATASOURCE = "orders_db"` also makes a real orchestrator a non-option here:
    /// `BlueprintArtifactParser` only ever derives `"database"` or `"database.<folder>"` schema-set
    /// keys from a migration script's path, so no blueprint jar could ever populate scripts under the
    /// literal key `"orders_db"` — resolution would fail every time regardless of jar content.
    private static final class RecordingOrchestrator implements SchemaOrchestratorService {
        private final InMemoryKvStore kvStore;
        boolean undoInvoked;
        boolean baselineInvoked;

        RecordingOrchestrator(InMemoryKvStore kvStore) {
            this.kvStore = kvStore;
        }

        @Override
        public Promise<Unit> migrateIfNeeded(String datasourceName) {
            return Causes.cause("migrateIfNeeded() not exercised by SchemaRouteStatusTest's fake orchestrator").promise();
        }

        @Override
        public Promise<Unit> undoTo(String datasourceName, int targetVersion) {
            undoInvoked = true;
            return recordOutcome(datasourceName, targetVersion, "U%03d__undo".formatted(targetVersion));
        }

        @Override
        public Promise<Unit> baseline(String datasourceName, int version) {
            baselineInvoked = true;
            return recordOutcome(datasourceName, version, "V%03d__baseline".formatted(version));
        }

        private Promise<Unit> recordOutcome(String datasourceName, int version, String lastMigration) {
            kvStore.put(SchemaVersionKey.schemaVersionKey(datasourceName),
                        SchemaVersionValue.schemaVersionValue(datasourceName, version, lastMigration, SchemaStatus.COMPLETED, COORDS, OWNER));

            return Promise.unitPromise();
        }
    }

    /// #543 condition 2 fixture: a non-leader node must refuse (409) before the orchestrator is ever
    /// touched. Mirrors [#nodeOver] with `isLeader`/`leader` swapped for the follower case; everything
    /// else (kvStore, apply, schemaOrchestrator) is identical.
    private ManageableNode nodeOverAsFollower(InMemoryKvStore kvStore, RecordingOrchestrator orchestrator, Option<NodeId> currentLeader) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, args) -> switch (method.getName()) {
            case "kvStore" -> kvStore;
            case "apply" -> applyBatch(args);
            case "isLeader" -> false;
            case "leader" -> currentLeader;
            case "schemaOrchestrator" -> orchestrator;
            default -> throw new UnsupportedOperationException("Not implemented in test proxy: " + method.getName());
        });
    }

    @SuppressWarnings("unchecked")
    private Promise<List<Long>> applyBatch(Object[] args) {
        ((List<KVCommand<AetherKey>>) args[0]).forEach(store::apply);

        return Promise.success(List.of());
    }

    /// #543 condition 4 fixture: leader like [#nodeOver], but hands back a caller-supplied
    /// orchestrator instead of the always-succeeding [RecordingOrchestrator] — [ErrorMapping] needs
    /// `undoTo`/`baseline` to actually FAIL with a typed `SchemaError` to prove the route propagates
    /// it unwrapped.
    private ManageableNode nodeOverWithOrchestrator(InMemoryKvStore kvStore, SchemaOrchestratorService orchestrator) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, args) -> switch (method.getName()) {
            case "kvStore" -> kvStore;
            case "apply" -> applyBatch(args);
            case "isLeader" -> true;
            case "leader" -> Option.none();
            case "schemaOrchestrator" -> orchestrator;
            default -> throw new UnsupportedOperationException("Not implemented in test proxy: " + method.getName());
        });
    }

    private static SchemaOrchestratorService orchestratorFailingUndo(Cause cause) {
        return new SchemaOrchestratorService() {
            @Override
            public Promise<Unit> migrateIfNeeded(String datasourceName) {
                return Causes.cause("migrateIfNeeded() not exercised by ErrorMapping's fake orchestrator").promise();
            }

            @Override
            public Promise<Unit> undoTo(String datasourceName, int targetVersion) {
                return cause.promise();
            }

            @Override
            public Promise<Unit> baseline(String datasourceName, int version) {
                return Causes.cause("baseline() not exercised by this ErrorMapping fixture").promise();
            }
        };
    }

    private static SchemaOrchestratorService orchestratorFailingBaseline(Cause cause) {
        return new SchemaOrchestratorService() {
            @Override
            public Promise<Unit> migrateIfNeeded(String datasourceName) {
                return Causes.cause("migrateIfNeeded() not exercised by ErrorMapping's fake orchestrator").promise();
            }

            @Override
            public Promise<Unit> undoTo(String datasourceName, int targetVersion) {
                return Causes.cause("undoTo() not exercised by this ErrorMapping fixture").promise();
            }

            @Override
            public Promise<Unit> baseline(String datasourceName, int version) {
                return cause.promise();
            }
        };
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
