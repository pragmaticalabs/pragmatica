// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceManifest;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
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

import io.netty.buffer.ByteBuf;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// BlueprintService.publish against the REAL fenced KVStore (BlueprintServiceTest's TestKVStore
/// applies every Put unconditionally, so it cannot show the version fence at work).
class BlueprintOutcomeStartFenceTest {
    private static final BlueprintId ID = BlueprintId.blueprintId("org.example:redeploy-app:1.0.0").unwrap();
    private static final Artifact SLICE = Artifact.artifact("org.example:redeploy-slice:1.0.0").unwrap();
    private static final AetherKey OUTCOME = AetherKey.DeploymentOutcomeKey.deploymentOutcomeKey(ID);
    private static final AetherKey BLUEPRINT = AetherKey.AppBlueprintKey.appBlueprintKey(ID);
    private static final String DSL = """
            id = "org.example:redeploy-app:1.0.0"

            [[slices]]
            artifact = "org.example:redeploy-slice:1.0.0"
            instances = 3
            """;

    @TempDir Path tempDir;
    private KVStore<AetherKey, AetherValue> store;
    private Repository repository;

    @BeforeEach
    void setUp() throws IOException {
        store = new KVStore<>(MessageRouter.mutable(), new Serializer() {
            @Override public <T> void write(ByteBuf b, T o) {}
        }, new Deserializer() {
            @Override public <T> T read(ByteBuf b) {return null;}
        });
        var jar = writeJar();
        repository = artifact -> SLICE.equals(artifact)
                                 ? Result.lift(Causes::fromThrowable, () -> jar.toUri().toURL()).flatMap(url -> Location.location(artifact, url)).async()
                                 : ((Repository.Absent) () -> "absent").promise();
    }

    /// A cluster that applies into the REAL store and runs a hook before each apply (index = 0-based apply call).
    private class Node implements ClusterNode<KVCommand<AetherKey>> {
        final List<List<KVCommand<AetherKey>>> batches = new ArrayList<>();
        Consumer<Integer> before = i -> {};
        Consumer<Integer> after = i -> {};
        boolean failFrom = false;
        int failAtCall = -1;

