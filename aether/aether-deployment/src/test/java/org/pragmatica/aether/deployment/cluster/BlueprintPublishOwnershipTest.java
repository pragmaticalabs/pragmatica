// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactId;
import org.pragmatica.aether.artifact.GroupId;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.deployment.schema.SchemaError;
import org.pragmatica.aether.deployment.validation.StreamResourceValidator;
import org.pragmatica.aether.deployment.validation.MissingConfigSection;
import org.pragmatica.aether.resource.artifact.ArtifactFile;
import org.pragmatica.aether.resource.artifact.ArtifactStore;
import org.pragmatica.aether.slice.SliceManifest;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.BlueprintStreamBindingsKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.BlueprintStreamBindingsValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.stream.BlueprintStreamAddresses;
import org.pragmatica.aether.slice.stream.StreamAddressError;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.VersionFenced;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.LayeredConfigProvider;
import org.pragmatica.config.NamedConfigProvider;
import org.pragmatica.config.source.MapConfigSource;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.assertj.core.api.InstanceOfAssertFactories;

import static org.assertj.core.api.Assertions.assertThat;

/// #542 — the deploy-time single-migrator gate.
///
/// Datasource names are cluster-global: `BlueprintArtifactParser` maps `schema/V001__*.sql` to
/// `"database"` for EVERY blueprint using the default layout, and that name resolves to one physical
/// database through the node-global config. Two blueprints migrating it interleave unrelated version
/// sequences, so the publish that would become the second migrator is refused before any command is
/// applied. Sharing a datasource for reads and writes stays legal — only duplicate migration
/// ownership is rejected.
class BlueprintPublishOwnershipTest {
    private static final String OWNER_COORDS = "org.example:orders-app:1.0.0";
    private static final String OWNER_UPGRADE_COORDS = "org.example:orders-app:2.0.0";
    private static final String OTHER_COORDS = "org.example:billing-app:1.0.0";
    private static final BlueprintId OWNER = BlueprintId.blueprintId(OWNER_COORDS).unwrap();
    private static final BlueprintId OTHER_OWNER = BlueprintId.blueprintId(OTHER_COORDS).unwrap();
    private static final Artifact SLICE = Artifact.artifact("org.example:orders-api:1.0.0").unwrap();
    private static final String SLICE_CLASS = "org.example.orders.OrdersSlice";
    private static final String DATASOURCE = "database";
    private static final Cause NOT_IN_REPOSITORY = Causes.cause("Artifact not present in local repository");
    private static final Cause NOT_IN_STORE = Causes.cause("Artifact not present in artifact store");

    private static final String SLICE_STANZA = """

            [[slices]]
            artifact = "org.example:orders-api:1.0.0"
            instances = 1
            """;

    @TempDir
    Path tempDir;

    private TestKVStore store;
    private TestClusterNode cluster;
    private Path sliceJar;

    @BeforeEach
    void setUp() throws IOException {
        store = new TestKVStore();
        cluster = new TestClusterNode(store);
        sliceJar = writeSliceJar();
    }

    @Nested
    class Rejection {
        @Test
        void publishFromArtifact_fails_whenDatasourceIsMigratedByAnotherBlueprint() {
            seedSchemaOwnedBy(OTHER_OWNER);

            publish(OWNER_COORDS, withMigrations(OWNER_COORDS)).onSuccess(_ -> Assertions.fail("Publish must be refused for a datasource another blueprint migrates"))
                                                               .onFailure(BlueprintPublishOwnershipTest::assertOwnershipConflict);
        }

        @Test
        void publishFromArtifact_leavesExistingOwnerIntact_whenRejected() {
            seedSchemaOwnedBy(OTHER_OWNER);

            publish(OWNER_COORDS, withMigrations(OWNER_COORDS)).onSuccess(_ -> Assertions.fail("Publish must be refused"));

            assertThat(recordedOwner()).as("a rejected publish must not overwrite the incumbent migrator")
                                       .isEqualTo(OTHER_COORDS);
        }

        @Test
        void publishFromArtifact_writesNoCommands_whenRejected() {
            seedSchemaOwnedBy(OTHER_OWNER);

            publish(OWNER_COORDS, withMigrations(OWNER_COORDS)).onSuccess(_ -> Assertions.fail("Publish must be refused"));

            assertThat(store.get(AppBlueprintKey.appBlueprintKey(OWNER)).isPresent())
                    .as("the gate runs before the batch, so not even the blueprint atom lands")
                    .isFalse();
        }

        @Test
        void datasourceOwnershipConflict_reportsHttpConflict() {
            var cause = SchemaError.DatasourceOwnershipConflict.datasourceOwnershipConflict(DATASOURCE,
                                                                                             OTHER_OWNER,
                                                                                             OWNER);

            assertThat(cause.httpStatus()).as("a state conflict on a well-formed request is 409, not 500")
                                          .isEqualTo(HttpStatus.CONFLICT);
        }
    }

    @Nested
    class Acceptance {
        @Test
        void publishFromArtifact_succeeds_whenDatasourceIsUnclaimed() {
            publish(OWNER_COORDS, withMigrations(OWNER_COORDS)).onFailure(BlueprintPublishOwnershipTest::failOnUnexpectedFailure);

            assertThat(recordedOwner()).as("the first migrator claims the datasource")
                                       .isEqualTo(OWNER_COORDS);
        }

        /// A version upgrade republishes the same blueprint identity with a new version. Ownership is
        /// compared on `ArtifactBase`, so it is the incumbent advancing its own schema, not a second
        /// migrator.
        @Test
        void publishFromArtifact_succeeds_whenSameBlueprintRedeclaresMigrations() {
            seedSchemaOwnedBy(OWNER);

            publish(OWNER_UPGRADE_COORDS,
                    withMigrations(OWNER_UPGRADE_COORDS)).onFailure(BlueprintPublishOwnershipTest::failOnUnexpectedFailure);

            assertThat(recordedOwner()).isEqualTo(OWNER_UPGRADE_COORDS);
        }

        /// Shared usage is legal: only the blueprint that ships the migration scripts owns them.
        @Test
        void publishFromArtifact_succeeds_whenSecondBlueprintDeclaresNoMigrations() {
            seedSchemaOwnedBy(OTHER_OWNER);

            publish(OWNER_COORDS, withoutMigrations(OWNER_COORDS)).onFailure(BlueprintPublishOwnershipTest::failOnUnexpectedFailure);

            assertThat(recordedOwner()).as("a non-migrating blueprint neither claims nor disturbs the record")
                                       .isEqualTo(OTHER_COORDS);
        }
    }

    /// #759 review: `DeploymentOutcomeKey` is written only at the four terminal FSM transitions and
    /// was never cleared when a NEW deployment of the same blueprint id started. `BlueprintId` wraps
    /// the artifact, so a retry reuses the key, and `BlueprintService.outcome(id)` — the outcome-first
    /// status route's read path — kept reporting the PREVIOUS attempt's terminal outcome while the new
    /// attempt was actively converging. `buildAllCommands` now bundles a `Remove` of this key into the
    /// SAME consensus batch as the `AppBlueprintKey` Put that starts the new attempt.
    ///
    /// Round 2 review widened the scope: `storeBlueprintWithKey` (DSL `publish`) and `removeFromStore`
    /// (`delete`) write/remove `AppBlueprintKey` on their OWN single-command batches, bypassing
    /// `buildAllCommands` entirely — so the same stale-outcome gap was still open on both live paths.
    /// Both now bundle the same `DeploymentOutcomeKey` Remove into their own batch. [[AtomicityPin]]
    /// below pins the batch-boundary property directly for all three paths.
    @Nested
    class OutcomeClearedAtPublish {
        @Test
        void publishFromArtifact_clearsStaleOutcome_fromPriorFailedAttempt_ofSameBlueprintId() {
            seedFailedOutcome(OWNER);

            publish(OWNER_COORDS, withoutMigrations(OWNER_COORDS)).onFailure(BlueprintPublishOwnershipTest::failOnUnexpectedFailure);

            assertThat(recordedOutcomeStatus())
                    .as("a fresh publish of a previously FAILED id must not carry the PREVIOUS attempt's "
                        + "result forward. #963: the guarantee is unchanged, but it is now met by a Put of "
                        + "IN_PROGRESS rather than a Remove — the stale terminal is gone AND the new attempt's "
                        + "start is recorded, so 'no terminal yet' is a fact rather than an absence")
                    .isEqualTo(Option.some(DeploymentOutcomeStatus.IN_PROGRESS));
        }

        @Test
        void publishFromArtifact_marksTheAttemptInProgress_whenIdNeverHadOne() {
            publish(OWNER_COORDS, withoutMigrations(OWNER_COORDS)).onFailure(BlueprintPublishOwnershipTest::failOnUnexpectedFailure);

            assertThat(recordedOutcomeStatus())
                    .as("#963: a first-ever publish has no prior outcome to clear, but it DOES record that "
                        + "this attempt started — the write is unconditional, which is what makes the "
                        + "record's presence trustworthy evidence rather than a best effort")
                    .isEqualTo(Option.some(DeploymentOutcomeStatus.IN_PROGRESS));
        }

