// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceManifest;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentApplyValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// W-28a (phase-3 design 9.2, 8.3 B17; spec Q8, R-4.1.1..R-4.1.3, NT-86, NT-118): the publish batch carries the apply record **under FSM2 only**,
/// with `previous` captured as R-4.1.2 says, and the publish confirms the record landed instead of reporting a publish that can never complete
/// (without an open apply no SUCCEEDED is ever written). MT-14p. Against the real fenced `KVStore`, as `BlueprintOutcomeStartFenceTest` does.
class BlueprintApplyRecordTest {
    private static final BlueprintId ID = BlueprintId.blueprintId("org.example:app:1.0.0").unwrap();
    private static final BlueprintId PRIOR_ID = BlueprintId.blueprintId("org.example:app:0.9.0").unwrap();
    private static final Artifact SLICE = Artifact.artifact("org.example:app-slice:1.0.0").unwrap();
    private static final AetherKey APPLY = AetherKey.DeploymentApplyKey.deploymentApplyKey(ID);
    private static final AetherKey BLUEPRINT = AetherKey.AppBlueprintKey.appBlueprintKey(ID);
    private static final String DSL = """
            id = "org.example:app:1.0.0"

            [[slices]]
            artifact = "org.example:app-slice:1.0.0"
            instances = 3
            """;

    @TempDir
    Path tempDir;

    private KVStore<AetherKey, AetherValue> store;
    private Repository repository;

    @BeforeEach
    void setUp() throws IOException {
        store = new KVStore<>(MessageRouter.mutable(), new Serializer() {
            @Override
            public <T> void write(ByteBuf b, T o) {}
        }, new Deserializer() {
            @Override
            public <T> T read(ByteBuf b) {
                return null;
            }
        });
        var jar = writeJar();

        repository = artifact -> Result.lift(Causes::fromThrowable, () -> jar.toUri().toURL()).flatMap(url -> Location.location(artifact, url)).async();
    }

    /// A cluster that applies into the REAL store; `drop` removes the commands it matches before they are applied (a write that does not land).
    private final class Node implements ClusterNode<KVCommand<AetherKey>> {
        final List<List<KVCommand<AetherKey>>> batches = new ArrayList<>();
        Predicate<KVCommand<AetherKey>> drop = _ -> false;
        Consumer<Integer> after = _ -> {};

        @Override
        public NodeId self() {
            return NodeId.nodeId("n").unwrap();
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

        @Override
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            var index = batches.size();

            batches.add(List.copyOf(batch));
            store.process(store.createBatch(batch.stream().filter(drop.negate()).toList()));
            after.accept(index);

            return Promise.success(Collections.emptyList());
        }
    }

    private Option<DeploymentApplyValue> apply() {
        return store.get(APPLY).filter(v -> v instanceof DeploymentApplyValue).map(v -> (DeploymentApplyValue) v);
    }

    private String committedAttempt() {
        return store.get(BLUEPRINT).map(v -> ((AppBlueprintValue) v).attemptId()).or("<none>");
    }

    private boolean batchWritesApplyRecord(Node node) {
        return node.batches.stream().flatMap(List::stream).anyMatch(command -> command.key() instanceof AetherKey.DeploymentApplyKey);
    }

    // -- W-28a: gated by the engine ----------------------------------------------------------------------------------------
    @Test
    void underLegacy_thePublishBatchHasNoApplyRecord() {
        var node = new Node();

        BlueprintService.blueprintService(node, store, repository, false).publish(DSL).await().onFailure(cause -> assertThat(cause.message()).isNull());

        assertThat(batchWritesApplyRecord(node)).as("MT-14p: the record is written under LEGACY").isFalse();
        assertThat(apply().isEmpty()).isTrue();
    }

    @Test
    void underFsm2_thePublishBatchOpensTheApplyRecord_ofItsOwnAttempt() {
        var node = new Node();

        BlueprintService.blueprintService(node, store, repository, true).publish(DSL).await().onFailure(cause -> assertThat(cause.message()).isNull());

        assertThat(batchWritesApplyRecord(node)).as("NT-86: the publish batch lacks the apply record").isTrue();
        assertThat(apply().map(DeploymentApplyValue::attemptId)).isEqualTo(Option.some(committedAttempt()));
        assertThat(apply().map(DeploymentApplyValue::previous)).as("a first publish restores nothing: previous = none, which means delete").isEqualTo(Option.some(Option.none()));
    }

    /// The record rides the SAME batch as the blueprint and the IN_PROGRESS outcome, so a reader never sees a blueprint with no open apply.
    @Test
    void theApplyRecord_ridesTheSameBatchAsTheBlueprintAndItsOutcome() {
        var node = new Node();

        BlueprintService.blueprintService(node, store, repository, true).publish(DSL).await();

        var first = node.batches.getFirst();

        assertThat(first).anyMatch(command -> command.key() instanceof AetherKey.AppBlueprintKey);
        assertThat(first).anyMatch(command -> command.key() instanceof AetherKey.DeploymentOutcomeKey);
        assertThat(first).anyMatch(command -> command.key() instanceof AetherKey.DeploymentApplyKey);
    }

    // -- R-4.1.2: previous ---------------------------------------------------------------------------------------------------
    /// The same-id overwritten value is what a rollback restores.
    @Test
    void aRepublishOfTheSameId_capturesTheOverwrittenBlueprint() {
        var node = new Node();
        var service = BlueprintService.blueprintService(node, store, repository, true);

        service.publish(DSL).await();
        var first = store.get(BLUEPRINT).map(v -> (AppBlueprintValue) v).unwrap();

        service.publish(DSL).await();

        assertThat(apply().map(DeploymentApplyValue::previous)).as("NT-86: a same-id previous is wrong").isEqualTo(Option.some(Option.some(first)));
    }

