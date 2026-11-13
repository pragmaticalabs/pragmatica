// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.pragmatica.aether.api.ManagementApiResponses.ApplyConfigRequest;
import org.pragmatica.aether.api.ManagementServerError;
import org.pragmatica.aether.api.ManagementApiResponses.ApplyConfigResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ScaleRequest;
import org.pragmatica.aether.api.ManagementApiResponses.UpgradeRequest;
import org.pragmatica.aether.config.cluster.ApplyRoute;
import org.pragmatica.aether.config.cluster.ClusterConfigError;
import org.pragmatica.aether.config.cluster.DiffAction;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.aether.deployment.cluster.ClusterConfigApplier;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.deployment.cluster.ClusterReplication;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AutoHealStateValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopologyEntry;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.routing.JsonCodecAdapter;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// `POST /api/cluster/config` — the three defects that made RFC-0017 worker provisioning a no-op,
/// each proven on a live Hetzner cluster before being pinned here.
///
/// Observed on the wire at formation: every node self-seeds a `ClusterConfigValue` with
/// `tomlContent=""`, the bootstrap CLI POSTs the operator's real TOML (carrying
/// `[source.hetzner.worker] count = 2`) with `expectedVersion=0`, the route answers with a success
/// `ApplyConfigResponse` — and the committed config remains the core-only seed. The worker topology
/// entry never reaches cluster state, so `reconcileWorkerTopology` has nothing to reconcile and no
/// cloud worker is ever created. Re-POSTing reproduced it exactly: success, `updatedAt` unmoved.
///
/// The three faults compose, which is why the failure was silent rather than loud:
///   1. routing recovered from ANY apply failure into the first-time store, so a refusal became a
///      fresh write attempt;
///   2. the apply against a blank-TOML seed always failed (the parser cannot clear its
///      `config_version` gate on `""`), which is the failure that was then recovered from;
///   3. that first-time store issued a bare `Put` and reported success unconditionally, while the
///      RFC-0018 successor fence rejected it SILENTLY — a version-1 write against a committed
///      version-1 seed is not a successor.
///
/// [TestKVStore] therefore models the successor fence rather than accepting every write: a fake that
/// stores whatever it is handed cannot observe defect 3 at all, since the whole defect is a
/// rejection that leaves no trace in the apply result.
class ClusterConfigRoutesApplyTest {
    /// The live RFC-0017 shape: one cloud source declaring BOTH core and worker counts.
    private static final String OPERATOR_TOML = """
        config_version = "1.0.0"

        [cluster]
        name = "prod"
        version = "1.0.0"

        [runtime.node]
        type = "container"
        image = "ghcr.io/pragmaticalabs/aether-node:1.0.0"

        [source.hetzner]
        type = "cloud"
        provider = "hetzner"
        credentials = "hcloud-token"
        region = "eu-central"

        [source.hetzner.core]
        count = 3
        runtime = "node"

        [source.hetzner.worker]
        count = 2
        runtime = "node"
        """;

    /// Same cluster, more workers — a genuine mutation, so the diff is non-empty.
    private static final String SCALED_TOML = OPERATOR_TOML.replace("count = 2", "count = 4");

    /// `cluster.name` is the one immutable field, so this is the cheapest way to make the diff refuse.
    private static final String RENAMED_TOML = OPERATOR_TOML.replace("name = \"prod\"", "name = \"staging\"");

    private static final TopologyEntry CORE_ENTRY = new TopologyEntry("hetzner", "core", 3);
    private static final TopologyEntry WORKER_ENTRY = new TopologyEntry("hetzner", "worker", 2);

    @Nested
    class SeedReplacement {

        /// The defect, end to end: a seed carries no diffable TOML, so it can only be REPLACED —
        /// which is what BootstrapModule's own comment ("Bootstrap replaces it with the real
        /// per-source spec at formation") always claimed happened.
        @Test
        void handleApplyConfig_storedBootstrapSeed_replacesSeedWithFullOperatorTopology() {
            var store = storeWith(bootstrapSeed());

            var response = applySucceeds(store, new ApplyConfigRequest(OPERATOR_TOML, 0, Option.none()));
            var committed = committedConfig(store);

            assertThat(committed.tomlContent()).isEqualTo(Option.some(OPERATOR_TOML));
            assertThat(committed.desiredTopology()).containsExactlyInAnyOrder(CORE_ENTRY, WORKER_ENTRY);
            assertThat(committed.configVersion()).as("seed version + 1, so the successor fence accepts the write")
                                                 .isEqualTo(2);
            assertThat(response.configVersion()).isEqualTo(2);
        }

        /// The operator-visible symptom: without the worker entry in committed state,
        /// `reconcileWorkerTopology` has nothing to act on and no cloud worker is ever created.
        @Test
        void handleApplyConfig_storedBootstrapSeed_carriesWorkerCountIntoClusterState() {
            var store = storeWith(bootstrapSeed());

            applySucceeds(store, new ApplyConfigRequest(OPERATOR_TOML, 0, Option.none()));

            assertThat(committedConfig(store).desiredCountFor("hetzner", "worker")).isEqualTo(2);
        }

        /// The seed's placeholder entry (empty source name) must not survive alongside the real one,
        /// or the topology would declare a phantom source.
        @Test
        void handleApplyConfig_storedBootstrapSeed_dropsThePlaceholderSourceEntry() {
            var store = storeWith(bootstrapSeed());

            applySucceeds(store, new ApplyConfigRequest(OPERATOR_TOML, 0, Option.none()));

            assertThat(committedConfig(store).desiredTopology()).noneMatch(entry -> entry.sourceName().isEmpty());
            assertThat(committedConfig(store).deploymentType()).isEqualTo("cloud");
        }
    }

    @Nested
    class FailurePropagation {