        /// #963 × #956 — the apply-start record must carry the SUCCESSOR fence version, and this
        /// pins it BY VALUE rather than by status.
        ///
        /// `DeploymentOutcomeValue` became [VersionFenced] in #805 item 2 while #963 was in flight.
        /// The applier rejects any write whose version is not the immediate successor of the
        /// committed one, so the first-write form (`inProgress(startedAtMs)`, carrying
        /// `FIRST_VERSION`) is wrong on this path: publish writes over a POSSIBLY-COMMITTED record —
        /// replacing a stale terminal is why the write exists — and against one the applier would
        /// drop it silently, leaving the previous attempt's terminal in place.
        ///
        /// **Why this test exists and a status assertion does not suffice.** A mutation reverting
        /// `startedOutcome` to the first-write form left every other test in this suite GREEN: the
        /// seeded record is at version 1, both forms produce status IN_PROGRESS, and this fixture's
        /// store does not run the applier's successor check — so nothing could see the difference.
        /// The merge resolution was an unpinned judgement until this assertion existed. It asserts
        /// the derivation itself: seeded at 1, the publish must write 2.
        @Test
        void publishFromArtifact_marksTheAttemptWithTheSuccessorFenceVersion() {
            seedFailedOutcome(OWNER);

            assertThat(recordedOutcome().map(value -> ((AetherValue.DeploymentOutcomeValue) value).outcomeVersion()))
                    .as("precondition: the committed record sits at FIRST_VERSION")
                    .isEqualTo(Option.some(AetherValue.DeploymentOutcomeValue.FIRST_VERSION));

            publish(OWNER_COORDS, withoutMigrations(OWNER_COORDS)).onFailure(BlueprintPublishOwnershipTest::failOnUnexpectedFailure);

            assertThat(recordedOutcome().map(value -> ((AetherValue.DeploymentOutcomeValue) value).outcomeVersion()))
                    .as("the apply-start record must be the immediate SUCCESSOR of the committed one, or "
                        + "the VersionFenced applier drops it and the stale terminal survives")
                    .isEqualTo(Option.some(AetherValue.DeploymentOutcomeValue.FIRST_VERSION + 1));

            // The property that makes this path's un-retried read-then-write safe, asserted rather
            // than assumed: the record ACCUMULATES NOTHING, so two racing publishes produce values
            // differing only in startedAtMs and losing either loses no information. That is what
            // distinguishes it from recordBestEffortFailureOutcome, which merges failingSlices and
            // therefore does need #956's bounded re-read-and-retry.
            assertThat(recordedOutcome().map(value -> ((AetherValue.DeploymentOutcomeValue) value).failingSlices()))
                    .as("an apply-start record accumulates no slice ids — if it ever gains any, the "
                        + "no-retry reasoning in BlueprintService.startedOutcome stops holding")
                    .isEqualTo(Option.some(List.<String> of()));
            assertThat(recordedOutcome().map(value -> ((AetherValue.DeploymentOutcomeValue) value).cause()))
                    .as("and carries no accumulated cause text, for the same reason")
                    .isEqualTo(Option.some(""));
        }

        /// #963 F1 — pins `BlueprintService.confirmOutcomeStart` ITSELF: the retry, not the fence.
        ///
        /// The earlier race test hand-seeded the successor version the retry was supposed to derive,
        /// so it pinned the applier's fence and left the retry untested — deleting
        /// `confirmOutcomeStart` outright was **0 red across the whole suite**. That is the
        /// fixture-supplies-the-thing failure, in the test written to close a race, in a ticket whose
        /// subject is an unpinned mechanism nobody could see was gone.
        ///
        /// Here nothing is seeded. A terminal for the PREVIOUS apply is injected by the cluster node
        /// at the moment the publish's first outcome Put is in flight, so that Put is genuinely
        /// fenced out. The publish must then notice — via a re-read, not a seeded value — re-derive
        /// against the moved committed version, and win.
        ///
        /// Without the retry the record keeps the injected SUCCEEDED and the new apply is
        /// permanently uncondemnable, which is #963's own defect returning by a different route.
        @Test
        void publish_whoseApplyStartLosesToATerminal_retriesUntilItLands() {
            store.processCommand(new KVCommand.Put<>(AetherKey.DeploymentOutcomeKey.deploymentOutcomeKey(OWNER),
                                                     AetherValue.DeploymentOutcomeValue.inProgress(1L, 1L)));
            cluster.injectTerminalBeforeNextOutcomeWrite(OWNER);

            publish(OWNER_COORDS, withoutMigrations(OWNER_COORDS)).onFailure(BlueprintPublishOwnershipTest::failOnUnexpectedFailure);

            assertThat(cluster.terminalInjections())
                    .as("instrument check: the injected terminal must actually have fired, or this test "
                        + "never created the race it claims to pin")
                    .isEqualTo(1);
            assertThat(recordedOutcomeStatus())
                    .as("the publish's apply-start lost the first round to a terminal for the previous "
                        + "apply; confirmOutcomeStart must re-read, re-derive and win, or the new apply "
                        + "is left uncondemnable behind a stale terminal")
                    .isEqualTo(Option.some(DeploymentOutcomeStatus.IN_PROGRESS));
        }

        /// #759 review round 2, BLOCKING 1: `publish(String dsl)` — the live path behind
        /// `SliceRoutes.handleBlueprint` — went through `storeBlueprintWithKey`, a single-command
        /// batch touching only `AppBlueprintKey`, bypassing `buildAllCommands` and its Remove
        /// entirely.
        @Test
        void publishDsl_clearsStaleOutcome_fromPriorFailedAttempt_ofSameBlueprintId() {
            seedFailedOutcome(OWNER);

            publishDsl(OWNER_COORDS).onFailure(BlueprintPublishOwnershipTest::failOnUnexpectedFailure);

            assertThat(recordedOutcomeStatus())
                    .as("the SliceRoutes.handleBlueprint live path must give the same guarantee "
                        + "publishFromArtifact does — stale terminal replaced by this attempt's IN_PROGRESS")
                    .isEqualTo(Option.some(DeploymentOutcomeStatus.IN_PROGRESS));
        }

        /// #759 review round 2, BLOCKING 2: `delete(id)` went through `removeFromStore`, a
        /// single-command batch removing only `AppBlueprintKey` — the outcome record was never
        /// cleared and orphaned permanently, since no later write to the deleted id would ever touch
        /// it again.
        @Test
        void delete_clearsStaleOutcome_fromPriorFailedAttempt() {
            seedFailedOutcome(OWNER);

            BlueprintService.blueprintService(cluster, store, repository())
                            .delete(OWNER)
                            .await();

            assertThat(recordedOutcome().isEmpty())
                    .as("deleting a blueprint id must clear its outcome record too — an orphaned "
                        + "terminal record must not survive the blueprint id it described")
                    .isTrue();
        }

        private Option<AetherValue> recordedOutcome() {
            return store.get(AetherKey.DeploymentOutcomeKey.deploymentOutcomeKey(OWNER));
        }

        /// The STATUS rather than mere presence: after #963 every publish leaves a record, so
        /// `isPresent` no longer discriminates between "this attempt started" and "the previous
        /// attempt's terminal survived", which is the whole point of the #759 guarantee.
        private Option<DeploymentOutcomeStatus> recordedOutcomeStatus() {
            return recordedOutcome().filter(value -> value instanceof AetherValue.DeploymentOutcomeValue)
                                    .map(value -> ((AetherValue.DeploymentOutcomeValue) value).status());
        }
    }

    /// #759 review round 2, BLOCKING 3: pins that each fix lands its `AppBlueprintKey`
    /// write/remove and its `DeploymentOutcomeKey` Remove in ONE recorded consensus batch — the
    /// property the outcome-first status route depends on (at any instant, in flight XOR terminal,
    /// never both). Every assertion in [[OutcomeClearedAtPublish]] is effect-based (checks the FINAL
    /// state of the store) and would stay green even if a fix were split into two separate
    /// `cluster.apply` calls; these tests check the batch boundary directly, mirroring
    /// `ClusterDeploymentStateTransactionalTest.restorePreviousBlueprint_recordsRolledBackOutcomeAtomicallyWithRestore`.
    @Nested
    class AtomicityPin {
        @Test
        void publishFromArtifact_putsBlueprintAndClearsOutcome_inOneBatch() {
            seedFailedOutcome(OWNER);

            publish(OWNER_COORDS, withoutMigrations(OWNER_COORDS)).onFailure(BlueprintPublishOwnershipTest::failOnUnexpectedFailure);

            assertThat(landedInSameBatch(AppBlueprintKey.appBlueprintKey(OWNER),
                                         AetherKey.DeploymentOutcomeKey.deploymentOutcomeKey(OWNER)))
                    .as("buildAllCommands's AppBlueprintKey Put and its DeploymentOutcomeKey Remove "
                        + "must land in the SAME cluster.apply batch, not merely both somewhere in "
                        + "this node's apply history")
                    .isTrue();
            // #963 F2 — assert the COMMAND, not merely the key. Key-level matching cannot tell the
            // #963 Put of the apply-start record from the pre-#963 Remove, so reverting this path to
            // a Remove left this test green: `confirmOutcomeStart` writes the record on a LATER
            // apply, repairing the end state while silently losing the same-batch property this
            // test exists to pin. The retry made the system robust in a way that hid the breakage
            // from the mutation that used to catch it.
            assertThat(outcomeCommandInSameBatchAs(AppBlueprintKey.appBlueprintKey(OWNER),
                                                   AetherKey.DeploymentOutcomeKey.deploymentOutcomeKey(OWNER))
                               .map(command -> command instanceof KVCommand.Put))
                    .as("buildAllCommands's outcome write in that batch must be a Put of the apply-start "
                        + "record — a Remove satisfies the key-level assertion above while leaving the "
                        + "record to be written outside the guaranteed batch")
                    .isEqualTo(Option.some(true));
        }

        @Test
        void publishDsl_putsBlueprintAndClearsOutcome_inOneBatch() {
            seedFailedOutcome(OWNER);

            publishDsl(OWNER_COORDS).onFailure(BlueprintPublishOwnershipTest::failOnUnexpectedFailure);

            assertThat(landedInSameBatch(AppBlueprintKey.appBlueprintKey(OWNER),
                                         AetherKey.DeploymentOutcomeKey.deploymentOutcomeKey(OWNER)))
                    .as("storeBlueprintWithKey's Put and its DeploymentOutcomeKey Remove must land in "
                        + "the SAME batch")
                    .isTrue();
            // #963 F2 — assert the COMMAND, not merely the key. Key-level matching cannot tell the
            // #963 Put of the apply-start record from the pre-#963 Remove, so reverting this path to
            // a Remove left this test green: `confirmOutcomeStart` writes the record on a LATER
            // apply, repairing the end state while silently losing the same-batch property this
            // test exists to pin. The retry made the system robust in a way that hid the breakage
            // from the mutation that used to catch it.
            assertThat(outcomeCommandInSameBatchAs(AppBlueprintKey.appBlueprintKey(OWNER),
                                                   AetherKey.DeploymentOutcomeKey.deploymentOutcomeKey(OWNER))
                               .map(command -> command instanceof KVCommand.Put))
                    .as("storeBlueprintWithKey's outcome write in that batch must be a Put of the apply-start "
                        + "record — a Remove satisfies the key-level assertion above while leaving the "
                        + "record to be written outside the guaranteed batch")
                    .isEqualTo(Option.some(true));
        }

