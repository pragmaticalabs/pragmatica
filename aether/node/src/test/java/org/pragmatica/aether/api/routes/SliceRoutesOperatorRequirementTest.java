// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceRequirementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.RequirementKind;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceRequirementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVCommand.LeaderTransaction;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// rc6 §3.1: `aether scale` writes the OPERATOR requirement and never the target's count (INV-02); `--release` removes it, and a release
/// with nothing to release writes nothing. The transaction is the artifact the leader applies, so what it mutates is what the route commits.
class SliceRoutesOperatorRequirementTest {
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:operator-slice:1.0.0").unwrap();
    private static final SliceRequirementKey OPERATOR_KEY = SliceRequirementKey.sliceRequirementKey(ARTIFACT.base(), RequirementKind.OPERATOR);

    @Test
    void operatorScale_overCommittedTarget_writesOnlyTheOperatorRequirement() {
        var store = storeWithTarget(4);

        var transaction = SliceRoutes.operatorTransaction(store, ARTIFACT, Option.some(7), Option.none()).unwrap();

        assertThat(transaction.mutations()).as("one mutation: the OPERATOR requirement").hasSize(1);
        assertThat(transaction.mutations().getFirst().key()).isEqualTo(OPERATOR_KEY);
        assertThat(transaction.mutations().getFirst().replacement()
                              .map(SliceRequirementValue.class::cast)
                              .map(SliceRequirementValue::instances)).as("the requested count rides in the requirement").isEqualTo(Option.some(7));
        assertThat(targetKeysWritten(transaction)).as("INV-02: the target's count is the combiner's, never the route's").isEmpty();
    }

    @Test
    void operatorRelease_removesTheCommittedRequirement() {
        var store = storeWithTarget(4);
        commit(store, OPERATOR_KEY, SliceRequirementValue.sliceRequirementValue(7, "aether scale", Option.none(), 0L));

        var transaction = SliceRoutes.operatorTransaction(store, ARTIFACT, Option.none(), Option.none()).unwrap();

        assertThat(transaction.mutations()).hasSize(1);
        assertThat(transaction.mutations().getFirst().key()).isEqualTo(OPERATOR_KEY);
        assertThat(transaction.mutations().getFirst().replacement().isEmpty()).as("a release removes the record").isTrue();
        assertThat(transaction.mutations().getFirst().expected().isPresent()).as("and expects the committed record").isTrue();
    }

    @Test
    void operatorRelease_withNothingCommitted_writesNothing() {
        var outcome = SliceRoutes.operatorTransaction(storeWithTarget(4), ARTIFACT, Option.none(), Option.none());

        assertThat(outcome.isFailure()).as("no transaction is built: there is nothing to release").isTrue();
    }

    @Test
    void operatorScale_aboveMaxInstances_isRefusedBadRequestNamingTheMax() {
        var cause = ScaleRouteError.InstancesAboveMax.FACTORY.apply(9, 6);
        var status = new AtomicReference<HttpStatus>();
        var body = new AtomicReference<String>("");

        ProblemResponses.writeProblem(new ResponseWriter() {
            @Override
            public void write(HttpStatus responseStatus, byte[] responseBody, ContentType contentType) {
                status.set(responseStatus);
                body.set(new String(responseBody, StandardCharsets.UTF_8));
            }

            @Override
            public ResponseWriter header(String name, String value) {
                return this;
            }
        }, cause, "/api/v1/scale", "req-1");

        assertThat(status.get()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body.get()).contains("Requested 9 instances").contains("maxInstances is 6");
    }

    @Test
    void guardMaxInstances_requestAboveCommittedMax_isRefusedWithTheMax() {
        var store = storeWithMax(6);

        assertThat(SliceRoutes.guardMaxInstances(store, ARTIFACT, 9).await().isFailure()).isTrue();
        assertThat(SliceRoutes.guardMaxInstances(store, ARTIFACT, 6).await().isSuccess()).isTrue();
    }

    @Test
    void guardMaxInstances_targetWithoutMax_acceptsAnyCount() {
        assertThat(SliceRoutes.guardMaxInstances(storeWithTarget(4), ARTIFACT, 50).await().isSuccess()).isTrue();
    }

    private static KVStore<AetherKey, AetherValue> storeWithMax(int max) {
        var store = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());

        commit(store, LeaderKey.INSTANCE, new LeaderValue(NodeId.nodeId("leader").unwrap(), 1));
        commit(store, SliceTargetKey.sliceTargetKey(ARTIFACT.base()),
               SliceTargetValue.sliceTargetValue(ARTIFACT.version(), 4, 3, Option.none(), Option.some(max), Option.none(), Option.none()));

        return store;
    }

    private static List<AetherKey> targetKeysWritten(LeaderTransaction<AetherKey, AetherValue> transaction) {
        return transaction.mutations()
                          .stream()
                          .filter(mutation -> mutation.key() instanceof SliceTargetKey)
                          .map(mutation -> mutation.key())
                          .toList();
    }

    private static KVStore<AetherKey, AetherValue> storeWithTarget(int instances) {
        var store = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());

        commit(store, LeaderKey.INSTANCE, new LeaderValue(NodeId.nodeId("leader").unwrap(), 1));
        commit(store, SliceTargetKey.sliceTargetKey(ARTIFACT.base()), SliceTargetValue.sliceTargetValue(ARTIFACT.version(), instances, 3));

        return store;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void commit(KVStore<AetherKey, AetherValue> store, StructuredKey key, Object value) {
        store.process(store.createBatch((List) List.of(new KVCommand.Put(key, value))));
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
