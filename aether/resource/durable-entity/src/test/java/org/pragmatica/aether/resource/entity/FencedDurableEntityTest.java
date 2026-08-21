// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.dht.OwnerEpochSource;
import org.pragmatica.dht.storage.MemoryStorageEngine;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Behavioural parity for the [FencedDurableEntity]: over a fence-free engine ([OwnerEpochSource#zero]
/// + the no-op-gated [MemoryStorageEngine]) it must honour the same create / get / update / delete
/// and typed-failure contract as [InMemoryDurableEntity], proving the fenced impl is a faithful
/// [DurableEntity] independent of the epoch fence (which [FencedDurableEntityFenceTest] proves
/// separately).
class FencedDurableEntityTest {
    private static final TimeSpan AWAIT = timeSpan(5).seconds();
    private static final String KEYSPACE = "orders";

    private static DurableEntity<String, Integer, IntOp> entity() {
        return FencedDurableEntity.fencedDurableEntity(MemoryStorageEngine.memoryStorageEngine(),
                                                       OwnerEpochSource.zero(),
                                                       intSerializer(),
                                                       intDeserializer(),
                                                       KEYSPACE);
    }

    private static Serializer intSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {
                byteBuf.writeInt((Integer) object);
            }
        };
    }

    private static Deserializer intDeserializer() {
        return new Deserializer() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T read(ByteBuf byteBuf) {
                return (T) Integer.valueOf(byteBuf.readInt());
            }
        };
    }

    @Nested
    class HappyPath {
        @Test
        void create_returnsInitialState_whenKeyAbsent() {
            entity().create("a", 7)
                    .await(AWAIT)
                    .onFailure(FencedDurableEntityTest::failCause)
                    .onSuccess(state -> assertThat(state).isEqualTo(7));
        }

        @Test
        void get_returnsState_whenKeyPresent() {
            var entity = entity();

            entity.create("a", 7).await(AWAIT).onFailure(FencedDurableEntityTest::failCause);

            entity.get("a")
                  .await(AWAIT)
                  .onFailure(FencedDurableEntityTest::failCause)
                  .onSuccess(state -> assertThat(state.or(-1)).isEqualTo(7));
        }

        @Test
        void get_returnsEmpty_whenKeyAbsent() {
            entity().get("absent")
                    .await(AWAIT)
                    .onFailure(FencedDurableEntityTest::failCause)
                    .onSuccess(state -> assertThat(state.isPresent()).isFalse());
        }

        @Test
        void update_appliesMutatorAndReturnsNewState_whenKeyPresent() {
            var entity = entity();

            entity.create("a", 7).await(AWAIT).onFailure(FencedDurableEntityTest::failCause);

            entity.update("a", new IntOp.Multiply(3))
                  .await(AWAIT)
                  .onFailure(FencedDurableEntityTest::failCause)
                  .onSuccess(state -> assertThat(state).isEqualTo(21));
        }

        @Test
        void update_commitsState_soSubsequentGetSeesIt() {
            var entity = entity();

            entity.create("a", 7).await(AWAIT).onFailure(FencedDurableEntityTest::failCause);
            entity.update("a", new IntOp.Add(1)).await(AWAIT).onFailure(FencedDurableEntityTest::failCause);

            entity.get("a")
                  .await(AWAIT)
                  .onFailure(FencedDurableEntityTest::failCause)
                  .onSuccess(state -> assertThat(state.or(-1)).isEqualTo(8));
        }

        @Test
        void delete_removesState_soSubsequentGetIsEmpty() {
            var entity = entity();

            entity.create("a", 7).await(AWAIT).onFailure(FencedDurableEntityTest::failCause);
            entity.delete("a").await(AWAIT).onFailure(FencedDurableEntityTest::failCause);

            entity.get("a")
                  .await(AWAIT)
                  .onFailure(FencedDurableEntityTest::failCause)
                  .onSuccess(state -> assertThat(state.isPresent()).isFalse());
        }
    }

    @Nested
    class FailurePaths {
        @Test
        void create_fails_whenKeyAlreadyExists() {
            var entity = entity();

            entity.create("a", 7).await(AWAIT).onFailure(FencedDurableEntityTest::failCause);

            entity.create("a", 9)
                  .await(AWAIT)
                  .onSuccess(state -> fail("expected EntityAlreadyExists, got " + state))
                  .onFailure(cause -> assertThat(cause).isInstanceOf(EntityError.EntityAlreadyExists.class));
        }

        @Test
        void update_fails_whenKeyNotFound() {
            entity().update("absent", new IntOp.Add(1))
                    .await(AWAIT)
                    .onSuccess(state -> fail("expected EntityNotFound, got " + state))
                    .onFailure(cause -> assertThat(cause).isInstanceOf(EntityError.EntityNotFound.class));
        }

        @Test
        void delete_fails_whenKeyNotFound() {
            entity().delete("absent")
                    .await(AWAIT)
                    .onSuccess(state -> fail("expected EntityNotFound, got " + state))
                    .onFailure(cause -> assertThat(cause).isInstanceOf(EntityError.EntityNotFound.class));
        }
    }

    private static void failCause(Cause cause) {
        fail(cause.message());
    }
}