        /// Defect 1. The refusal must reach the operator. Previously `.orElse(storeInitialConfig)`
        /// could not tell this failure from "nothing stored yet", so a rejected rename was answered
        /// with a success response for a phantom first-time store.
        @Test
        void handleApplyConfig_immutableFieldChange_propagatesFailureWithoutPhantomInitialStore() {
            var store = storeWith(committedOperatorConfig(5));

            var result = apply(store, new ApplyConfigRequest(RENAMED_TOML, 5, Option.none()));

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause).isInstanceOf(ClusterConfigError.ValidationFailed.class));
            assertUnchanged(store, 5);
        }

        @Test
        void handleApplyConfig_versionConflict_propagatesFailureWithoutPhantomInitialStore() {
            var store = storeWith(committedOperatorConfig(5));

            var result = apply(store, new ApplyConfigRequest(SCALED_TOML, 3, Option.none()));

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause).isInstanceOf(ClusterConfigError.VersionConflict.class));
            assertUnchanged(store, 5);
        }

        /// The #289 fence, reached through the whole route rather than through its pure predicate:
        /// an unfenced re-push against populated config is refused, and refusal does not fall through
        /// to a first-time store either.
        @Test
        void handleApplyConfig_unfencedOverwriteOfPopulatedConfig_propagatesFailure() {
            var store = storeWith(committedOperatorConfig(5));

            var result = apply(store, new ApplyConfigRequest(SCALED_TOML, 0, Option.none()));

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause).isInstanceOf(ClusterConfigError.UnfencedOverwrite.class));
            assertUnchanged(store, 5);
        }

        private static void assertUnchanged(TestKVStore store, long expectedVersion) {
            var committed = committedConfig(store);

            assertThat(committed.clusterName()).isEqualTo("prod");
            assertThat(committed.tomlContent()).isEqualTo(Option.some(OPERATOR_TOML));
            assertThat(committed.configVersion()).isEqualTo(expectedVersion);
        }
    }

    @Nested
    class InitialStore {

        @Test
        void handleApplyConfig_emptyKvStore_storesEveryDeclaredSourceRolePairAtVersionOne() {
            var store = new TestKVStore();

            var response = applySucceeds(store, new ApplyConfigRequest(OPERATOR_TOML, 0, Option.none()));
            var committed = committedConfig(store);

            assertThat(committed.desiredTopology()).containsExactlyInAnyOrder(CORE_ENTRY, WORKER_ENTRY);
            assertThat(committed.configVersion()).isEqualTo(1);
            assertThat(committed.clusterName()).isEqualTo("prod");
            assertThat(response.configVersion()).isEqualTo(1);
            assertThat(response.coreCount()).isEqualTo(3);
        }

        /// #1564 B1: `[replication.cluster_events] confirmation_factor` above the desired core count (3 here) is
        /// refused at apply, typed, and nothing is committed — instead of committing a config the cluster-events
        /// registrar can never satisfy.
        @Test
        void handleApplyConfig_clusterEventsConfirmationAboveCoreCount_isRefusedAndNothingCommits() {
            var store = new TestKVStore();
            var toml = OPERATOR_TOML + """

                [replication.cluster_events]
                confirmation_factor = 5
                """;

            var result = apply(store, new ApplyConfigRequest(toml, 0, Option.none()));

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause).isInstanceOf(ClusterReplication.ClusterEventsFactorsRefused.class));
            assertThat(store.get(ClusterConfigKey.CURRENT).isEmpty()).isTrue();
        }

        /// Defect 3. The first-time store used a bare `Put` and reported success unconditionally,
        /// but the successor fence rejects silently — nothing in the apply result reveals the loss.
        /// A write that did not land must be a failure, never a fabricated version and timestamp.
        @Test
        void handleApplyConfig_initialStoreThatDoesNotLand_failsInsteadOfReportingSuccess() {
            var store = new TestKVStore();

            store.loseWritesTo(committedOperatorConfig(9));

            var result = apply(store, new ApplyConfigRequest(SCALED_TOML, 0, Option.none()));

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause).isInstanceOf(ClusterConfigError.VersionConflict.class));
            assertThat(committedConfig(store).tomlContent()).as("the competing writer's config is what committed")
                                                            .isEqualTo(Option.some(OPERATOR_TOML));
        }
    }

    @Nested
    class ScaleReachesApplier {

        /// #578 review Testing Gap T4: none of the other tests in this file exercise a diff that
        /// actually reaches `applier.apply(plan.allActions())` (`ClusterConfigRoutes.executeDiff`) —
        /// they either replace a bootstrap seed (bypasses the applier entirely) or fail before the
        /// diff is even computed. A genuine worker-count scale against a real, matching-version
        /// committed config is the smallest diff that reaches the applier. This harness wires
        /// `ClusterConfigApplier.NoTopologyManager` (the single-arg `clusterConfigRoutes` factory's
        /// applier), so the observable proof of reachability is the applier's own honest 503 — not a
        /// fabricated success — and a rejected apply must leave the previously committed config
        /// untouched, same guarantee the applier's own `apply()` gives for any rejected plan.
        @Test
        void handleApplyConfig_genuineScale_reachesApplier_andFailsHonestlyWithNoTopologyManagerWired() {
            var store = storeWith(committedOperatorConfig(5));

            var result = apply(store, new ApplyConfigRequest(SCALED_TOML, 5, Option.none()));

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause).isInstanceOf(ClusterConfigError.ClusterTopologyManagerUnavailable.class));

            var committed = committedConfig(store);
            assertThat(committed.tomlContent()).as("a rejected apply must leave the previously committed config untouched")
                                               .isEqualTo(Option.some(OPERATOR_TOML));
            assertThat(committed.configVersion()).isEqualTo(5);
        }
    }

    /// #686 check 2: with a `route`, the server decides the route of every pool-changing action from the committed auto-heal
    /// value, refuses the actions no route makes safe, and refuses a claim that differs from its own decision. Each test
    /// drives the real diff from a committed TOML to a desired TOML through `handleApplyConfig` with a real applier.
    @Nested
    class RouteCheck {
        private static final String CORE_ONLY_TOML = OPERATOR_TOML.replace("[source.hetzner.worker]\ncount = 2\nruntime = \"node\"\n", "");
        private static final String WORKER_ZERO_TOML = OPERATOR_TOML.replace("count = 2", "count = 0");
        private static final String CORE_SCALED_TOML = OPERATOR_TOML.replace("count = 3", "count = 5");
        private static final String CORE_AND_WORKER_SCALED_TOML = CORE_SCALED_TOML.replace("count = 2", "count = 4");
        private static final String REGION_CHANGED_TOML = OPERATOR_TOML.replace("eu-central", "eu-west");
        private static final String EDGE_SOURCE = """

            [source.edge]
            type = "cloud"
            provider = "hetzner"
            credentials = "hcloud-token"
            region = "eu-west"
            """;
        private static final String EDGE_CORE = """

            [source.edge.core]
            count = 2
            runtime = "node"
            """;
        private static final String EDGE_WORKER = """

            [source.edge.worker]
            count = 1
            runtime = "node"
            """;
        private static final String EDGE_WORKER_ZERO = EDGE_WORKER.replace("count = 1", "count = 0");
        private static final String WITH_EDGE_CORE_TOML = OPERATOR_TOML + EDGE_SOURCE + EDGE_CORE;
        private static final String WITH_EDGE_CORE_AND_IDLE_WORKER_TOML = WITH_EDGE_CORE_TOML + EDGE_WORKER_ZERO;
        private static final String WITH_EDGE_IDLE_WORKER_TOML = OPERATOR_TOML + EDGE_SOURCE + EDGE_WORKER_ZERO;
        private static final String WITH_EDGE_WORKER_TOML = OPERATOR_TOML + EDGE_SOURCE + EDGE_WORKER;
        private static final long STORED_VERSION = 5;

        @Test
        void leaderRoute_workerAddRole_accepted() {
            var store = storeWith(committed(CORE_ONLY_TOML));

            var response = routed(store, AUTO_HEAL_ON, OPERATOR_TOML, ApplyRoute.LEADER).map(ApplyConfigResponse.class::cast)
                                                                                         .unwrap();

            assertThat(response.configVersion()).isEqualTo(STORED_VERSION + 1);
            assertThat(committedConfig(store).tomlContent()).isEqualTo(Option.some(OPERATOR_TOML));
        }

        @Test
        void applyRoute_coreAddRole_autoHealOff_accepted() {
            var store = storeWith(committed(OPERATOR_TOML));

            var result = routed(store, AUTO_HEAL_OFF, WITH_EDGE_CORE_TOML, ApplyRoute.APPLY);

            assertThat(result.isSuccess()).as("core AddRole with auto-heal off is the APPLY route: %s", result).isTrue();
            assertThat(committedConfig(store).tomlContent()).isEqualTo(Option.some(WITH_EDGE_CORE_TOML));
        }

        @Test
        void applyRoute_autoHealOn_refused409() {
            var store = storeWith(committed(OPERATOR_TOML));

            var cause = refusedWith(routed(store, AUTO_HEAL_ON, CORE_SCALED_TOML, ApplyRoute.APPLY), ClusterConfigError.RouteMismatch.class);

            assertThat(cause.claimed()).isEqualTo(ApplyRoute.APPLY);
            assertThat(cause.required()).as("auto-heal on: the leader fills a core pool").isEqualTo(ApplyRoute.LEADER);
            assertThat(cause.httpStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertUnchanged(store);
        }

        /// A MIXED plan (one APPLY action, one LEADER action) has no claim the server accepts: the CLI refuses it before any
        /// write, and the server refuses it again whichever route the request claims.
        @Test
        void routeClaimMismatch_refused() {
            var store = storeWith(committed(OPERATOR_TOML));

            var claimedApply = refusedWith(routed(store, AUTO_HEAL_OFF, CORE_AND_WORKER_SCALED_TOML, ApplyRoute.APPLY),
                                           ClusterConfigError.RouteMismatch.class);
            var claimedLeader = refusedWith(routed(store, AUTO_HEAL_OFF, CORE_AND_WORKER_SCALED_TOML, ApplyRoute.LEADER),
                                            ClusterConfigError.RouteMismatch.class);

            assertThat(claimedApply.required()).as("the worker scale-up is the leader's").isEqualTo(ApplyRoute.LEADER);
            assertThat(claimedApply.action()).isInstanceOf(DiffAction.ScaleUp.class);
            assertThat(claimedLeader.required()).as("the core scale-up with auto-heal off is apply's").isEqualTo(ApplyRoute.APPLY);
            assertUnchanged(store);
        }

        @Test
        void leaderRoute_coreScaleUp_autoHealOff_refused409() {
            var store = storeWith(committed(OPERATOR_TOML));

            var cause = refusedWith(routed(store, AUTO_HEAL_OFF, CORE_SCALED_TOML, ApplyRoute.LEADER), ClusterConfigError.RouteMismatch.class);

            assertThat(cause.required()).isEqualTo(ApplyRoute.APPLY);
            assertThat(cause.httpStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertUnchanged(store);
        }

        @Test
        void coreScaleUp_leaderRoute_singleSource_accepted() {
            var store = storeWith(committed(OPERATOR_TOML));

            var result = routed(store, AUTO_HEAL_ON, CORE_SCALED_TOML, ApplyRoute.LEADER);

            assertThat(result.isSuccess()).as("a single core source with auto-heal on stays on the LEADER route: %s", result).isTrue();
        }

        @Test
        void applyRoute_workerAddRole_refused() {
            var store = storeWith(committed(CORE_ONLY_TOML));

            var cause = refusedWith(routed(store, AUTO_HEAL_OFF, OPERATOR_TOML, ApplyRoute.APPLY), ClusterConfigError.RouteMismatch.class);

            assertThat(cause.required()).isEqualTo(ApplyRoute.LEADER);
            assertThat(cause.action()).isInstanceOf(DiffAction.AddRole.class);
            assertUnchanged(store);
        }

        @Test
        void leaderRoute_coreScaleDown_autoHealOff_refused409() {
            var store = storeWith(committed(CORE_SCALED_TOML));

            var cause = refusedWith(routed(store, AUTO_HEAL_OFF, OPERATOR_TOML, ApplyRoute.LEADER), ClusterConfigError.RouteMismatch.class);

            assertThat(cause.required()).isEqualTo(ApplyRoute.APPLY);
            assertThat(cause.action()).isInstanceOf(DiffAction.ScaleDown.class);
            assertUnchanged(store);
        }

        @Test
        void leaderRoute_coreRemoveRole_autoHealOff_refused() {
            var store = storeWith(committed(WITH_EDGE_CORE_AND_IDLE_WORKER_TOML));

            var cause = refusedWith(routed(store, AUTO_HEAL_OFF, WITH_EDGE_IDLE_WORKER_TOML, ApplyRoute.LEADER),
                                    ClusterConfigError.RouteMismatch.class);

            assertThat(cause.required()).as("removing live core nodes with auto-heal off is apply's").isEqualTo(ApplyRoute.APPLY);
            assertThat(cause.action()).isInstanceOf(DiffAction.RemoveRole.class);
            assertUnchanged(store);
        }

        @Test
        void coreRemoveRole_zeroCount_admittedOnEitherRoute() {
            var store = storeWith(committed(WITH_EDGE_CORE_AND_IDLE_WORKER_TOML.replace("count = 2\nruntime = \"node\"\n\n[source.edge.worker]",
                                                                                        "count = 0\nruntime = \"node\"\n\n[source.edge.worker]")));

            var result = routed(store, AUTO_HEAL_OFF, WITH_EDGE_IDLE_WORKER_TOML, ApplyRoute.LEADER);

            assertThat(result.isSuccess()).as("a zero-count core removal changes no pool: %s", result).isTrue();
        }

        @Test
        void leaderRoute_coreRemoveSource_autoHealOff_refused() {
            var store = storeWith(committed(WITH_EDGE_CORE_TOML));

            var cause = refusedWith(routed(store, AUTO_HEAL_OFF, OPERATOR_TOML, ApplyRoute.LEADER), ClusterConfigError.RouteMismatch.class);

            assertThat(cause.required()).isEqualTo(ApplyRoute.APPLY);
            assertThat(cause.action()).isInstanceOf(DiffAction.RemoveSource.class);
            assertUnchanged(store);
        }

        @Test
        void coreAddRole_zeroCount_autoHealOn_accepted() {
            var store = storeWith(committed(OPERATOR_TOML));

            var result = routed(store, AUTO_HEAL_ON, OPERATOR_TOML + EDGE_SOURCE + EDGE_CORE.replace("count = 2", "count = 0"), ApplyRoute.LEADER);

            assertThat(result.isSuccess()).as("a core role declared with no nodes has nothing to fill: %s", result).isTrue();
        }

        private static final String WITH_EDGE_ZERO_CORE_TOML = OPERATOR_TOML + EDGE_SOURCE + EDGE_CORE.replace("count = 2", "count = 0");

        @Test
        void zeroCoreAddSource_autoHealOff_admittedOnEitherRoute() {
            var leader = routed(storeWith(committed(OPERATOR_TOML)), AUTO_HEAL_OFF, WITH_EDGE_ZERO_CORE_TOML, ApplyRoute.LEADER);
            var apply = routed(storeWith(committed(OPERATOR_TOML)), AUTO_HEAL_OFF, WITH_EDGE_ZERO_CORE_TOML, ApplyRoute.APPLY);

            assertThat(leader.isSuccess()).as("a zero-size AddRole is unrouted, the CLI's CONFIG claim is LEADER: %s", leader).isTrue();
            assertThat(apply.isSuccess()).as("a zero-size AddRole is unrouted, either claim is admitted: %s", apply).isTrue();
        }

        @Test
        void zeroCoreAddSource_autoHealOn_admittedOnEitherRoute() {
            var leader = routed(storeWith(committed(OPERATOR_TOML)), AUTO_HEAL_ON, WITH_EDGE_ZERO_CORE_TOML, ApplyRoute.LEADER);
            var apply = routed(storeWith(committed(OPERATOR_TOML)), AUTO_HEAL_ON, WITH_EDGE_ZERO_CORE_TOML, ApplyRoute.APPLY);

            assertThat(leader.isSuccess()).as("unrouted, LEADER admitted: %s", leader).isTrue();
            assertThat(apply.isSuccess()).as("unrouted, APPLY admitted: %s", apply).isTrue();
        }

        @Test
        void nonzeroCoreAddRole_autoHealOff_leaderClaim_stillRoutedToApply() {
            var store = storeWith(committed(OPERATOR_TOML));

            var cause = refusedWith(routed(store, AUTO_HEAL_OFF, WITH_EDGE_CORE_TOML, ApplyRoute.LEADER), ClusterConfigError.RouteMismatch.class);

            assertThat(cause.required()).isEqualTo(ApplyRoute.APPLY);
            assertThat(cause.action()).isInstanceOf(DiffAction.AddRole.class);
            assertUnchanged(store);
        }

        @Test
        void countOneCoreAddRole_autoHealOff_leaderClaim_stillRoutedToApply() {
            var twoCoreBase = OPERATOR_TOML.replace("count = 3", "count = 2");
            var store = storeWith(committed(twoCoreBase));

            var cause = refusedWith(routed(store, AUTO_HEAL_OFF, twoCoreBase + EDGE_SOURCE + EDGE_CORE.replace("count = 2", "count = 1"), ApplyRoute.LEADER),
                                    ClusterConfigError.RouteMismatch.class);

            assertThat(cause.required()).isEqualTo(ApplyRoute.APPLY);
            assertThat(cause.action()).isInstanceOf(DiffAction.AddRole.class);
            assertUnchanged(store);
        }

        @Test
        void zeroCoreAddRoleOnSecondSource_withCoreScaleUp_autoHealOn_refusedByDesiredCoreSources() {
            var store = storeWith(committed(OPERATOR_TOML));

            var cause = refusedWith(routed(store, AUTO_HEAL_ON, CORE_SCALED_TOML + EDGE_SOURCE + EDGE_CORE.replace("count = 2", "count = 0"), ApplyRoute.LEADER),
                                    ClusterConfigError.MultiSourceCoreScaleRefused.class);

            assertThat(cause.coreSources()).containsExactly("edge", "hetzner");
            assertUnchanged(store);
        }

        @Test
        void removingSecondCoreSource_withCoreScaleUp_autoHealOn_admittedByDesiredCoreSources() {
            var store = storeWith(committed(WITH_EDGE_ZERO_CORE_TOML));

            var result = routed(store, AUTO_HEAL_ON, CORE_SCALED_TOML, ApplyRoute.LEADER);

            assertThat(result.isSuccess()).as("the post-commit config has one core source: %s", result).isTrue();
        }

        @Test
        void coreScaleUp_multiSource_autoHealOff_acceptedOnApply() {
            var store = storeWith(committed(WITH_EDGE_CORE_TOML));

            var result = routed(store, AUTO_HEAL_OFF, WITH_EDGE_CORE_TOML.replace("count = 3", "count = 5"), ApplyRoute.APPLY);

            assertThat(result.isSuccess()).as("with auto-heal off apply creates the cores, whatever the source count: %s", result).isTrue();
        }

        @Test
        void noRoute_structuralStillRefused() {
            var store = storeWith(committed(CORE_ONLY_TOML));

            var result = applyWith(store, AUTO_HEAL_ON, NO_EXPLICIT_COMMUNITIES, new ApplyConfigRequest(OPERATOR_TOML, STORED_VERSION, Option.none()));

            refusedWith(result, ClusterConfigError.UnsupportedApplyAction.class);
            assertUnchanged(store);
        }

        @Test
        void wildcardVersionWithRoute_refused() {
            var store = storeWith(committed(OPERATOR_TOML));

            var scale = applyWith(store, AUTO_HEAL_ON, NO_EXPLICIT_COMMUNITIES, new ApplyConfigRequest(SCALED_TOML, 0, Option.some(ApplyRoute.LEADER)));
            var identical = applyWith(store,
                                      AUTO_HEAL_ON,
                                      NO_EXPLICIT_COMMUNITIES,
                                      new ApplyConfigRequest(OPERATOR_TOML, 0, Option.some(ApplyRoute.LEADER)));

            refusedWith(scale, ClusterConfigError.UnfencedOverwrite.class);
            refusedWith(identical, ClusterConfigError.UnfencedOverwrite.class);
            assertUnchanged(store);
        }

        @Test
        void sourceFieldChange_sourceWithWorkerRole_refused() {
            var store = storeWith(committed(OPERATOR_TOML));

            var cause = refusedWith(routed(store, AUTO_HEAL_OFF, REGION_CHANGED_TOML, ApplyRoute.APPLY),
                                    ClusterConfigError.SourceReplaceRefused.class);

            assertThat(cause.field()).isEqualTo("region");
            assertThat(cause.message()).contains("disable auto-heal, or move the roles to a new source");
            assertUnchanged(store);
        }

        @Test
        void sourceFieldChange_coreOnlySource_autoHealOn_refused() {
            var store = storeWith(committed(CORE_ONLY_TOML));

            refusedWith(routed(store, AUTO_HEAL_ON, CORE_ONLY_TOML.replace("eu-central", "eu-west"), ApplyRoute.LEADER),
                        ClusterConfigError.SourceReplaceRefused.class);
            assertUnchanged(store);
        }

        @Test
        void sourceFieldChange_coreOnlySource_autoHealOff_leaderClaim_refused() {
            var store = storeWith(committed(CORE_ONLY_TOML));

            var cause = refusedWith(routed(store, AUTO_HEAL_OFF, CORE_ONLY_TOML.replace("eu-central", "eu-west"), ApplyRoute.LEADER),
                                    ClusterConfigError.RouteMismatch.class);

            assertThat(cause.required()).isEqualTo(ApplyRoute.APPLY);
            assertUnchanged(store);
        }

        /// The source gains a worker in the same plan: the replace would reprovision it too, so the desired config counts.
        @Test
        void sourceFieldChange_workerAddedInSamePlan_refused() {
            var store = storeWith(committed(CORE_ONLY_TOML));

            refusedWith(routed(store, AUTO_HEAL_OFF, REGION_CHANGED_TOML, ApplyRoute.APPLY), ClusterConfigError.SourceReplaceRefused.class);
            assertUnchanged(store);
        }

        @Test
        void sourceFieldChange_coreOnlySource_autoHealOff_acceptedOnApply() {
            var store = storeWith(committed(CORE_ONLY_TOML));

            var result = routed(store, AUTO_HEAL_OFF, CORE_ONLY_TOML.replace("eu-central", "eu-west"), ApplyRoute.APPLY);

            assertThat(result.isSuccess()).as("a core-only source with auto-heal off is replaced by apply: %s", result).isTrue();
        }

        @Test
        void workerRemoveRole_refusedWithNextStep() {
            var store = storeWith(committed(OPERATOR_TOML));

            var cause = refusedWith(routed(store, AUTO_HEAL_ON, CORE_ONLY_TOML, ApplyRoute.LEADER),
                                    ClusterConfigError.LivePoolRemovalRefused.class);

            assertThat(cause.count()).isEqualTo(2);
            assertThat(cause.message()).contains("Set hetzner.worker count to 0, apply, wait until `aether cluster status` shows 0 live");
            assertThat(cause.httpStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertUnchanged(store);
        }

        @Test
        void workerRemoveRole_afterScaleToZero_acceptedAsConfig() {
            var store = storeWith(committed(WORKER_ZERO_TOML));

            var result = routed(store, AUTO_HEAL_ON, CORE_ONLY_TOML, ApplyRoute.LEADER);

            assertThat(result.isSuccess()).as("a zero-count removal changes no pool: %s", result).isTrue();
            assertThat(committedConfig(store).tomlContent()).isEqualTo(Option.some(CORE_ONLY_TOML));
        }

        @Test
        void workerRemoveSource_refusedWithNextStep() {
            var store = storeWith(committed(WITH_EDGE_WORKER_TOML));

            var cause = refusedWith(routed(store, AUTO_HEAL_ON, OPERATOR_TOML, ApplyRoute.LEADER),
                                    ClusterConfigError.LivePoolRemovalRefused.class);

            assertThat(cause.sourceName()).isEqualTo("edge");
            assertThat(cause.count()).isEqualTo(1);
            assertUnchanged(store);
        }

        @Test
        void workerRemoveSource_afterScaleToZero_acceptedAsConfig() {
            var store = storeWith(committed(WITH_EDGE_WORKER_TOML.replace("count = 1", "count = 0")));

            var result = routed(store, AUTO_HEAL_ON, OPERATOR_TOML, ApplyRoute.APPLY);

            assertThat(result.isSuccess()).as("a source whose roles are all at zero is config-only, whatever the claim: %s", result).isTrue();
        }

        @Test
        void coreAddSource_leaderRoute_refused() {
            var store = storeWith(committed(OPERATOR_TOML));

            var cause = refusedWith(routed(store, AUTO_HEAL_ON, WITH_EDGE_CORE_TOML, ApplyRoute.LEADER), ClusterConfigError.CoreFillRefused.class);

            assertThat(cause.sourceName()).isEqualTo("edge");
            assertThat(cause.message()).contains("disable auto-heal, apply, re-enable");
            assertUnchanged(store);
        }

        @Test
        void coreScaleUp_leaderRoute_multiSource_refused() {
            var store = storeWith(committed(WITH_EDGE_CORE_TOML));

            var cause = refusedWith(routed(store, AUTO_HEAL_ON, WITH_EDGE_CORE_TOML.replace("count = 3", "count = 5"), ApplyRoute.LEADER),
                                    ClusterConfigError.MultiSourceCoreScaleRefused.class);

            assertThat(cause.coreSources()).containsExactly("edge", "hetzner");
            assertThat(cause.message()).contains("disable auto-heal, apply, re-enable");
            assertUnchanged(store);
        }

        @Test
        void sourceFieldChange_withWorkerRemovedInSamePlan_refusedAsSourceReplace() {
            var store = storeWith(committed(OPERATOR_TOML));

            var result = routed(store, AUTO_HEAL_OFF, WORKER_ZERO_TOML.replace("eu-central", "eu-west"), ApplyRoute.APPLY);

            refusedWith(result, ClusterConfigError.SourceReplaceRefused.class);
            assertUnchanged(store);
        }

        @Test
        void workerScaleUp_leaderRoute_multiSourceCore_accepted() {
            var store = storeWith(committed(WITH_EDGE_CORE_TOML));

            var result = routed(store, AUTO_HEAL_ON, SCALED_TOML + EDGE_SOURCE + EDGE_CORE, ApplyRoute.LEADER);

            assertThat(result.isSuccess()).as("only a core scale-up is refused over several core sources: %s", result).isTrue();
        }

        @Test
        void leaderRoute_workerAddRole_explicitCommunities_refused() {
            var store = storeWith(committed(CORE_ONLY_TOML));

            var result = applyWith(store,
                                   AUTO_HEAL_ON,
                                   EXPLICIT_COMMUNITIES,
                                   new ApplyConfigRequest(OPERATOR_TOML, STORED_VERSION, Option.some(ApplyRoute.LEADER)));

            assertThat(refusedWith(result, ClusterConfigError.ParseFailed.class).message()).contains("Explicit communities own worker capacity");
            assertUnchanged(store);
        }

        @Test
        void versionChangeWithRoute_staysRefused() {
            var store = storeWith(committed(OPERATOR_TOML));

            refusedWith(routed(store, AUTO_HEAL_ON, OPERATOR_TOML.replace("\nversion = \"1.0.0\"", "\nversion = \"1.1.0\""), ApplyRoute.LEADER),
                        ClusterConfigError.VersionChangeViaApply.class);
            assertUnchanged(store);
        }

        /// N3: every definitive refusal reachable with `route` present renders a JSON `code`, and the codes are the
        /// client contract (B2's fake server answers exactly these). The transient 409 / 503 stay untyped, so a client
        /// that classifies by code keeps them retryable.
        @Test
        void everyRoutePathRefusal_carriesCode() {
            var scale = new DiffAction.ScaleUp(SourceName.sourceNameOrDefault("hetzner"),
                                               NodeRole.CORE,
                                               3,
                                               5);
            var refusals = Map.<Cause, String> ofEntries(Map.entry(new ClusterConfigError.ImmutableFieldChange("cluster.name"), "ImmutableFieldChange"),
                                                         Map.entry(new ClusterConfigError.ValidationFailed(List.of()), "ValidationFailed"),
                                                         Map.entry(new ClusterConfigError.VersionChangeViaApply("1.0.0", "1.1.0"), "VersionChangeViaApply"),
                                                         Map.entry(new ClusterConfigError.RuntimeChangeViaApply("hetzner", "core", "a", "b"), "RuntimeChangeViaApply"),
                                                         Map.entry(new ClusterConfigError.RuntimeProfileChangeViaApply("node", "image"), "RuntimeProfileChangeViaApply"),
                                                         Map.entry(new ClusterConfigError.UnsupportedApplyAction(scale), "UnsupportedApplyAction"),
                                                         Map.entry(new ClusterConfigError.ParseFailed("explicit communities / retirement guard"), "ParseFailed"),
                                                         Map.entry(new ClusterConfigError.UnfencedOverwrite(5), "UnfencedOverwrite"),
                                                         Map.entry(new ClusterConfigError.VersionConflict(5, 6), "VersionConflict"),
                                                         Map.entry(new ClusterConfigError.AutoHealChanged(ClusterConfigError.AutoHealSetting.ABSENT, ClusterConfigError.AutoHealSetting.DISABLED), "AutoHealChanged"),
                                                         Map.entry(new ClusterConfigError.RouteMismatch(ApplyRoute.LEADER, ApplyRoute.APPLY, scale), "RouteMismatch"),
                                                         Map.entry(new ClusterConfigError.SourceReplaceRefused("hetzner", "region"), "SourceReplaceRefused"),
                                                         Map.entry(new ClusterConfigError.LivePoolRemovalRefused("hetzner", "worker", 2), "LivePoolRemovalRefused"),
                                                         Map.entry(new ClusterConfigError.CoreFillRefused("edge", 2), "CoreFillRefused"),
                                                         Map.entry(new ClusterConfigError.MultiSourceCoreScaleRefused("hetzner", List.of("edge", "hetzner")), "MultiSourceCoreScaleRefused"),
                                                         Map.entry(new ClusterReplication.ClusterEventsFactorsRefused(3, new ClusterConfigError.ParseFailed("x")), "ClusterEventsFactorsRefused"));

            refusals.forEach((cause, code) -> assertThat(problemBody(cause)).as("code of %s", cause.getClass().getSimpleName())
                                                                            .contains("\"code\":\"" + code + "\""));
            assertThat(problemBody(new ManagementServerError.Conflict("Current core leader required for config update")))
                .as("the transient leader-required 409 stays untyped")
                .doesNotContain("\"code\"");
            assertThat(problemBody(new ManagementServerError.ServiceUnavailable("election")))
                .as("the transient election 503 stays untyped")
                .doesNotContain("\"code\"");
        }

        @Test
        void applyConfigRequest_withoutRoute_decodesAsNone() {
            var request = decode("{\"tomlContent\":\"x\",\"expectedVersion\":3}");

            assertThat(request.route()).isEqualTo(Option.none());
            assertThat(request.expectedVersion()).isEqualTo(3);
        }

        @Test
        void applyConfigRequest_withRoute_decodesTheClaim() {
            assertThat(decode("{\"tomlContent\":\"x\",\"expectedVersion\":3,\"route\":\"APPLY\"}").route())
                .isEqualTo(Option.some(ApplyRoute.APPLY));
        }

        private static ApplyConfigRequest decode(String json) {
            return JsonCodecAdapter.defaultCodec()
                                                               .deserialize(json.getBytes(StandardCharsets.UTF_8),
                                                                            new TypeToken<ApplyConfigRequest>() {})
                                                               .onFailure(cause -> fail(cause.message()))
                                                               .unwrap();
        }

        /// The body exactly as the management router writes a route failure.
        private static String problemBody(Cause cause) {
            var body = new AtomicReference<String>();

            ProblemResponses.writeProblem(new ResponseWriter() {
                @Override
                public void write(HttpStatus status, byte[] bytes, ContentType contentType) {
                    body.set(new String(bytes, StandardCharsets.UTF_8));
                }

                @Override
                public ResponseWriter header(String name, String value) {
                    return this;
                }
            }, cause, "/api/v1/cluster/config", "req-1");

            return body.get();
        }

        private static ClusterConfigValue committed(String toml) {
            return ClusterConfigValue.clusterConfigValue(toml,
                                                         "prod",
                                                         "1.0.0",
                                                         List.of(CORE_ENTRY, WORKER_ENTRY),
                                                         3,
                                                         3,
                                                         "cloud",
                                                         STORED_VERSION);
        }

        private static Result<Object> routed(TestKVStore store, boolean autoHeal, String desired, ApplyRoute route) {
            return applyWith(store, autoHeal, NO_EXPLICIT_COMMUNITIES, new ApplyConfigRequest(desired, STORED_VERSION, Option.some(route)));
        }

        private static <C extends Cause> C refusedWith(Result<Object> result, Class<C> type) {
            assertThat(result.isFailure()).as("expected a %s refusal, got %s", type.getSimpleName(), result).isTrue();

            var cause = result.fold(failure -> failure, value -> fail("unreachable: " + value));

            assertThat(cause).isInstanceOf(type);

            return type.cast(cause);
        }

        private static void assertUnchanged(TestKVStore store) {
            assertThat(committedConfig(store).configVersion()).as("a refused apply commits nothing").isEqualTo(STORED_VERSION);
        }
    }

    /// #686 A2 (M7, S8, S1, S9): the write is fenced on the exact values the request was checked against. Each test drives the
    /// forced order through [ClusterConfigRoutes.ApplyCommitHook]: a competing write lands after the checks and before the
    /// submit, and every test asserts the hook ran, so a pass is known to have exercised the race.
    @Nested
    class WriteTimeFence {
        private static final long STORED_VERSION = 5;
        private static final String COMPETING_WRITER = "competing-writer";
        private static final AutoHealStateValue AUTO_HEAL_DISABLED = AutoHealStateValue.autoHealStateValue(false, "operator", 1L);
        private static final AutoHealStateValue AUTO_HEAL_ENABLED = AutoHealStateValue.autoHealStateValue(true, "operator", 2L);

        @Test
        void autoHealFlipBetweenCheckAndWrite_transactionFails() {
            var store = storeWith(configAt(STORED_VERSION));
            var ran = new AtomicBoolean();

            store.seed(AetherKey.AutoHealStateKey.SINGLETON, AUTO_HEAL_DISABLED);

            var cause = refusal(routedApply(store, hook(ran, () -> store.seed(AetherKey.AutoHealStateKey.SINGLETON, AUTO_HEAL_ENABLED))),
                                ClusterConfigError.AutoHealChanged.class);

            assertThat(ran).as("instrument: the competing flip must have been injected").isTrue();
            assertThat(cause.observed()).isEqualTo(ClusterConfigError.AutoHealSetting.DISABLED);
            assertThat(cause.now()).isEqualTo(ClusterConfigError.AutoHealSetting.ENABLED);
            assertThat(committedConfig(store).configVersion()).as("the refused apply commits nothing").isEqualTo(STORED_VERSION);
        }

        @Test
        void storedChangedBetweenCheckAndWrite_refused() {
            var store = storeWith(configAt(STORED_VERSION));
            var ran = new AtomicBoolean();

            var cause = refusal(routedApply(store, hook(ran, () -> store.seed(ClusterConfigKey.CURRENT, otherContentAt(STORED_VERSION)))),
                                ClusterConfigError.VersionConflict.class);

            assertThat(ran).as("instrument: the competing config write must have been injected").isTrue();
            assertThat(cause.expected()).isEqualTo(STORED_VERSION);
            assertThat(cause.actual()).as("same version, other content: only the fenced expected value can tell").isEqualTo(STORED_VERSION);
            assertThat(committedConfig(store).deploymentType()).as("the competing write survives").isEqualTo(COMPETING_WRITER);
        }

        @Test
        void autoHealKeyAbsent_witnessedAsNone_flipRefuses() {
            var store = storeWith(configAt(STORED_VERSION));
            var ran = new AtomicBoolean();

            var cause = refusal(routedApply(store, hook(ran, () -> store.seed(AetherKey.AutoHealStateKey.SINGLETON, AUTO_HEAL_ENABLED))),
                                ClusterConfigError.AutoHealChanged.class);

            assertThat(ran).as("instrument: the first write of the key must have been injected").isTrue();
            assertThat(cause.observed()).as("an absent key is witnessed as none, not skipped").isEqualTo(ClusterConfigError.AutoHealSetting.ABSENT);
            assertThat(cause.now()).isEqualTo(ClusterConfigError.AutoHealSetting.ENABLED);
        }

        @Test
        void refusedTransaction_reportsAutoHealChanged() {
            var store = storeWith(configAt(STORED_VERSION));
            var ran = new AtomicBoolean();

            store.seed(AetherKey.AutoHealStateKey.SINGLETON, AUTO_HEAL_DISABLED);

            var cause = refusal(routedApply(store, hook(ran, () -> store.seed(AetherKey.AutoHealStateKey.SINGLETON, AUTO_HEAL_ENABLED))),
                                ClusterConfigError.class);

            assertThat(ran).isTrue();
            assertThat(cause.code()).as("a flip is not reported as a version conflict").isEqualTo("AutoHealChanged");
            assertThat(cause.httpStatus()).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        void routedApply_withNoCompetingWrite_lands() {
            var store = storeWith(configAt(STORED_VERSION));
            var ran = new AtomicBoolean();

            store.seed(AetherKey.AutoHealStateKey.SINGLETON, AUTO_HEAL_DISABLED);

            var result = routedApply(store, hook(ran, () -> {}));

            assertThat(ran).isTrue();
            assertThat(result.isSuccess()).as("control: without the competing write the same request lands: %s", result).isTrue();
            assertThat(committedConfig(store).configVersion()).isEqualTo(STORED_VERSION + 1);
        }

        @Test
        void scale_storedChangedBetweenCheckAndWrite_refused() {
            var store = storeWith(configAt(STORED_VERSION));
            var ran = new AtomicBoolean();

            var result = ClusterConfigRoutes.clusterConfigRoutes(() -> nodeWith(store),
                                                                 ClusterConfigApplier.NoTopologyManager.INSTANCE,
                                                                 hook(ran, () -> store.seed(ClusterConfigKey.CURRENT, otherContentAt(STORED_VERSION))))
                                            .handleScale(new ScaleRequest("hetzner", "worker", 4, STORED_VERSION))
                                            .await();

            assertThat(ran).as("instrument: the competing write must have been injected").isTrue();
            assertThat(refusal(result, ClusterConfigError.VersionConflict.class).actual()).isEqualTo(STORED_VERSION);
            assertThat(committedConfig(store).deploymentType()).isEqualTo(COMPETING_WRITER);
        }

        @Test
        void upgrade_storedChangedBetweenCheckAndWrite_refused() {
            var store = storeWith(bootstrapSeed());
            var ran = new AtomicBoolean();

            var result = ClusterConfigRoutes.clusterConfigRoutes(() -> nodeWith(store),
                                                                 ClusterConfigApplier.NoTopologyManager.INSTANCE,
                                                                 hook(ran, () -> store.seed(ClusterConfigKey.CURRENT, otherContentAt(1L))))
                                            .handleUpgrade(new UpgradeRequest("1.1.0", 1L))
                                            .await();

            assertThat(ran).as("instrument: the competing write must have been injected").isTrue();
            assertThat(refusal(result, ClusterConfigError.VersionConflict.class).actual()).isEqualTo(1L);
            assertThat(committedConfig(store).version()).as("the refused upgrade must not land").isEqualTo("1.0.0");
        }

        /// The witness is for the route check's own decision: an unrouted apply decides nothing on the auto-heal value, so a
        /// flip of it is none of that write's business.
        @Test
        void unroutedApply_whileAutoHealFlips_lands() {
            var store = storeWith(configAt(STORED_VERSION));
            var ran = new AtomicBoolean();

            store.seed(AetherKey.AutoHealStateKey.SINGLETON, AUTO_HEAL_DISABLED);

            var result = unroutedApply(store,
                                       SCALED_TOML,
                                       hook(ran, () -> store.seed(AetherKey.AutoHealStateKey.SINGLETON, AUTO_HEAL_ENABLED)));

            assertThat(ran).as("instrument: the auto-heal flip must have been injected").isTrue();
            assertThat(result.isSuccess()).as("an unrouted apply is not refused by an auto-heal flip: %s", result).isTrue();
            assertThat(committedConfig(store).configVersion()).isEqualTo(STORED_VERSION + 1);
        }

        /// S8 ordering: the witness is read BEFORE the route check reads the auto-heal value. The flip lands inside the route
        /// check itself, after the witness read and before the check's answer; a witness taken after the check would
        /// already hold the flipped value and the write would land on a decision made against the old one.
        @Test
        void autoHealFlipDuringRouteCheck_refused() {
            var store = storeWith(configAt(STORED_VERSION));
            var ran = new AtomicBoolean();
            var flipped = new AtomicBoolean();

            store.seed(AetherKey.AutoHealStateKey.SINGLETON, AUTO_HEAL_DISABLED);

            var applier = ClusterConfigApplier.clusterConfigApplier(topologyManager(AUTO_HEAL_OFF,
                                                                                    NO_EXPLICIT_COMMUNITIES,
                                                                                    () -> flipDuringCheck(store, flipped)));
            var cause = refusal(routedApply(store, applier, hook(ran, () -> {})), ClusterConfigError.AutoHealChanged.class);

            assertThat(flipped).as("instrument: the flip must have been injected inside the route check").isTrue();
            assertThat(cause.observed()).as("the witness predates the route check's read").isEqualTo(ClusterConfigError.AutoHealSetting.DISABLED);
            assertThat(cause.now()).isEqualTo(ClusterConfigError.AutoHealSetting.ENABLED);
        }

        @Test
        void retirement_withNoCompetingChange_lands() {
            var store = retiringCommunityStore();
            var ran = new AtomicBoolean();

            var result = retirementApply(store, hook(ran, () -> {}));

            assertThat(ran).isTrue();
            assertThat(result.isSuccess()).as("control: the retirement lands when nothing moves: %s", result).isTrue();
            assertThat(committedConfig(store).configVersion()).isEqualTo(STORED_VERSION + 1);
        }

        /// The transaction itself is refused because the retired community's key moved after the guards were taken, and the
        /// moved value no longer qualifies: the refusal is the retirement's own, not a version conflict.
        @Test
        void retirementReverted_afterGuardsTaken_refusedAsRetirementChange() {
            var store = retiringCommunityStore();
            var submitted = new AtomicBoolean();

            store.beforeTransaction(() -> {
                submitted.set(true);
                store.seed(COMMUNITY_KEY, community(1, 0L));
            });

            var cause = refusal(retirementApply(store, hook(new AtomicBoolean(), () -> {})), ClusterConfigError.ParseFailed.class);

            assertThat(submitted).as("instrument: the community must have moved during the submit").isTrue();
            assertThat(cause.message()).contains("Community retirement changed before config commit: " + COMMUNITY_ID);
            assertThat(committedConfig(store).configVersion()).as("the refused retirement commits nothing").isEqualTo(STORED_VERSION);
        }

        /// The guard moved but the retirement still qualifies (an ABA), so nothing names a cause: a conflict, never a success
        /// and never a retirement refusal. With the guards dropped from the transaction this would land.
        @Test
        void retirementGuardMoved_stillQualifying_conflictNeverSuccess() {
            var store = retiringCommunityStore();
            var submitted = new AtomicBoolean();

            store.beforeTransaction(() -> {
                submitted.set(true);
                store.seed(COMMUNITY_KEY, community(0, 7L));
            });

            var cause = refusal(retirementApply(store, hook(new AtomicBoolean(), () -> {})), ClusterConfigError.VersionConflict.class);

            assertThat(submitted).as("instrument: the community must have moved during the submit").isTrue();
            assertThat(cause.expected()).isEqualTo(STORED_VERSION);
            assertThat(cause.actual()).as("the intended version: the stored one never moved").isEqualTo(STORED_VERSION + 1);
            assertThat(committedConfig(store).configVersion()).isEqualTo(STORED_VERSION);
        }

        /// The retirement guards are taken from the config the request was checked against. A competing write that replaces it
        /// (and un-retires the community) must not make the guards vanish with it.
        @Test
        void retirementChangedWithStored_guardsFollowTheCheckedConfig() {
            var store = retiringCommunityStore();
            var ran = new AtomicBoolean();

            var cause = refusal(retirementApply(store,
                                                hook(ran,
                                                     () -> {
                                                         store.seed(ClusterConfigKey.CURRENT, otherContentAt(STORED_VERSION));
                                                         store.seed(COMMUNITY_KEY, community(1, 0L));
                                                     })),
                                ClusterConfigError.ParseFailed.class);

            assertThat(ran).as("instrument: the competing write must have been injected").isTrue();
            assertThat(cause.message()).contains("Community retirement changed before config commit: " + COMMUNITY_ID);
        }

        private static final String COMMUNITY_ID = "legacy";
        private static final AetherKey COMMUNITY_KEY = new AetherKey.CommunityKey(COMMUNITY_ID);
        private static final String WITH_COMMUNITY_TOML = OPERATOR_TOML + """

            [community.legacy]
            target_size = 0
            [community.legacy.placement.first]
            source = "hetzner"
            minimum = 0
            weight = 1
            """;

        private static CommunityValue community(int targetSize, long createdAt) {
            return new CommunityValue("hetzner", "worker", targetSize, CommunityState.DISSOLVED, createdAt, Option.none());
        }

        /// Stored config declares the community; the community is already DISSOLVED with target 0, so dropping it is admissible.
        private static TestKVStore retiringCommunityStore() {
            var store = storeWith(ClusterConfigValue.clusterConfigValue(WITH_COMMUNITY_TOML,
                                                                        "prod",
                                                                        "1.0.0",
                                                                        List.of(CORE_ENTRY, WORKER_ENTRY),
                                                                        3,
                                                                        3,
                                                                        "cloud",
                                                                        STORED_VERSION));

            store.seed(COMMUNITY_KEY, community(0, 0L));

            return store;
        }

        /// Drops the declared community, with the final-community rule satisfied by zeroing the legacy worker count.
        private static Result<Object> retirementApply(TestKVStore store, ClusterConfigRoutes.ApplyCommitHook hook) {
            return unroutedApply(store, OPERATOR_TOML.replace("count = 2", "count = 0"), hook);
        }

        private static Result<Object> unroutedApply(TestKVStore store, String desired, ClusterConfigRoutes.ApplyCommitHook hook) {
            var applier = ClusterConfigApplier.clusterConfigApplier(topologyManager(AUTO_HEAL_OFF, NO_EXPLICIT_COMMUNITIES));

            return ClusterConfigRoutes.clusterConfigRoutes(() -> nodeWith(store), applier, hook)
                                      .handleApplyConfig(new ApplyConfigRequest(desired, STORED_VERSION, Option.none()))
                                      .await();
        }

        private static void flipDuringCheck(TestKVStore store, AtomicBoolean flipped) {
            flipped.set(true);
            store.seed(AetherKey.AutoHealStateKey.SINGLETON, AUTO_HEAL_ENABLED);
        }

        private static ClusterConfigRoutes.ApplyCommitHook hook(AtomicBoolean ran, Runnable competingWrite) {
            return () -> {
                ran.set(true);
                competingWrite.run();

                return Promise.success(Unit.unit());
            };
        }

        /// A core AddRole on a second source with auto-heal off: the APPLY route, which witnesses the auto-heal value.
        private static Result<Object> routedApply(TestKVStore store, ClusterConfigRoutes.ApplyCommitHook hook) {
            return routedApply(store, ClusterConfigApplier.clusterConfigApplier(topologyManager(AUTO_HEAL_OFF, NO_EXPLICIT_COMMUNITIES)), hook);
        }

        private static Result<Object> routedApply(TestKVStore store, ClusterConfigApplier applier, ClusterConfigRoutes.ApplyCommitHook hook) {
            var desired = OPERATOR_TOML + RouteCheck.EDGE_SOURCE + RouteCheck.EDGE_CORE;

            return ClusterConfigRoutes.clusterConfigRoutes(() -> nodeWith(store), applier, hook)
                                      .handleApplyConfig(new ApplyConfigRequest(desired, STORED_VERSION, Option.some(ApplyRoute.APPLY)))
                                      .await();
        }

        private static ClusterConfigValue configAt(long configVersion) {
            return committedOperatorConfig(configVersion);
        }

        /// Same version as the stored config, different content: a re-read at write time would adopt it and pass the CAS.
        private static ClusterConfigValue otherContentAt(long configVersion) {
            return ClusterConfigValue.bootstrapSeed("prod", "1.0.0", List.of(new TopologyEntry("", TopologyEntry.CORE_ROLE, 5)), 5, 5, COMPETING_WRITER, configVersion);
        }

        private static <C extends Cause> C refusal(Result<?> result, Class<C> type) {
            assertThat(result.isFailure()).as("expected a %s refusal, got %s", type.getSimpleName(), result).isTrue();

            var cause = result.fold(failure -> failure, value -> fail("unreachable: " + value));

            assertThat(cause).isInstanceOf(type);

            return type.cast(cause);
        }
    }

    private static final boolean AUTO_HEAL_ON = true;
    private static final boolean AUTO_HEAL_OFF = false;
    private static final boolean EXPLICIT_COMMUNITIES = true;
    private static final boolean NO_EXPLICIT_COMMUNITIES = false;

    private static Result<Object> applyWith(TestKVStore store, boolean autoHeal, boolean explicitCommunities, ApplyConfigRequest request) {
        var applier = ClusterConfigApplier.clusterConfigApplier(topologyManager(autoHeal, explicitCommunities));

        return ClusterConfigRoutes.clusterConfigRoutes(() -> nodeWith(store), applier)
                                  .handleApplyConfig(request)
                                  .await();
    }

    /// The two reads the route check makes of the topology manager; any other call is a test bug.
    private static ClusterTopologyManager topologyManager(boolean autoHeal, boolean explicitCommunities) {
        return topologyManager(autoHeal, explicitCommunities, () -> {});
    }

    /// `onAutoHealRead` runs inside the route check's read of the auto-heal value, before it answers.
    private static ClusterTopologyManager topologyManager(boolean autoHeal, boolean explicitCommunities, Runnable onAutoHealRead) {
        return (ClusterTopologyManager) Proxy.newProxyInstance(ClusterTopologyManager.class.getClassLoader(),
                                                               new Class[]{ClusterTopologyManager.class},
                                                               (_, method, _) -> switch (method.getName()) {
                                                                   case "isAutoHealEnabled" -> {
                                                                       onAutoHealRead.run();
                                                                       yield autoHeal;
                                                                   }
                                                                   case "usesExplicitCommunities" -> explicitCommunities;
                                                                   default -> throw new UnsupportedOperationException("Not implemented in test proxy: " + method.getName());
                                                               });
    }

    private static Result<Object> apply(TestKVStore store, ApplyConfigRequest request) {
        return ClusterConfigRoutes.clusterConfigRoutes(() -> nodeWith(store))
                                  .handleApplyConfig(request)
                                  .await();
    }

    private static ApplyConfigResponse applySucceeds(TestKVStore store, ApplyConfigRequest request) {
        return apply(store, request).onFailure(cause -> fail(cause.message()))
                                    .map(ApplyConfigResponse.class::cast)
                                    .unwrap();
    }

    private static ClusterConfigValue committedConfig(TestKVStore store) {
        return store.get(ClusterConfigKey.CURRENT)
                    .filter(ClusterConfigValue.class::isInstance)
                    .map(ClusterConfigValue.class::cast)
                    .or(() -> fail("No cluster config committed"));
    }

    private static TestKVStore storeWith(ClusterConfigValue committed) {
        var store = new TestKVStore();

        store.seed(ClusterConfigKey.CURRENT, committed);

        return store;
    }

    /// The BootstrapModule self-seed exactly as a formed cluster carries it: no TOML, a core-only
    /// topology under an EMPTY source name (the seed predates any source definition), version 1.
    private static ClusterConfigValue bootstrapSeed() {
        return ClusterConfigValue.bootstrapSeed("prod",
                                                     "1.0.0",
                                                     List.of(new TopologyEntry("", TopologyEntry.CORE_ROLE, 3)),
                                                     3,
                                                     3,
                                                     "bootstrap-seed",
                                                     1L);
    }

    private static ClusterConfigValue committedOperatorConfig(long configVersion) {
        return ClusterConfigValue.clusterConfigValue(OPERATOR_TOML,
                                                     "prod",
                                                     "1.0.0",
                                                     List.of(CORE_ENTRY, WORKER_ENTRY),
                                                     3,
                                                     3,
                                                     "cloud",
                                                     configVersion);
    }

    private static ManageableNode nodeWith(TestKVStore store) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, args) -> dispatch(store, method, args));
    }

    private static Object dispatch(TestKVStore store, Method method, Object[] args) {
        return switch (method.getName()) {
            case "kvStore" -> store;
            case "isLeader" -> true;
            case "self" -> new org.pragmatica.consensus.NodeId("core");
            case "apply" -> applyBatch(store, args);
            case "upgradeRunService" -> org.pragmatica.aether.deployment.cluster.UpgradeRunService.unavailable();
            default -> throw new UnsupportedOperationException("Not implemented in test proxy: " + method.getName());
        };
    }

    @SuppressWarnings("unchecked")
    private static Promise<List<Object>> applyBatch(TestKVStore store, Object[] args) {
        var results = ((List<KVCommand<AetherKey>>) args[0]).stream()
            .map(command -> routeCommand(store, command)).toList();
        return Promise.success(results);
    }

    private static Object routeCommand(TestKVStore store, KVCommand<AetherKey> command) {
        if (command instanceof KVCommand.LeaderTransaction<?, ?> transaction) {
            store.beforeTransaction.run();
            var mutation = transaction.mutations().getFirst();
            var key = (AetherKey) mutation.key();
            var value = (AetherValue) mutation.replacement().unwrap();
            boolean accepted = store.raceWinner.isEmpty() && store.get(key).equals(mutation.expected())
                && store.isSuccessor(key, value) && guardsHold(store, transaction);
            if (accepted || store.raceWinner.isPresent()) { store.applyPut(key, value); }
            return new KVCommand.TransactionResult(transaction.transactionId(), accepted);
        }
        return new KVCommand.TransactionResult("unexpected", false);
    }

    /// The read set of a leader transaction as the real applier judges it: every witnessed key still holds exactly the value read.
    private static boolean guardsHold(TestKVStore store, KVCommand.LeaderTransaction<?, ?> transaction) {
        return transaction.guards().stream().allMatch(guard -> store.get((AetherKey) guard.key()).equals(guard.expected()));
    }

    private static final class TestKVStore extends KVStore<AetherKey, AetherValue> {
        private final Map<AetherKey, AetherValue> storage = new HashMap<>();
        private Option<AetherValue> raceWinner = Option.none();
        private Runnable beforeTransaction = () -> {};

        private TestKVStore() {
            super(null, null, null);
        }

        /// A write that lands while a transaction is in flight: after it was built and before the applier judges its guards.
        void beforeTransaction(Runnable competingWrite) {
            beforeTransaction = competingWrite;
        }

        void seed(AetherKey key, AetherValue value) {
            storage.put(key, value);
        }

        /// Model a competing writer that wins the race: our `Put` is discarded and a re-read observes
        /// the competitor's value instead. This is the rejection shape the apply result cannot show —
        /// under batch merging every submitter receives the full merged result list — and the reason
        /// [ClusterConfigRoutes] must confirm a write by re-reading committed state.
        void loseWritesTo(AetherValue winner) {
            raceWinner = Option.some(winner);
        }

        void applyPut(AetherKey key, AetherValue value) {
            raceWinner.onPresent(winner -> storage.put(key, winner));

            if (raceWinner.isEmpty() && isSuccessor(key, value)) {
                storage.put(key, value);
            }
        }

        /// The RFC-0018 successor fence as the real applier enforces it: a `VersionFenced` write lands
        /// only when its version is the IMMEDIATE successor of the committed one, and a first write
        /// against an absent key always lands. Equal versions are rejected — which is exactly what
        /// happened live, where a version-1 initial store met a committed version-1 seed.
        private boolean isSuccessor(AetherKey key, AetherValue value) {
            if (!(storage.get(key) instanceof ClusterConfigValue stored)
                || !(value instanceof ClusterConfigValue incoming)) {
                return true;
            }

            return incoming.configVersion() == stored.configVersion() + 1;
        }

        @Override
        public <VV> Option<VV> getTyped(org.pragmatica.cluster.state.kvstore.StructuredKey key, Class<VV> type) {
            if (key == org.pragmatica.cluster.state.kvstore.LeaderKey.INSTANCE) {
                return Option.some(type.cast(new org.pragmatica.cluster.state.kvstore.LeaderValue(
                    new org.pragmatica.consensus.NodeId("core"), 1)));
            }
            return Option.option(storage.get(key)).filter(type::isInstance).map(type::cast);
        }

        @Override
        public Map<AetherKey, AetherValue> snapshot() {
            return new HashMap<>(storage);
        }

        @Override
        public Option<AetherValue> get(AetherKey key) {
            return Option.option(storage.get(key));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <KK, VV> void forEach(Class<KK> keyClass, Class<VV> valueClass, BiConsumer<KK, VV> consumer) {
            storage.forEach((key, value) -> {
                if (keyClass.isInstance(key) && valueClass.isInstance(value)) {
                    consumer.accept((KK) key, (VV) value);
                }
            });
        }
    }
}