    /// Otherwise the blueprint of the committed target's prior version.
    @Test
    void aNewVersion_capturesTheBlueprintOfTheCommittedTargetsPriorVersion() {
        var prior = AppBlueprintValue.appBlueprintValue(ExpandedBlueprint.expandedBlueprint(PRIOR_ID, List.of()));

        store.process(store.createBatch(List.of(new KVCommand.Put<>(AetherKey.AppBlueprintKey.appBlueprintKey(PRIOR_ID), prior))));
        store.process(store.createBatch(List.of(new KVCommand.Put<>(AetherKey.SliceTargetKey.sliceTargetKey(SLICE.base()),
                                                                    SliceTargetValue.sliceTargetValue(Artifact.artifact("org.example:app-slice:0.9.0").unwrap().version(), 2, 1)))));

        BlueprintService.blueprintService(new Node(), store, repository, true).publish(DSL).await();

        assertThat(apply().map(DeploymentApplyValue::previous)).isEqualTo(Option.some(Option.some(prior)));
    }

    // -- NT-118: the record is confirmed ---------------------------------------------------------------------------------------
    /// A publish whose apply record did not land is not reported confirmed: with no open apply the FSM never writes SUCCEEDED.
    @Test
    void aPublishWhoseApplyRecordNeverLands_isNotReportedConfirmed() {
        var node = new Node();

        node.drop = command -> command.key() instanceof AetherKey.DeploymentApplyKey;

        var result = BlueprintService.blueprintService(node, store, repository, true).publish(DSL).await();

        assertThat(result.isFailure()).as("NT-118").isTrue();
        result.onSuccess(published -> assertThat(published).isNull()).onFailure(cause -> assertThat(cause.message()).contains("apply record did not land"));
        assertThat(node.batches.size()).as("the confirmation retried within its budget").isGreaterThan(2);
    }

    /// The positive control, and the case where the apply already closed: the cluster FSM finished before the confirmation read, so the record is
    /// gone and this attempt's terminal outcome is committed. That is a landed record, not a missing one.
    @Test
    void aPublishWhoseApplyAlreadyClosed_isConfirmed() {
        var node = new Node();

        node.after = index -> {
            if (index == 0) {
                var attempt = committedAttempt();

                store.process(store.createBatch(List.of(new KVCommand.Remove<AetherKey>(APPLY))));
                store.process(store.createBatch(List.of(new KVCommand.Put<>(AetherKey.DeploymentOutcomeKey.deploymentOutcomeKey(ID),
                                                                            DeploymentOutcomeValue.succeeded(2L, outcomeVersion(), attempt)))));
            }
        };

        var result = BlueprintService.blueprintService(node, store, repository, true).publish(DSL).await();

        assertThat(result.isSuccess()).isTrue();
        assertThat(node.batches).as("no retry of a record that already did its job").hasSize(1);
    }

    /// A terminal outcome that describes ANOTHER attempt is not this attempt's closure: only the outcome of this attempt means the apply did its job.
    /// It lands after the outcome start was confirmed (the first retry of the record), so the failure is the record's, not the outcome's.
    @Test
    void aTerminalOutcomeOfAnotherAttempt_isNotThisAttemptsClosure() {
        var node = new Node();

        node.drop = command -> command.key() instanceof AetherKey.DeploymentApplyKey;
        node.after = index -> {
            if (index == 1) {
                store.process(store.createBatch(List.of(new KVCommand.Put<>(AetherKey.DeploymentOutcomeKey.deploymentOutcomeKey(ID),
                                                                            DeploymentOutcomeValue.succeeded(2L, outcomeVersion(), "an-earlier-attempt")))));
            }
        };

        var result = BlueprintService.blueprintService(node, store, repository, true).publish(DSL).await();

        assertThat(result.isFailure()).as("the other attempt's SUCCEEDED does not confirm this publish").isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("apply record did not land"));
    }

    /// A newer publish owns the id: the blueprint now carries its attempt, so this attempt's record is superseded, not missing, and is not retried.
    @Test
    void aNewerPublishOfTheId_supersedesThisAttempt_andIsNotRetried() {
        var node = new Node();

        node.drop = command -> command.key() instanceof AetherKey.DeploymentApplyKey;
        node.after = index -> {
            if (index == 0) {
                var mine = store.get(BLUEPRINT).map(v -> (AppBlueprintValue) v).unwrap();

                store.process(store.createBatch(List.of(new KVCommand.Put<>(BLUEPRINT, new AppBlueprintValue(mine.blueprint(), mine.registerOnly(), "a-newer-attempt")))));
            }
        };

        var result = BlueprintService.blueprintService(node, store, repository, true).publish(DSL).await();

        assertThat(result.isSuccess()).isTrue();
        assertThat(node.batches).as("superseded: no retry").hasSize(1);
    }

    private long outcomeVersion() {
        return store.get(AetherKey.DeploymentOutcomeKey.deploymentOutcomeKey(ID)).map(v -> ((DeploymentOutcomeValue) v).outcomeVersion() + 1).or(1L);
    }

    private Path writeJar() throws IOException {
        var manifest = new Manifest();
        var attributes = manifest.getMainAttributes();

        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(SliceManifest.SLICE_ARTIFACT_ATTR, SLICE.asString());
        attributes.putValue(SliceManifest.SLICE_CLASS_ATTR, "org.example.app.AppSlice");
        attributes.putValue(SliceManifest.ENVELOPE_VERSION_ATTR, "1000");

        var target = tempDir.resolve("app-slice-1.0.0.jar");

        try (var out = new JarOutputStream(Files.newOutputStream(target), manifest)) {
            out.putNextEntry(new ZipEntry("org/example/app/"));
            out.closeEntry();
        }

        return target;
    }
}