        @Test
        void delete_removesBlueprintAndClearsOutcome_inOneBatch() {
            seedFailedOutcome(OWNER);

            BlueprintService.blueprintService(cluster, store, repository())
                            .delete(OWNER)
                            .await();

            assertThat(landedInSameBatch(AppBlueprintKey.appBlueprintKey(OWNER),
                                         AetherKey.DeploymentOutcomeKey.deploymentOutcomeKey(OWNER)))
                    .as("removeFromStore's AppBlueprintKey Remove and its DeploymentOutcomeKey Remove "
                        + "must land in the SAME batch")
                    .isTrue();
        }
    }

    // --- helpers ---

    /// The cause must reach the caller UNWRAPPED. `ProblemResponses.resolveStatus` keys the response
    /// code off `cause instanceof HttpStatusAware`, so any accumulation or composition on the way out
    /// silently downgrades the publish rejection from 409 to 500.
    private static void assertOwnershipConflict(Cause cause) {
        assertThat(cause).isInstanceOf(SchemaError.DatasourceOwnershipConflict.class);
        assertThat(cause).asInstanceOf(InstanceOfAssertFactories.type(HttpStatusAware.class))
                         .extracting(HttpStatusAware::httpStatus)
                         .isEqualTo(HttpStatus.CONFLICT);
        assertThat(cause.message()).contains(DATASOURCE)
                                   .contains(OTHER_COORDS)
                                   .contains(OWNER_COORDS);
    }

    private static void failOnUnexpectedFailure(Cause cause) {
        Assertions.fail("Unexpected publish failure: " + cause.message());
    }

    private Result<PublishedBlueprint> publish(String coords, byte[] blueprintJar) {
        return BlueprintService.blueprintService(cluster, store, repository(), artifactStore(blueprintJar))
                               .publishFromArtifact(coords + ":blueprint")
                               .await();
    }

    /// #759 review round 2: the DSL `publish(String)` path (`SliceRoutes.handleBlueprint`) parses
    /// this TOML directly — no jar, no `ArtifactStore` — but still resolves the declared slice
    /// through `repository()`, same as [#publish].
    private Result<PublishedBlueprint> publishDsl(String blueprintId) {
        var dsl = "id = \"" + blueprintId + "\"\n" + SLICE_STANZA;

        return BlueprintService.blueprintService(cluster, store, repository())
                               .publish(dsl)
                               .await();
    }

    /// #759 review round 2, BLOCKING 3: true only when SOME recorded `cluster.apply` batch contains
    /// both keys — proves the two commands committed atomically in one consensus round, not merely
    /// both somewhere across this node's apply history (which two separate `apply` calls would also
    /// satisfy).
    private boolean landedInSameBatch(AetherKey keyA, AetherKey keyB) {
        return cluster.batches
                      .stream()
                      .anyMatch(batch -> batch.stream().anyMatch(c -> c.key().equals(keyA))
                                       && batch.stream().anyMatch(c -> c.key().equals(keyB)));
    }

    private void seedFailedOutcome(BlueprintId id) {
        store.processCommand(new KVCommand.Put<>(AetherKey.DeploymentOutcomeKey.deploymentOutcomeKey(id),
                                                  AetherValue.DeploymentOutcomeValue.failed(List.of("orders-api"),
                                                                                            "prior attempt failed",
                                                                                            1L)));
    }

    private void seedSchemaOwnedBy(BlueprintId owner) {
        store.processCommand(new KVCommand.Put<>(SchemaVersionKey.schemaVersionKey(DATASOURCE),
                                                 SchemaVersionValue.schemaVersionValue(DATASOURCE,
                                                                                       1,
                                                                                       "V001__init.sql",
                                                                                       SchemaStatus.COMPLETED,
                                                                                       owner.asString(),
                                                                                       owner)));
    }

    private String recordedOwner() {
        return store.get(SchemaVersionKey.schemaVersionKey(DATASOURCE))
                    .filter(SchemaVersionValue.class::isInstance)
                    .map(SchemaVersionValue.class::cast)
                    .map(SchemaVersionValue::owningBlueprint)
                    .map(BlueprintId::asString)
                    .or("<no record>");
    }

    private Repository repository() {
        return artifact -> SLICE.equals(artifact)
                           ? sliceLocation(artifact)
                           : NOT_IN_REPOSITORY.promise();
    }

    private Promise<Location> sliceLocation(Artifact artifact) {
        return Result.lift(Causes::fromThrowable, () -> sliceJar.toUri().toURL())
                     .flatMap(url -> Location.location(artifact, url))
                     .async();
    }

    private static byte[] withMigrations(String blueprintId) {
        return blueprintJar(blueprintId, Option.some("schema/V001__init.sql"));
    }

    private static byte[] withoutMigrations(String blueprintId) {
        return blueprintJar(blueprintId, Option.none());
    }

    /// A blueprint jar carrying `META-INF/blueprint.toml` and, when requested, one migration script
    /// under the default layout — which `BlueprintArtifactParser` maps to the `"database"` datasource.
    private static byte[] blueprintJar(String blueprintId, Option<String> migrationEntryPath) {
        var bytes = new ByteArrayOutputStream();

        try (var zip = new ZipOutputStream(bytes)) {
            writeEntry(zip, "META-INF/blueprint.toml", "id = \"" + blueprintId + "\"\n" + SLICE_STANZA);
            migrationEntryPath.onPresent(path -> writeMigration(zip, path));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build test blueprint jar", e);
        }

        return bytes.toByteArray();
    }

    private static void writeMigration(ZipOutputStream zip, String path) {
        try {
            writeEntry(zip, path, "CREATE TABLE orders(id BIGINT PRIMARY KEY);");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write test migration entry", e);
        }
    }

    private static void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /// A minimal slice jar on disk: `BlueprintExpander` resolves the blueprint's one slice through
    /// `Repository` and reads its `SliceManifest` off a real `JarFile`, so this must be a file, not a
    /// byte array. It declares no dependency file, which `DependencyFile.load` treats as "no
    /// dependencies".
    private Path writeSliceJar() throws IOException {
        var manifest = new Manifest();
        var attributes = manifest.getMainAttributes();

        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(SliceManifest.SLICE_ARTIFACT_ATTR, SLICE.asString());
        attributes.putValue(SliceManifest.SLICE_CLASS_ATTR, SLICE_CLASS);
        attributes.putValue(SliceManifest.ENVELOPE_VERSION_ATTR, "1000");

        var target = tempDir.resolve("orders-api-1.0.0.jar");

        try (var out = new JarOutputStream(Files.newOutputStream(target), manifest)) {
            out.putNextEntry(new ZipEntry("org/example/orders/"));
            out.closeEntry();
        }

        return target;
    }

    private static ArtifactStore artifactStore(byte[] blueprintJar) {
        return new ArtifactStore() {
            @Override public Promise<DeployResult> deploy(ArtifactFile file, byte[] content) {
                return NOT_IN_STORE.promise();
            }

            @Override public Promise<byte[]> resolve(ArtifactFile file) {
                return Promise.success(blueprintJar);
            }

            @Override public Promise<ResolvedArtifact> resolveWithMetadata(ArtifactFile file) {
                return NOT_IN_STORE.promise();
            }

            @Override public Promise<Boolean> exists(ArtifactFile file) {
                return Promise.success(false);
            }

            @Override public Promise<Option<ArtifactMetadata>> metadata(ArtifactFile file) {
                return Promise.success(Option.none());
            }

            @Override public Promise<List<Version>> versions(GroupId groupId, ArtifactId artifactId) {
                return Promise.success(List.of());
            }

            @Override public Promise<Unit> delete(ArtifactFile file) {
                return Promise.unitPromise();
            }

            @Override public Metrics metrics() {
                return new Metrics(0, 0, 0L);
            }
        };
    }

    /// The outcome-key command riding in the same batch as `blueprintKey`, so a test can assert its
    /// TYPE rather than only its presence.
    private Option<KVCommand<AetherKey>> outcomeCommandInSameBatchAs(AetherKey blueprintKey, AetherKey outcomeKey) {
        return cluster.batches.stream()
                              .filter(batch -> batch.stream().anyMatch(command -> blueprintKey.equals(command.key())))
                              .flatMap(batch -> batch.stream().filter(command -> outcomeKey.equals(command.key())))
                              .findFirst()
                              .map(Option::some)
                              .orElseGet(Option::none);
    }

    private static final class TestClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final TestKVStore store;
        // #759 review round 2, BLOCKING 3: tracks each apply() call's batch verbatim (mirrors
        // ClusterDeploymentStateTransactionalTest's RecordingClusterNode) so a test can pin that a
        // Put and a Remove landed in the SAME consensus batch, not merely both somewhere in this
        // node's history — splitting the fix into two separate apply() calls would leave every
        // effect-based assertion in OutcomeClearedAtPublish green while breaking atomicity.
        final List<List<KVCommand<AetherKey>>> batches = new ArrayList<>();

        TestClusterNode(TestKVStore store) {
            this.store = store;
        }

        @Override
        public NodeId self() {
            return NodeId.nodeId("test-node").unwrap();
        }

        @Override
        public TopologyManager topologyManager() {
            return null;
        }