        @Override public NodeId self() {return NodeId.nodeId("n").unwrap();}
        @Override public TopologyManager topologyManager() {return null;}
        @Override public Promise<Unit> start() {return Promise.unitPromise();}
        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            var index = batches.size();
            batches.add(List.copyOf(batch));
            if (index == failAtCall) {
                return Causes.cause("not the leader any more").promise();
            }
            before.accept(index);
            store.process(store.createBatch(batch));
            after.accept(index);
            return Promise.success(Collections.emptyList());
        }
    }

    private static Option<DeploymentOutcomeValue> outcome(KVStore<AetherKey, AetherValue> s) {
        return s.get(OUTCOME).filter(v -> v instanceof DeploymentOutcomeValue).map(v -> (DeploymentOutcomeValue) v);
    }

    private static String committedAttempt(KVStore<AetherKey, AetherValue> s) {
        return s.get(BLUEPRINT).map(v -> ((AppBlueprintValue) v).attemptId()).or("<none>");
    }

    private void put(AetherKey key, AetherValue value) {
        store.process(store.createBatch(List.of(new KVCommand.Put<>(key, value))));
    }

    private long outcomePuts(Node node, DeploymentOutcomeStatus status, String attempt) {
        return node.batches.stream().flatMap(List::stream)
                   .filter(c -> c instanceof KVCommand.Put<AetherKey, ?> p && p.key().equals(OUTCOME)
                                && p.value() instanceof DeploymentOutcomeValue o && o.status() == status && o.attemptId().equals(attempt))
                   .count();
    }

    /// B1: seeded old terminal v6; an interleaved writer (the old attempt's late terminal v7) commits BEFORE the
    /// publish batch, so the batch's IN_PROGRESS v7 is fenced out. The retry IN_PROGRESS v8 is itself preceded by
    /// the OWN apply's SUCCEEDED v8 (the FSM finishing). The fence refuses the retry; the confirmation must then
    /// stop (own attempt settled) instead of writing IN_PROGRESS v9 over the real SUCCEEDED.
    @Test
    void interleavedRetryAfterOwnTerminal_endsOnOwnSucceeded() {
        put(OUTCOME, DeploymentOutcomeValue.failed(List.of("x"), "old", 1L, 6L, "old-attempt"));
        var node = new Node();
        var service = BlueprintService.blueprintService(node, store, repository);

        node.before = i -> {
            if (i == 0) {
                put(OUTCOME, DeploymentOutcomeValue.failed(List.of("x"), "late old terminal", 2L, 7L, "old-attempt"));
            }
            if (i == 1) {
                put(OUTCOME, DeploymentOutcomeValue.succeeded(3L, 8L, committedAttempt(store)));
            }
        };
        service.publish(DSL).await().onFailure(c -> fail("publish failed: " + c.message()));

        assertThat(outcome(store).map(DeploymentOutcomeValue::status)).isEqualTo(Option.some(DeploymentOutcomeStatus.SUCCEEDED));
        assertThat(outcome(store).map(DeploymentOutcomeValue::attemptId)).isEqualTo(Option.some(committedAttempt(store)));
        assertThat(node.batches.size()).as("publish + one refused retry, then stop").isEqualTo(2);
    }

    /// B2: same start, but before the retry a NEWER publish (attempt B) commits its blueprint while the record is
    /// still the old terminal (its own apply-start was fenced out too). A's loop must stop on "superseded" and not
    /// write A's IN_PROGRESS.
    @Test
    void staleAttemptsRetry_afterNewerPublish_isNotWritten() {
        put(OUTCOME, DeploymentOutcomeValue.failed(List.of("x"), "old", 1L, 6L, "old-attempt"));
        var node = new Node();
        var service = BlueprintService.blueprintService(node, store, repository);

        node.before = i -> {
            if (i == 0) {
                put(OUTCOME, DeploymentOutcomeValue.failed(List.of("x"), "late old terminal", 2L, 7L, "old-attempt"));
            }
        };
        var attemptA = new String[1];

        node.after = i -> {
            if (i == 0) {
                var published = (AppBlueprintValue) store.get(BLUEPRINT).unwrap();
                attemptA[0] = published.attemptId();
                put(BLUEPRINT, AppBlueprintValue.appBlueprintValue(published.blueprint(), false, "attempt-B"));
            }
        };
        var wrapped = node;
        var svc = service;

        svc.publish(DSL).await().onFailure(c -> fail("publish failed: " + c.message()));

        assertThat(outcomePuts(wrapped, DeploymentOutcomeStatus.IN_PROGRESS, attemptA[0])).as("only the first batch's IN_PROGRESS for A").isEqualTo(1);
        assertThat(wrapped.batches.size()).isEqualTo(1);
    }

    /// B4: the first batch's IN_PROGRESS is fenced out by a late terminal; NOTHING interferes with the retry, so
    /// the retry lands. Its record must close the committed attempt.
    @Test
    void retryThatLands_carriesTheCommittedAttempt() {
        put(OUTCOME, DeploymentOutcomeValue.failed(List.of("x"), "old", 1L, 6L, "old-attempt"));
        var node = new Node();
        var service = BlueprintService.blueprintService(node, store, repository);

        node.before = i -> {
            if (i == 0) {
                put(OUTCOME, DeploymentOutcomeValue.failed(List.of("x"), "late old terminal", 2L, 7L, "old-attempt"));
            }
        };
        service.publish(DSL).await().onFailure(c -> fail("publish failed: " + c.message()));

        assertThat(node.batches.size()).as("publish + one landed retry").isEqualTo(2);
        assertThat(outcome(store).map(DeploymentOutcomeValue::status)).isEqualTo(Option.some(DeploymentOutcomeStatus.IN_PROGRESS));
        assertThat(outcome(store).map(DeploymentOutcomeValue::attemptId)).isEqualTo(Option.some(committedAttempt(store)));
    }


    private Path writeJar() throws IOException {
        var manifest = new Manifest();
        var attributes = manifest.getMainAttributes();

        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(SliceManifest.SLICE_ARTIFACT_ATTR, SLICE.asString());
        attributes.putValue(SliceManifest.SLICE_CLASS_ATTR, "org.example.redeploy.RedeploySlice");
        attributes.putValue(SliceManifest.ENVELOPE_VERSION_ATTR, "1000");

        var target = tempDir.resolve("redeploy-slice-1.0.0.jar");

        try (var out = new JarOutputStream(Files.newOutputStream(target), manifest)) {
            out.putNextEntry(new ZipEntry("org/example/redeploy/"));
            out.closeEntry();
        }

        return target;
    }
}
