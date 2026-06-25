// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Behavioural coverage for the HA-only in-memory [DurableEntity]: happy paths and typed-failure
/// paths for create / get / update / delete, plus the typed decline of the not-yet-supported timer
/// operations.
class DurableEntityTest {
    private static final TimeSpan AWAIT = timeSpan(5).seconds();

    private static DurableEntity<String, Integer> entity() {
        return InMemoryDurableEntity.inMemoryDurableEntity();
    }

    @Nested
    class HappyPath {
        @Test
        void create_returnsInitialState_whenKeyAbsent() {
            entity().create("a", 7)
                    .await(AWAIT)
                    .onFailure(DurableEntityTest::failCause)
                    .onSuccess(state -> assertThat(state).isEqualTo(7));
        }

        @Test
        void get_returnsState_whenKeyPresent() {
            var entity = entity();

            entity.create("a", 7).await(AWAIT).onFailure(DurableEntityTest::failCause);

            entity.get("a")
                  .await(AWAIT)
                  .onFailure(DurableEntityTest::failCause)
                  .onSuccess(state -> assertThat(state.or(-1)).isEqualTo(7));
        }

        @Test
        void get_returnsEmpty_whenKeyAbsent() {
            entity().get("absent")
                    .await(AWAIT)
                    .onFailure(DurableEntityTest::failCause)
                    .onSuccess(state -> assertThat(state.isPresent()).isFalse());
        }

        @Test
        void update_appliesMutatorAndReturnsNewState_whenKeyPresent() {
            var entity = entity();

            entity.create("a", 7).await(AWAIT).onFailure(DurableEntityTest::failCause);

            entity.update("a", value -> value * 3)
                  .await(AWAIT)
                  .onFailure(DurableEntityTest::failCause)
                  .onSuccess(state -> assertThat(state).isEqualTo(21));
        }

        @Test
        void update_commitsState_soSubsequentGetSeesIt() {
            var entity = entity();

            entity.create("a", 7).await(AWAIT).onFailure(DurableEntityTest::failCause);
            entity.update("a", value -> value + 1).await(AWAIT).onFailure(DurableEntityTest::failCause);

            entity.get("a")
                  .await(AWAIT)
                  .onFailure(DurableEntityTest::failCause)
                  .onSuccess(state -> assertThat(state.or(-1)).isEqualTo(8));
        }

        @Test
        void delete_removesState_soSubsequentGetIsEmpty() {
            var entity = entity();

            entity.create("a", 7).await(AWAIT).onFailure(DurableEntityTest::failCause);
            entity.delete("a").await(AWAIT).onFailure(DurableEntityTest::failCause);

            entity.get("a")
                  .await(AWAIT)
                  .onFailure(DurableEntityTest::failCause)
                  .onSuccess(state -> assertThat(state.isPresent()).isFalse());
        }
    }

    @Nested
    class FailurePaths {
        @Test
        void create_fails_whenKeyAlreadyExists() {
            var entity = entity();

            entity.create("a", 7).await(AWAIT).onFailure(DurableEntityTest::failCause);

            entity.create("a", 9)
                  .await(AWAIT)
                  .onSuccess(state -> fail("expected KeyAlreadyExists, got " + state))
                  .onFailure(cause -> assertThat(cause).isInstanceOf(DurableEntityError.KeyAlreadyExists.class));
        }

        @Test
        void update_fails_whenKeyNotFound() {
            entity().update("absent", value -> value + 1)
                    .await(AWAIT)
                    .onSuccess(state -> fail("expected KeyNotFound, got " + state))
                    .onFailure(cause -> assertThat(cause).isInstanceOf(DurableEntityError.KeyNotFound.class));
        }

        @Test
        void delete_fails_whenKeyNotFound() {
            entity().delete("absent")
                    .await(AWAIT)
                    .onSuccess(state -> fail("expected KeyNotFound, got " + state))
                    .onFailure(cause -> assertThat(cause).isInstanceOf(DurableEntityError.KeyNotFound.class));
        }
    }

    @Nested
    class TimerOperations {
        @Test
        void scheduleTimer_fails_withTimerNotSupported() {
            entity().scheduleTimer("a", Duration.ofSeconds(1), value -> value + 1)
                    .await(AWAIT)
                    .onSuccess(token -> fail("expected TimerNotSupported, got " + token))
                    .onFailure(cause -> assertThat(cause).isInstanceOf(DurableEntityError.TimerNotSupported.class));
        }

        @Test
        void cancelTimer_fails_withTimerNotSupported() {
            entity().cancelTimer("a", new DurableEntity.TimerToken("t1"))
                    .await(AWAIT)
                    .onSuccess(unit -> fail("expected TimerNotSupported, got " + unit))
                    .onFailure(cause -> assertThat(cause).isInstanceOf(DurableEntityError.TimerNotSupported.class));
        }
    }

    private static void failCause(Cause cause) {
        fail(cause.message());
    }
}