        @Override
        public Promise<Unit> start() {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        /// One-shot: the next batch carrying a `DeploymentOutcomeKey` Put has a terminal for the
        /// PREVIOUS apply landed immediately ahead of it, so that batch's outcome write is fenced
        /// out. Models a completion writer winning the race a publish cannot see coming.
        private Option<BlueprintId> pendingTerminalFor = Option.none();
        private int terminalInjections = 0;

        void injectTerminalBeforeNextOutcomeWrite(BlueprintId id) {
            pendingTerminalFor = Option.some(id);
        }

        int terminalInjections() {
            return terminalInjections;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            batches.add(List.copyOf(commands));
            injectTerminalIfArmed(commands);

            return Promise.success(commands.stream()
                                           .map(command -> (R) store.processCommand(command))
                                           .toList());
        }

        private void injectTerminalIfArmed(List<KVCommand<AetherKey>> commands) {
            var touchesOutcome = commands.stream()
                                         .anyMatch(command -> command.key() instanceof AetherKey.DeploymentOutcomeKey);

            pendingTerminalFor.filter(_ -> touchesOutcome)
                              .onPresent(id -> {
                                  var key = AetherKey.DeploymentOutcomeKey.deploymentOutcomeKey(id);
                                  var committed = store.get(key)
                                                       .filter(value -> value instanceof AetherValue.DeploymentOutcomeValue)
                                                       .map(value -> ((AetherValue.DeploymentOutcomeValue) value).outcomeVersion())
                                                       .or(0L);

                                  store.processCommand(new KVCommand.Put<>(key,
                                                                           AetherValue.DeploymentOutcomeValue.succeeded(9L,
                                                                                                                        committed + 1)));
                                  pendingTerminalFor = Option.none();
                                  terminalInjections++;
                              });
        }
    }

    private static final class TestKVStore extends KVStore<AetherKey, AetherValue> {
        private final Map<AetherKey, AetherValue> storage = new HashMap<>();

        TestKVStore() {
            super(null, null, null);
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
            storage.forEach((key, value) -> acceptMatching(keyClass, valueClass, consumer, key, value));
        }

        @SuppressWarnings("unchecked")
        private static <KK, VV> void acceptMatching(Class<KK> keyClass,
                                                    Class<VV> valueClass,
                                                    BiConsumer<KK, VV> consumer,
                                                    AetherKey key,
                                                    AetherValue value) {
            if (keyClass.isInstance(key) && valueClass.isInstance(value)) {
                consumer.accept((KK) key, (VV) value);
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> List<R> process(Batch<KVCommand<AetherKey>> batch) {
            return batch.commands()
                        .stream()
                        .map(command -> (R) processCommand(command))
                        .toList();
        }

        /// #963 F1 — this double now enforces the applier's successor fence for [VersionFenced]
        /// values, mirroring `KVStore.staleSuccessorWrite`.
        ///
        /// It previously accepted every Put unconditionally, and that omission was load-bearing in
        /// the worst way: it is the exact mechanism `DeploymentOutcomeValue` is fenced by, so every
        /// test in this suite ran against a store that could not reproduce the failure the fence
        /// exists to prevent. Two mutations were invisible because of it — reverting the successor
        /// derivation, and deleting `BlueprintService.confirmOutcomeStart` entirely — each leaving
        /// the whole suite green while breaking the guarantee it is here to pin.
        ///
        /// A rejected write mutates nothing and does NOT fail the batch it rode in, exactly as the
        /// real applier behaves, so a fenced-out outcome Put still lets its sibling blueprint Put
        /// land — which is the state `confirmOutcomeStart` has to detect.
        @SuppressWarnings({"unchecked", "rawtypes"})
        Option<AetherValue> processCommand(KVCommand command) {
            return switch (command) {
                case KVCommand.Put<?, ?> put -> {
                    if (fencedOut((AetherKey) put.key(), put.value())) {
                        yield Option.option(storage.get((AetherKey) put.key()));
                    }

                    storage.put((AetherKey) put.key(), (AetherValue) put.value());
                    yield Option.none();
                }
                case KVCommand.Remove<?> remove -> {
                    storage.remove((AetherKey) remove.key());
                    yield Option.none();
                }
                case KVCommand.Get<?> get -> Option.option(storage.get((AetherKey) get.key()));
                default -> Option.none();
            };
        }

        /// Mirrors `KVStore.staleSuccessorWrite`: a write is rejected when both the incoming and the
        /// committed value are [VersionFenced] and the incoming version is not the immediate
        /// successor. A first write against an absent or non-fenced value passes — there is no chain
        /// to fence yet.
        private boolean fencedOut(AetherKey key, Object incoming) {
            return incoming instanceof VersionFenced in
                   && storage.get(key) instanceof VersionFenced stored
                   && in.fenceVersion() != stored.fenceVersion() + 1;
        }
    }

    /// #1066 — the TOML body publish stores the stream bindings the artifact publish does.
    ///
    /// `publish(String)` — `POST /api/v1/blueprints`, `aether blueprint apply`, Forge — wrote no
    /// `BlueprintStreamBindingsKey`, and since #1040 `BlueprintStreamAddresses` refuses an owning
    /// blueprint without one: stream publisher slices failed to load and declarative consumers were never
    /// subscribed. The fixtures reproduce the layout that broke: the body TOML is a bare slice list and
    /// every `[streams.*]` declaration lives only inside the slice jars, each shipping the same module
    /// `resources.toml`, as `aether/tests/blueprints/test-stream-consumer` and the ticketing demo do.
    @Nested
    class StreamBindings {
        private static final String STREAM_APP_COORDS = "org.example:stream-app:1.0.0";
        private static final BlueprintId STREAM_APP = BlueprintId.blueprintId(STREAM_APP_COORDS).unwrap();
        private static final Artifact PUBLISHER_SLICE = Artifact.artifact("org.example:stream-app-publisher:1.0.0").unwrap();
        private static final Artifact CONSUMER_SLICE = Artifact.artifact("org.example:stream-app-consumer:1.0.0").unwrap();
        private static final String STREAM_SLICE_CLASS = "org.example.stream.StreamSlice";
        private static final String ORDER_EVENTS = "order-events";
        private static final String CONSUMER_EVENTS = "consumer-events";
        private static final String NAMESPACE = "org.example.stream-app";
        private static final Cause UNREADABLE_JAR = Causes.cause("Slice jar became unreadable after expansion");

        private static final String STREAM_APP_DSL = """
                id = "org.example:stream-app:1.0.0"

                [[slices]]
                artifact = "org.example:stream-app-publisher:1.0.0"
                instances = 1

                [[slices]]
                artifact = "org.example:stream-app-consumer:1.0.0"
                instances = 1
                """;

        private static final String MODULE_STREAMS = """
                [streams.order-events]
                partitions = 1
                retention = "count"
                retention-value = "100000"
                max-event-size = "64KB"

                [streams.consumer-events]
                partitions = 1
                retention = "count"
                retention-value = "100000"
                max-event-size = "64KB"
                """;

        /// `consistency_mode` is the key the provisioning binder reads for `StreamConfig.consistencyMode`.
        private static final String STRONG_MODULE_STREAMS = """
                [streams.order-events]
                partitions = 1
                retention = "count"
                retention-value = "100000"
                max-event-size = "64KB"
                consistency_mode = "strong"
                """;

        /// #1262: no write path can honour STRONG (no consensus publish path is wired), so a blueprint
        /// declaring it must fail the deploy with the named cause — not publish empty bindings and fail
        /// later at slice activation with a generic `UnboundStreamAlias`.
        @Test
        void publish_isRefused_whenASliceDeclaresAStrongStream() {
            var repository = sliceRepository(Map.of(PUBLISHER_SLICE, sliceJar(PUBLISHER_SLICE, STRONG_MODULE_STREAMS),
                                                    CONSUMER_SLICE, sliceJar(CONSUMER_SLICE, MODULE_STREAMS)));

            publishBody(repository).onSuccess(_ -> Assertions.fail("a STRONG stream declaration must refuse the deploy"))
                                   .onFailure(cause -> assertThat(cause.message()).contains("#1262")
                                                                                  .contains("consistency_mode")
                                                                                  .contains(StreamResourceValidator.RULE_UNSUPPORTED_CONSISTENCY));
            assertThat(cluster.batches).as("the refusal must come before any batch is applied").isEmpty();
        }

        @Test
        void publishFromArtifact_isRefused_whenTheBlueprintDeclaresAStrongStream() {
            var artifactPathStore = new TestKVStore();
            var artifactPathCluster = new TestClusterNode(artifactPathStore);

            BlueprintService.blueprintService(artifactPathCluster,
                                              artifactPathStore,
                                              streamAppRepository(),
                                              artifactStore(streamAppBlueprintJar(STRONG_MODULE_STREAMS)))
                            .publishFromArtifact(STREAM_APP_COORDS + ":blueprint")
                            .await()
                            .onSuccess(_ -> Assertions.fail("a STRONG stream declaration must refuse the deploy"))
                            .onFailure(cause -> assertThat(cause.message()).contains("#1262")
                                                                           .contains("consistency_mode")
                                                                           .contains(StreamResourceValidator.RULE_UNSUPPORTED_CONSISTENCY));
            assertThat(artifactPathCluster.batches).as("the refusal must come before any batch is applied").isEmpty();
        }

        @Test
        void publish_storesBindingsForStreamsDeclaredOnlyInsideSliceJars() {
            publishBody(streamAppRepository()).onFailure(BlueprintPublishOwnershipTest::failOnUnexpectedFailure);

            assertThat(boundAddresses(store))
                    .as("the body TOML declares no streams; both aliases must be bound from the slice jars' "
                        + "META-INF/resources.toml, under the blueprint's namespace at the producer-default version")
                    .containsExactlyInAnyOrder(ORDER_EVENTS + "=" + NAMESPACE + ":" + ORDER_EVENTS + ":1.0.0",
                                               CONSUMER_EVENTS + "=" + NAMESPACE + ":" + CONSUMER_EVENTS + ":1.0.0");
        }

        /// The ordering `StreamAddressError` relies on to treat missing bindings as fatal: the FSM writes
        /// slice targets only after applying the blueprint, so bindings in the blueprint's own batch can
        /// never trail a slice target. An effect-only assertion stays green if they land in a later apply.
        @Test
        void publish_putsStreamBindingsInTheSameBatchAsTheBlueprint() {
            publishBody(streamAppRepository()).onFailure(BlueprintPublishOwnershipTest::failOnUnexpectedFailure);

            assertThat(landedInSameBatch(AppBlueprintKey.appBlueprintKey(STREAM_APP),
                                         BlueprintStreamBindingsKey.blueprintStreamBindingsKey(STREAM_APP)))
                    .as("the bindings Put must ride the blueprint Put's cluster.apply batch")
                    .isTrue();
        }

        /// The acceptance path. A deployed stream slice resolves its alias twice — the publisher factory
        /// through `StreamAddressResolver`, the declarative consumer registration through
        /// `NodeDeploymentState.resolveStreamName` — and both call `BlueprintStreamAddresses.engineKeyFor`
        /// against the owning blueprint the FSM stamps on the slice target. At the rc4 tip both refused
        /// with `UnresolvedStreamBindings`: the publisher slice failed to load, the consumer never
        /// subscribed.
        @Test
        void publish_letsPublisherAndDeclarativeConsumerResolveTheirStreams() {
            publishBody(streamAppRepository()).onFailure(BlueprintPublishOwnershipTest::failOnUnexpectedFailure);
            seedOwnedSliceTarget(PUBLISHER_SLICE);
            seedOwnedSliceTarget(CONSUMER_SLICE);

            BlueprintStreamAddresses.engineKeyFor(store, PUBLISHER_SLICE, ORDER_EVENTS)
                                    .onFailure(cause -> Assertions.fail("publisher alias must resolve: " + cause.message()))
                                    .onSuccess(key -> assertThat(key).isEqualTo(NAMESPACE + ":" + ORDER_EVENTS + ":1.0.0"));
            BlueprintStreamAddresses.engineKeyFor(store, CONSUMER_SLICE, CONSUMER_EVENTS)
                                    .onFailure(cause -> Assertions.fail("consumer alias must resolve: " + cause.message()))
                                    .onSuccess(key -> assertThat(key).isEqualTo(NAMESPACE + ":" + CONSUMER_EVENTS + ":1.0.0"));
        }

        /// The fix is that bindings exist, not that resolution got lenient: an alias no slice declares
        /// must still be refused, naming the alias, once the body publish HAS written bindings.
        @Test
        void publish_leavesAnUndeclaredAliasFailingLoudly() {
            publishBody(streamAppRepository()).onFailure(BlueprintPublishOwnershipTest::failOnUnexpectedFailure);
            seedOwnedSliceTarget(CONSUMER_SLICE);

            BlueprintStreamAddresses.engineKeyFor(store, CONSUMER_SLICE, "undeclared-events")
                                    .onSuccess(key -> Assertions.fail("an undeclared alias must be refused, not resolved to " + key))
                                    .onFailure(cause -> assertThat(cause).isInstanceOf(StreamAddressError.UnboundStreamAlias.class)
                                                                         .extracting(Cause::message)
                                                                         .asString()
                                                                         .contains("undeclared-events"));
        }

        @Test
        void publish_andPublishFromArtifact_storeIdenticalBindings_forTheSameBlueprint() {
            var artifactPathStore = new TestKVStore();

            BlueprintService.blueprintService(new TestClusterNode(artifactPathStore),
                                              artifactPathStore,
                                              streamAppRepository(),
                                              artifactStore(streamAppBlueprintJar()))
                            .publishFromArtifact(STREAM_APP_COORDS + ":blueprint")
                            .await()
                            .onFailure(BlueprintPublishOwnershipTest::failOnUnexpectedFailure);
            publishBody(streamAppRepository()).onFailure(BlueprintPublishOwnershipTest::failOnUnexpectedFailure);

            assertThat(boundAddresses(artifactPathStore))
                    .as("instrument check: the artifact path must bind both streams, or the equality below is vacuous")
                    .hasSize(2);
            assertThat(bindingsIn(store))
                    .as("the body path must store exactly the BlueprintStreamBindingsValue the artifact path stores")
                    .isEqualTo(bindingsIn(artifactPathStore));
        }

        /// Slices from different modules can ship different declarations, a case the artifact path never
        /// sees. One alias bound to two addresses must refuse the publish before any command lands:
        /// `BlueprintStreamBindingsValue.addressFor` would otherwise answer with the first and silently
        /// point the other slice at a ring it did not declare.
        @Test
        void publish_isRefused_whenSlicesBindOneAliasToDifferentAddresses() {
            var repository = sliceRepository(Map.of(PUBLISHER_SLICE, sliceJar(PUBLISHER_SLICE, pinnedOrderEvents("1.0.0")),
                                                    CONSUMER_SLICE, sliceJar(CONSUMER_SLICE, pinnedOrderEvents("2.0.0"))));

            publishBody(repository).onSuccess(_ -> Assertions.fail("conflicting declarations of one alias must refuse the publish"))
                                   .onFailure(cause -> assertThat(cause.message()).contains("conflicting-stream-declaration")
                                                                                  .contains(NAMESPACE + ":" + ORDER_EVENTS + ":1.0.0")
                                                                                  .contains(NAMESPACE + ":" + ORDER_EVENTS + ":2.0.0"))
                                   .onFailure(cause -> assertThat(cause).as("#1336: this refusal is the artifact author's content, answered 422 — not a 500")
                                                                        .isInstanceOf(HttpStatusAware.class)
                                                                        .extracting(c -> ((HttpStatusAware) c).httpStatus())
                                                                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
            assertThat(cluster.batches).as("the refusal must come before any batch is applied").isEmpty();
        }

        /// A slice jar the expander read but the bindings derivation cannot must fail the publish, never
        /// bind a subset: a subset turns into a silent `UnboundStreamAlias` on whichever slice lost.
        /// The expander and the pub/sub topology read each locate the consumer jar once, so the third
        /// locate is the bindings read.
        @Test
        void publish_isRefused_whenASliceJarCannotBeReadForBindings() {
            var calls = new AtomicInteger();
            var delegate = streamAppRepository();
            Repository repository = artifact -> CONSUMER_SLICE.equals(artifact) && calls.incrementAndGet() >= 3
                                                ? UNREADABLE_JAR.promise()
                                                : delegate.locate(artifact);

            publishBody(repository).onSuccess(_ -> Assertions.fail("an unreadable slice jar must refuse the publish"))
                                   .onFailure(cause -> assertThat(cause.message()).contains(UNREADABLE_JAR.message()));
            assertThat(calls.get()).as("instrument check: the failing locate must be the bindings read, the third")
                                   .isEqualTo(3);
            assertThat(cluster.batches).as("nothing may be applied when the bindings cannot be derived").isEmpty();
        }

        /// #1336 — one invalid `[streams.*]` section must cost only ITS binding. At the rc4 tip
        /// `BlueprintService.streamBindings` folded the validator's all-or-nothing result with
        /// `.or(List.of())`, so `audit-events` declaring both `source` and `version` emptied the whole
        /// bindings entry and `order-events`, declared correctly beside it, vanished with it.
        @Test
        void publish_bindsTheValidStream_whenAnotherStreamIsInvalid() {
            var repository = sliceRepository(Map.of(PUBLISHER_SLICE, sliceJar(PUBLISHER_SLICE, ONE_VALID_ONE_INVALID),
                                                    CONSUMER_SLICE, sliceJar(CONSUMER_SLICE, ONE_VALID_ONE_INVALID)));

            var published = publishBody(repository).onFailure(BlueprintPublishOwnershipTest::failOnUnexpectedFailure);

            assertThat(boundAddresses(store))
                    .as("#1336: the valid `order-events` binding must survive the invalid `audit-events` section beside it")
                    .containsExactly(ORDER_EVENTS + "=" + NAMESPACE + ":" + ORDER_EVENTS + ":1.0.0");
            assertThat(rejectedFieldsAndRules(published))
                    .as("#1336: the invalid section is reported ON THE ANSWER by field and rule — once, although both "
                        + "slice jars ship the same text — not only as a log line")
                    .containsExactly("[streams.audit-events]::version-and-source-mutually-exclusive");
        }

        @Test
        void publishFromArtifact_bindsTheValidStream_whenAnotherStreamIsInvalid() {
            var artifactPathStore = new TestKVStore();

            var published = BlueprintService.blueprintService(new TestClusterNode(artifactPathStore),
                                                              artifactPathStore,
                                                              streamAppRepository(),
                                                              artifactStore(streamAppBlueprintJar(ONE_VALID_ONE_INVALID)))
                                            .publishFromArtifact(STREAM_APP_COORDS + ":blueprint")
                                            .await()
                                            .onFailure(BlueprintPublishOwnershipTest::failOnUnexpectedFailure);

            assertThat(boundAddresses(artifactPathStore))
                    .as("#1336: the artifact path must keep the valid `order-events` binding beside the invalid section")
                    .containsExactly(ORDER_EVENTS + "=" + NAMESPACE + ":" + ORDER_EVENTS + ":1.0.0");
            assertThat(rejectedFieldsAndRules(published))
                    .as("#1336: the artifact path reports the invalid section on the answer by field and rule")
                    .containsExactly("[streams.audit-events]::version-and-source-mutually-exclusive");
        }

        /// GATING: a `resources.toml` that does not parse has no sections to keep or drop, and the same
        /// file is every slice's intrinsic config layer at load — so the publish is refused, naming the
        /// rule, before any command lands.
        @Test
        void publish_isRefused_whenAResourcesTomlDoesNotParse() {
            var repository = sliceRepository(Map.of(PUBLISHER_SLICE, sliceJar(PUBLISHER_SLICE, UNPARSEABLE),
                                                    CONSUMER_SLICE, sliceJar(CONSUMER_SLICE, MODULE_STREAMS)));

            publishBody(repository).onSuccess(_ -> Assertions.fail("an unparseable resources.toml must refuse the publish"))
                                   .onFailure(cause -> assertThat(cause.message()).contains(StreamResourceValidator.RULE_RESOURCES_PARSE));
            assertThat(cluster.batches).as("the refusal must come before any batch is applied").isEmpty();
        }

        /// The artifact path derives the bindings BEFORE it builds its batch, so a gating rule refuses the
        /// publish with nothing applied — the same "nothing lands" the body path has.
        @Test
        void publishFromArtifact_isRefused_whenTheResourcesTomlDoesNotParse_beforeAnyBatch() {
            var artifactPathStore = new TestKVStore();
            var artifactPathCluster = new TestClusterNode(artifactPathStore);

            BlueprintService.blueprintService(artifactPathCluster,
                                              artifactPathStore,
                                              streamAppRepository(),
                                              artifactStore(streamAppBlueprintJar(UNPARSEABLE)))
                            .publishFromArtifact(STREAM_APP_COORDS + ":blueprint")
                            .await()
                            .onSuccess(_ -> Assertions.fail("an unparseable resources.toml must refuse the artifact publish"))
                            .onFailure(cause -> assertThat(cause.message()).contains(StreamResourceValidator.RULE_RESOURCES_PARSE));
            assertThat(artifactPathCluster.batches).as("the refusal must come before any batch is applied").isEmpty();
        }

        /// GATING by ruling (#1336 on #1282): a blueprint `External` source naming a runtime-provisioned
        /// stream kind is refused the way the management API refuses it — a typed 4xx naming the rule — not
        /// dropped so the consuming slice fails at load. The valid section beside it does not rescue the
        /// publish.
        @Test
        void publish_isRefused_whenASourceNamesAReservedStreamKind() {
            var repository = sliceRepository(Map.of(PUBLISHER_SLICE, sliceJar(PUBLISHER_SLICE, RESERVED_KIND_SOURCE),
                                                    CONSUMER_SLICE, sliceJar(CONSUMER_SLICE, RESERVED_KIND_SOURCE)));

            publishBody(repository).onSuccess(_ -> Assertions.fail("a reserved stream-kind source must refuse the publish"))
                                   .onFailure(cause -> assertThat(cause.message()).contains(StreamResourceValidator.RULE_SOURCE_RESERVED_KIND)
                                                                                  .contains("[streams.inbox]"));
            assertThat(cluster.batches).as("the refusal must come before any batch is applied").isEmpty();
        }

        private static final String RESERVED_KIND_SOURCE = """
                [streams.order-events]
                partitions = 1

                [streams.inbox]
                source = "entity:orders:1.0.0"
                role = "consumer"
                """;

        /// GATING: the blueprint's own namespace prefixes every owned address, so when it cannot be derived
        /// and a stream IS declared there is no per-alias subset to keep — the publish is refused naming
        /// the rule. `system` is the reserved namespace (`Namespace.appNamespace`).
        @Test
        void publish_isRefused_whenTheBlueprintNamespaceIsReserved_andAStreamIsDeclared() {
            var outcome = BlueprintService.blueprintService(cluster, store, streamAppRepository())
                                          .publish(STREAM_APP_DSL.replace("id = \"org.example:stream-app:1.0.0\"", "id = \"system.example:stream-app:1.0.0\""))
                                          .await();

            outcome.onSuccess(_ -> Assertions.fail("a reserved blueprint namespace with declared streams must refuse the publish"))
                   .onFailure(cause -> assertThat(cause.message()).contains(StreamResourceValidator.RULE_NAMESPACE_RESERVED)
                                                                  .contains("system.example:stream-app:1.0.0"));
            assertThat(cluster.batches).as("the refusal must come before any batch is applied").isEmpty();
        }

        /// NOT gating: with no stream declared there is nothing the namespace could prefix, so a blueprint
        /// that deployed before #1336 still deploys; the namespace failure is reported on the answer.
        @Test
        void publish_reportsAReservedBlueprintNamespace_withoutRefusing_whenNoStreamIsDeclared() {
            var repository = sliceRepository(Map.of(PUBLISHER_SLICE, sliceJar(PUBLISHER_SLICE, NO_STREAMS),
                                                    CONSUMER_SLICE, sliceJar(CONSUMER_SLICE, NO_STREAMS)));
            var published = BlueprintService.blueprintService(cluster, store, repository)
                                            .publish(STREAM_APP_DSL.replace("id = \"org.example:stream-app:1.0.0\"", "id = \"system.example:stream-app:1.0.0\""))
                                            .await()
                                            .onFailure(BlueprintPublishOwnershipTest::failOnUnexpectedFailure);

            assertThat(rejectedFieldsAndRules(published))
                    .as("nothing to bind, nothing to gate — but the operator is still told")
                    .containsExactly("system.example:stream-app:1.0.0::" + StreamResourceValidator.RULE_NAMESPACE_RESERVED);
            assertThat(cluster.batches).as("the publish proceeds").isNotEmpty();
        }

        /// Same as the case above with NO `resources.toml` in either slice jar: the derivation must still run
        /// once so the body path reports the reserved namespace exactly as the artifact path does (rev1363 NIT-5).
        @Test
        void publish_reportsAReservedBlueprintNamespace_whenNoSliceShipsAResourcesToml() {
            var repository = sliceRepository(Map.of(PUBLISHER_SLICE, sliceJarWithoutResources(PUBLISHER_SLICE),
                                                    CONSUMER_SLICE, sliceJarWithoutResources(CONSUMER_SLICE)));
            var published = BlueprintService.blueprintService(cluster, store, repository)
                                            .publish(STREAM_APP_DSL.replace("id = \"org.example:stream-app:1.0.0\"", "id = \"system.example:stream-app:1.0.0\""))
                                            .await()
                                            .onFailure(BlueprintPublishOwnershipTest::failOnUnexpectedFailure);

            assertThat(rejectedFieldsAndRules(published))
                    .containsExactly("system.example:stream-app:1.0.0::" + StreamResourceValidator.RULE_NAMESPACE_RESERVED);
        }

        private static final String UNPARSEABLE = """
                [streams.order-events
                partitions = 1
                """;

        private static final String NO_STREAMS = """
                [datasource.orders]
                url = "jdbc:postgresql://localhost/orders"
                """;

        private static List<String> rejectedFieldsAndRules(Result<PublishedBlueprint> published) {
            return published.map(value -> value.rejectedStreamBindings()
                                               .stream()
                                               .map(failure -> failure.field() + "::" + failure.rule())
                                               .toList())
                            .or(List.of());
        }

        /// `order-events` is a well-formed owned stream; `audit-events` trips the parser's
        /// `version-and-source-mutually-exclusive` rule, which names exactly that one section.
        private static final String ONE_VALID_ONE_INVALID = """
                [streams.order-events]
                partitions = 1

                [streams.audit-events]
                source = "org.example.other:audit-events:1.0.0"
                version = "1.0.0"
                """;

        private Result<PublishedBlueprint> publishBody(Repository repository) {
            return BlueprintService.blueprintService(cluster, store, repository)
                                   .publish(STREAM_APP_DSL)
                                   .await();
        }

        private void seedOwnedSliceTarget(Artifact slice) {
            store.processCommand(new KVCommand.Put<>(SliceTargetKey.sliceTargetKey(slice.base()),
                                                     SliceTargetValue.sliceTargetValue(slice.version(), 1, Option.some(STREAM_APP))));
        }

        private Repository streamAppRepository() {
            return sliceRepository(Map.of(PUBLISHER_SLICE, sliceJar(PUBLISHER_SLICE, MODULE_STREAMS),
                                          CONSUMER_SLICE, sliceJar(CONSUMER_SLICE, MODULE_STREAMS)));
        }

        private static Repository sliceRepository(Map<Artifact, Path> jars) {
            return artifact -> Option.option(jars.get(artifact))
                                     .toResult(NOT_IN_REPOSITORY)
                                     .flatMap(jar -> jarLocation(artifact, jar))
                                     .async();
        }

        private static Result<Location> jarLocation(Artifact artifact, Path jar) {
            return Result.lift(Causes::fromThrowable, () -> jar.toUri().toURL())
                         .flatMap(url -> Location.location(artifact, url));
        }

        /// #1181/#1336 — #576 refuses the descoped `[streams.X]` keys, and until #1336 that refusal was
        /// unreachable: `BlueprintService.streamBindings` ended `.or(List.of())`, which discarded the
        /// `Cause`, so a blueprint carrying `compression = "lz4"` PUBLISHED with an EMPTY bindings entry
        /// and the consuming slice failed far away with a generic `UnboundStreamAlias`. The enabled
        /// tripwire that pinned that defect stood here.
        ///
        /// CTO ruling on #1181/#1336 (2026-09-20): "inert keys: KEEP drop + report (#576 reject-not-accept).
        /// Not bind-and-warn." — the tripwire's expectation of a GATE is superseded by that ruling. An inert
        /// key is a per-alias rule — the stream parses, the key does
        /// nothing — so it drops THAT alias and is reported by field and rule; it does not refuse the
        /// publish. Here the only declared stream is the rejected one, so the entry is still empty —
        /// but the answer now says which key and why.
        @Test
        void publish_withDescopedStreamKey_dropsThatBinding_andNamesTheRuleOnTheAnswer() {
            var repository = sliceRepository(Map.of(PUBLISHER_SLICE, sliceJar(PUBLISHER_SLICE, descopedCompression()),
                                                    CONSUMER_SLICE, sliceJar(CONSUMER_SLICE, descopedCompression())));

            var published = publishBody(repository).onFailure(BlueprintPublishOwnershipTest::failOnUnexpectedFailure);

            assertThat(boundAddresses(store))
                    .as("the rejected alias gets no binding — accepting it would bind a stream whose declared "
                        + "compression the runtime silently ignores")
                    .isEmpty();
            assertThat(rejectedFieldsAndRules(published))
                    .as("the operator is told at deploy time which key and which rule, not by a later UnboundStreamAlias")
                    .containsExactly("[streams.order-events]::" + StreamResourceValidator.RULE_INERT_STREAM_CONFIG);
            published.onSuccess(value -> assertThat(value.rejectedStreamBindings().getFirst().message()).contains("compression 'LZ4'"));
        }

        /// Same shape as [#pinnedOrderEvents], plus the descoped `compression` key #576 refuses.
        private static String descopedCompression() {
            return """
                    [streams.order-events]
                    partitions = 1
                    compression = "lz4"
                    """;
        }

        private static String pinnedOrderEvents(String version) {
            return """
                    [streams.order-events]
                    version = "%s"
                    partitions = 1
                    """.formatted(version);
        }

        private Path sliceJar(Artifact slice, String resourcesToml) {
            return sliceJar(slice, Option.some(resourcesToml));
        }

        private Path sliceJarWithoutResources(Artifact slice) {
            return sliceJar(slice, Option.none());
        }

        private Path sliceJar(Artifact slice, Option<String> resourcesToml) {
            var manifest = new Manifest();
            var attributes = manifest.getMainAttributes();

            attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
            attributes.putValue(SliceManifest.SLICE_ARTIFACT_ATTR, slice.asString());
            attributes.putValue(SliceManifest.SLICE_CLASS_ATTR, STREAM_SLICE_CLASS);
            attributes.putValue(SliceManifest.ENVELOPE_VERSION_ATTR, "1000");

            var target = tempDir.resolve(slice.artifactId().id() + ".jar");

            try (var out = new JarOutputStream(Files.newOutputStream(target), manifest)) {
                out.putNextEntry(new ZipEntry("org/example/stream/"));
                out.closeEntry();

                if (resourcesToml.isPresent()) {
                    writeEntry(out, "META-INF/resources.toml", resourcesToml.unwrap());
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to build test slice jar", e);
            }

            return target;
        }

        private static byte[] streamAppBlueprintJar() {
            return streamAppBlueprintJar(MODULE_STREAMS);
        }

        private static byte[] streamAppBlueprintJar(String resourcesToml) {
            var bytes = new ByteArrayOutputStream();

            try (var zip = new ZipOutputStream(bytes)) {
                writeEntry(zip, "META-INF/blueprint.toml", STREAM_APP_DSL);
                writeEntry(zip, "META-INF/resources.toml", resourcesToml);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to build test blueprint jar", e);
            }

            return bytes.toByteArray();
        }

        private static Option<BlueprintStreamBindingsValue> bindingsIn(TestKVStore target) {
            return target.get(BlueprintStreamBindingsKey.blueprintStreamBindingsKey(STREAM_APP))
                         .filter(BlueprintStreamBindingsValue.class::isInstance)
                         .map(BlueprintStreamBindingsValue.class::cast);
        }

        private static List<String> boundAddresses(TestKVStore target) {
            return bindingsIn(target).map(value -> value.bindings()
                                                        .stream()
                                                        .map(binding -> binding.alias() + "=" + binding.address().asString())
                                                        .toList())
                                     .or(List.of());
        }
    }

    /// #547 — deploy-time pre-flight for generic resource config sections. Reuses this class's
    /// on-disk JAR-building harness (unrelated to the #542 migration-ownership scenarios above)
    /// because it is the one fixture in this module that already builds a real slice jar readable
    /// by `BlueprintExpander`/`TopologyParser` through a real `Repository`.
    @Nested
    class ConfigPreflight {
        private static final String PREFLIGHT_COORDS = "org.example:preflight-app:1.0.0";
        private static final String BLUEPRINT_SERVICE_LOGGER_NAME =
                "org.pragmatica.aether.deployment.cluster.BlueprintServiceInstance";
        private static final String PAYMENTS_SECTION_TOML = """
                [payments]
                base_url = "https://payments.example"
                """;
        private static final String UNRELATED_SECTION_TOML = """
                [shipping]
                base_url = "https://shipping.example"
                """;
        // An unterminated array is a parse error by TomlParser's own contract, as in SliceStoreTest.
        private static final String MALFORMED_PAYMENTS_TOML = """
                [payments]
                base_url = [
                """;

        private FailOpenLogCapture failOpenLogCapture;

        @BeforeEach
        void captureBlueprintServiceLog() {
            failOpenLogCapture = FailOpenLogCapture.attach(BLUEPRINT_SERVICE_LOGGER_NAME);
        }

        @AfterEach
        void detachBlueprintServiceLog() {
            failOpenLogCapture.detach();
        }

        @Test
        void publishFromArtifact_fails_whenDeclaredResourceSectionIsNotConfigured() throws IOException {
            var jar = writeSliceJarWithResource("http", "payments");
            var result = publishWithComposite(jar, Option.some(providerWithSections()));

            result.onSuccess(_ -> Assertions.fail("Expected deploy to fail: [payments] section is not configured anywhere"))
                  .onFailure(cause -> assertThat(cause.message()).contains("payments")
                                                                  .contains("orders-api"));
        }

        @Test
        void publishFromArtifact_succeeds_whenDeclaredResourceSectionIsConfigured() throws IOException {
            var jar = writeSliceJarWithResource("http", "payments");
            var result = publishWithComposite(jar, Option.some(providerWithSections("payments")));

            result.onFailure(cause -> Assertions.fail("Expected deploy to succeed: " + cause.message()));
        }

        @Test
        void publishFromArtifact_succeeds_whenNoConfigurationProviderIsWired() throws IOException {
            var jar = writeSliceJarWithResource("http", "payments");
            var result = publishWithComposite(jar, Option.none());

            result.onFailure(cause -> Assertions.fail("Fail-open expected when no ConfigurationProvider is wired: " + cause.message()));
        }

        /// #547 fail-open visibility (team-lead's added condition, mirroring the drain
        /// disruption-budget guard's visible bypass note in `NodeLifecycleRoutes`): a quiet gate
        /// that doesn't gate is itself a failure mode, so the fail-open path in
        /// `noteConfigSectionPreflightSkipIfBlind` must not be silent. Drives the SAME real
        /// end-to-end jar/deploy path as the fail-open success test above, but also captures the
        /// production logger to prove the skip is actually observable, not just successful.
        @Test
        void publishFromArtifact_logsVisibleSkipWarning_whenNoConfigurationProviderIsWired() throws IOException {
            var jar = writeSliceJarWithResource("http", "payments");
            var result = publishWithComposite(jar, Option.none());

            result.onFailure(cause -> Assertions.fail("Fail-open expected when no ConfigurationProvider is wired: " + cause.message()));
            assertThat(failOpenLogCapture.capturedWarns())
                    .as("fail-open skip must be logged, not silent")
                    .anyMatch(msg -> msg.contains("Config-section pre-flight (#547) SKIPPED")
                                   && msg.contains("1 declared resource section(s)")
                                   && msg.contains("across 1 slice(s)")
                                   && msg.contains("not checked"));
        }

        /// #547 condition (b): publish-topic sections stay invisible to the pre-flight even when a
        /// REAL slice jar (parsed through `TopologyParser`/`BlueprintExpander`, not a hand-built
        /// [org.pragmatica.aether.slice.topology.SliceTopology]) declares both a missing generic
        /// resource AND a missing publish-topic section side by side. Only the generic resource is
        /// named — proving the exclusion holds through the actual manifest-generation shape, not just
        /// the validator's own unit tests.
        @Test
        void publishFromArtifact_namesOnlyTheMissingResourceSection_whenAMissingPublishTopicSectionCoexists() throws IOException {
            var jar = writeSliceJarWithResourceAndPublishTopic("http", "payments", "order-placed");
            var result = publishWithComposite(jar, Option.some(providerWithSections()));

            result.onSuccess(_ -> Assertions.fail("Expected deploy to fail: [payments] section is not configured anywhere"))
                  .onFailure(cause -> assertThat(cause.message()).contains("payments")
                                                                  .doesNotContain("order-placed"));
        }

        /// #1067: the `POST /api/v1/blueprints` path (`SliceRoutes.handleBlueprint` → `publish(String)`)
        /// for a slice that declares its section ONLY in its own jar's `META-INF/resources.toml`. The
        /// loader layers that file under the node composite (`SliceStore.assembleSliceComposite`), so the
        /// runtime resolves the section; a pre-flight that consulted the node composite alone refused the
        /// deploy with HTTP 500 — the shape that failed `DurableEntityForgeTest` in the Heavy job.
        @Test
        void publish_succeeds_whenDeclaredSectionShipsOnlyInTheSliceJar() throws IOException {
            var jar = writeSliceJarWithResource("http", "payments", Option.some(PAYMENTS_SECTION_TOML));
            var result = publishDslWithComposite(jar, Option.some(providerWithSections()));

            result.onFailure(cause -> Assertions.fail("The loader resolves [payments] from the slice jar, so the pre-flight must accept it: "
                                                      + cause.message()));
        }

        /// #1067, same property through the artifact path, which shares `validatePubSub` with the DSL path.
        @Test
        void publishFromArtifact_succeeds_whenDeclaredSectionShipsOnlyInTheSliceJar() throws IOException {
            var jar = writeSliceJarWithResource("http", "payments", Option.some(PAYMENTS_SECTION_TOML));
            var result = publishWithComposite(jar, Option.some(providerWithSections()));

            result.onFailure(cause -> Assertions.fail("The loader resolves [payments] from the slice jar, so the pre-flight must accept it: "
                                                      + cause.message()));
        }

        /// #1067 guarantee, refusal half: the slice jar DOES ship a `resources.toml` and the node composite
        /// does carry a section, but neither is `[payments]`. Absent from every layer the loader would
        /// consult, so the deploy is still refused — and refused with [MissingConfigSection] specifically.
        @Test
        void publish_failsWithMissingConfigSection_whenSectionIsAbsentFromEveryLayer() throws IOException {
            var jar = writeSliceJarWithResource("http", "payments", Option.some(UNRELATED_SECTION_TOML));
            var result = publishDslWithComposite(jar, Option.some(providerWithSections("inventory")));

            result.onSuccess(_ -> Assertions.fail("[payments] is in neither the node composite nor the slice jar — the deploy must be refused"))
                  .onFailure(ConfigPreflight::assertOnlyPaymentsSectionMissing);
        }

        /// #1067: a malformed slice `resources.toml` makes the loader drop the slice composite whole
        /// (`SliceStoreTest.buildSliceCompositeFromClassLoader_dropsWholeComposite_whenResourcesTomlIsMalformed`),
        /// and provisioning then falls back to the node-wide `ConfigService` — the node composite alone. A
        /// section that appears only in the malformed file is not available at runtime, so the pre-flight
        /// must not count it.
        @Test
        void publish_failsWithMissingConfigSection_whenSectionShipsOnlyInAMalformedSliceToml() throws IOException {
            var jar = writeSliceJarWithResource("http", "payments", Option.some(MALFORMED_PAYMENTS_TOML));
            var result = publishDslWithComposite(jar, Option.some(providerWithSections()));

            result.onSuccess(_ -> Assertions.fail("A malformed slice resources.toml contributes no layer at runtime — the deploy must be refused"))
                  .onFailure(ConfigPreflight::assertOnlyPaymentsSectionMissing);
        }

        /// #1067 no-regression: a section configured only in the node's `node.toml` layer is accepted.
        @Test
        void publish_succeeds_whenSectionIsConfiguredOnlyInNodeToml() throws IOException {
            var jar = writeSliceJarWithResource("http", "payments");
            var result = publishDslWithComposite(jar, Option.some(nodeComposite(providerWithSections(), providerWithSections("payments"))));

            result.onFailure(cause -> Assertions.fail("[payments] is configured in node.toml: " + cause.message()));
        }

        /// #1067 no-regression: a section configured only in the operator KV overlay is accepted.
        @Test
        void publish_succeeds_whenSectionIsConfiguredOnlyInKvOverlay() throws IOException {
            var jar = writeSliceJarWithResource("http", "payments");
            var result = publishDslWithComposite(jar, Option.some(nodeComposite(providerWithSections("payments"), providerWithSections())));

            result.onFailure(cause -> Assertions.fail("[payments] is configured in the KV overlay: " + cause.message()));
        }

        private static void assertOnlyPaymentsSectionMissing(Cause cause) {
            assertThat(cause.stream().toList()).as("every aggregated failure is a MissingConfigSection")
                                               .isNotEmpty()
                                               .allMatch(MissingConfigSection.class::isInstance);
            assertThat(cause.message()).contains("[payments]")
                                       .contains("orders-api");
        }

        private Result<PublishedBlueprint> publishWithComposite(Path jar, Option<ConfigurationProvider> nodeComposite) {
            return preflightService(jar, nodeComposite).publishFromArtifact(PREFLIGHT_COORDS + ":blueprint")
                                                       .await();
        }

        /// The DSL path `SliceRoutes.handleBlueprint` serves for `POST /api/v1/blueprints`.
        private Result<PublishedBlueprint> publishDslWithComposite(Path jar, Option<ConfigurationProvider> nodeComposite) {
            return preflightService(jar, nodeComposite).publish("id = \"" + PREFLIGHT_COORDS + "\"\n" + SLICE_STANZA)
                                                       .await();
        }

        private BlueprintService preflightService(Path jar, Option<ConfigurationProvider> nodeComposite) {
            Repository repository = artifact -> SLICE.equals(artifact)
                                                 ? Result.lift(Causes::fromThrowable, () -> jar.toUri().toURL())
                                                         .flatMap(url -> Location.location(artifact, url))
                                                         .async()
                                                 : NOT_IN_REPOSITORY.promise();

            return BlueprintService.blueprintService(cluster, store, repository, artifactStore(withoutMigrations(PREFLIGHT_COORDS)), nodeComposite);
        }

        /// The node composite exactly as `AetherNode.createResourceProviderFacade` layers it: the operator KV
        /// overlay first, the node's own `node.toml` beneath.
        private ConfigurationProvider nodeComposite(ConfigurationProvider kvOverlay, ConfigurationProvider nodeToml) {
            return LayeredConfigProvider.layered(List.of(NamedConfigProvider.namedConfigProvider("KV", kvOverlay),
                                                         NamedConfigProvider.namedConfigProvider("node.toml", nodeToml)));
        }

        private ConfigurationProvider providerWithSections(String... sections) {
            var values = new HashMap<String, String>();

            for (var section : sections) {
                values.put(section + ".present", "true");
            }

            var source = MapConfigSource.mapConfigSource("test", values).unwrap();

            return ConfigurationProvider.builder().withSource(source).build();
        }

        /// Same manifest attributes as [BlueprintPublishOwnershipTest#writeSliceJar], plus one
        /// `META-INF/slice/*.manifest` properties entry declaring a single generic resource
        /// dependency, in the exact key format `TopologyParser.parseFromJar` expects.
        private Path writeSliceJarWithResource(String resourceType, String resourceSection) throws IOException {
            return writeSliceJarWithResource(resourceType, resourceSection, Option.none());
        }

        /// Same as [#writeSliceJarWithResource(String, String)] plus, when given, the slice's own
        /// `META-INF/resources.toml` — the entry the loader reads through the slice classloader and layers
        /// under the node composite (#1067).
        private Path writeSliceJarWithResource(String resourceType,
                                               String resourceSection,
                                               Option<String> resourcesToml) throws IOException {
            var manifest = new Manifest();
            var attributes = manifest.getMainAttributes();

            attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
            attributes.putValue(SliceManifest.SLICE_ARTIFACT_ATTR, SLICE.asString());
            attributes.putValue(SliceManifest.SLICE_CLASS_ATTR, SLICE_CLASS);
            attributes.putValue(SliceManifest.ENVELOPE_VERSION_ATTR, "1000");

            var target = tempDir.resolve("orders-api-preflight-1.0.0.jar");
            var topology = """
                    slice.name=orders-api
                    routes.count=0
                    dependencies.count=0
                    resources.count=1
                    resource.0.type=%s
                    resource.0.config=%s
                    publish.topics.count=0
                    reactive.count=0
                    """.formatted(resourceType, resourceSection);

            try (var out = new JarOutputStream(Files.newOutputStream(target), manifest)) {
                out.putNextEntry(new ZipEntry("org/example/orders/"));
                out.closeEntry();
                writeEntry(out, "META-INF/slice/OrdersApi.manifest", topology);

                if (resourcesToml.isPresent()) {
                    writeEntry(out, "META-INF/resources.toml", resourcesToml.unwrap());
                }
            }

            return target;
        }

        /// Same as [#writeSliceJarWithResource] plus a publish-topic/subscription pair (config
        /// section `topicConfigSection`, self-subscribed so `PubSubValidator`'s orphan-publisher
        /// check does not fire and mask the assertion this test cares about) — proving the missing
        /// generic-resource section is named while the co-present, equally-missing topic section is
        /// not, through a real manifest rather than a hand-built [SliceTopology].
        private Path writeSliceJarWithResourceAndPublishTopic(String resourceType,
                                                              String resourceSection,
                                                              String topicConfigSection) throws IOException {
            var manifest = new Manifest();
            var attributes = manifest.getMainAttributes();

            attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
            attributes.putValue(SliceManifest.SLICE_ARTIFACT_ATTR, SLICE.asString());
            attributes.putValue(SliceManifest.SLICE_CLASS_ATTR, SLICE_CLASS);
            attributes.putValue(SliceManifest.ENVELOPE_VERSION_ATTR, "1000");

            var target = tempDir.resolve("orders-api-preflight-topic-1.0.0.jar");
            var topology = """
                    slice.name=orders-api
                    routes.count=0
                    dependencies.count=0
                    resources.count=1
                    resource.0.type=%s
                    resource.0.config=%s
                    publish.topics.count=1
                    publish.topic.0.config=%s
                    publish.topic.0.messageType=com.example.OrderPlaced
                    reactive.count=1
                    reactive.0.category=subscription
                    reactive.0.method=onOrderPlaced
                    reactive.0.config=%s
                    reactive.0.messageType=com.example.OrderPlaced
                    """.formatted(resourceType, resourceSection, topicConfigSection, topicConfigSection);

            try (var out = new JarOutputStream(Files.newOutputStream(target), manifest)) {
                out.putNextEntry(new ZipEntry("org/example/orders/"));
                out.closeEntry();
                writeEntry(out, "META-INF/slice/OrdersApi.manifest", topology);
            }

            return target;
        }

        /// Log4j2 programmatic appender capturing WARN-and-above messages from a named logger for
        /// assertions — same wiring approach as `ClusterTopologyManagerCasLossLoggingTest` in this
        /// module, adapted to attach/detach around a single test via `@BeforeEach`/`@AfterEach`
        /// rather than a standalone test class.
        private static final class FailOpenLogCapture extends AbstractAppender {
            private final List<String> messages = new CopyOnWriteArrayList<>();
            private final LoggerConfig loggerConfig;
            private final Level originalLevel;

            private FailOpenLogCapture(String name, Layout<?> layout, LoggerConfig loggerConfig, Level originalLevel) {
                super(name, (Filter) null, layout, true, Property.EMPTY_ARRAY);
                this.loggerConfig = loggerConfig;
                this.originalLevel = originalLevel;
            }

            static FailOpenLogCapture attach(String loggerName) {
                var ctx = (LoggerContext) LogManager.getContext(false);
                var configuration = ctx.getConfiguration();
                var loggerConfig = getOrCreateLoggerConfig(configuration, loggerName);
                var originalLevel = loggerConfig.getLevel();
                var capture = new FailOpenLogCapture(loggerName + "-capture", PatternLayout.createDefaultLayout(), loggerConfig, originalLevel);

                capture.start();
                loggerConfig.addAppender(capture, Level.WARN, null);
                loggerConfig.setLevel(Level.WARN);
                ctx.updateLoggers();

                return capture;
            }

            void detach() {
                var ctx = (LoggerContext) LogManager.getContext(false);

                loggerConfig.removeAppender(getName());
                loggerConfig.setLevel(originalLevel);
                ctx.updateLoggers();
                stop();
            }

            List<String> capturedWarns() {
                return List.copyOf(messages);
            }

            @Override public void append(LogEvent event) {
                if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
                    messages.add(event.getMessage().getFormattedMessage());
                }
            }

            private static LoggerConfig getOrCreateLoggerConfig(Configuration configuration, String loggerName) {
                var existing = configuration.getLoggerConfig(loggerName);

                if (loggerName.equals(existing.getName())) {
                    return existing;
                }

                var fresh = new LoggerConfig(loggerName, Level.WARN, false);
                configuration.addLogger(loggerName, fresh);
                return fresh;
            }
        }
    }
}

