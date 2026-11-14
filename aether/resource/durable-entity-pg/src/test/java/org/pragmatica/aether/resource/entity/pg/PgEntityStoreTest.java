// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.entity.pg;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.resource.Mutator;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.resource.entity.EntityError;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.entity.pg.PgFixture.INCREMENT;
import static org.pragmatica.aether.resource.entity.pg.PgFixture.LONG_CODEC;
import static org.pragmatica.aether.resource.entity.pg.PgFixture.failureOf;
import static org.pragmatica.aether.resource.entity.pg.PgFixture.freshKey;
import static org.pragmatica.aether.resource.entity.pg.PgFixture.oneTo;
import static org.pragmatica.aether.resource.entity.pg.PgFixture.race;

/// #2100 E-B against a LIVE PostgreSQL 16: the store's per-operation guarantees, each pinned by a test that goes red
/// when the mechanism behind it is removed (drop `FOR UPDATE`, drop the version compare in the CAS, turn the create
/// `DO NOTHING` into an upsert). Every test owns its entity name, so no test reads another test's rows. Failure
/// payloads, hooks and the version compare-and-set are in [PgEntityStoreFailuresTest].
class PgEntityStoreTest {
    private static final int CONTENDERS = 16;
    private static SqlConnector connector;

    private final String entity = PgFixture.freshEntity();

    @BeforeAll
    static void startPostgres() throws IOException {
        connector = PgFixture.connector();
    }

    @Test
    void create_thenGet_returnsInitialAtVersionOne() {
        var store = store();
        var key = freshKey();

        assertThat(store.create(key, 5L).await().unwrap()).isEqualTo(5L);
        assertThat(store.get(key).await().unwrap()).isEqualTo(Option.some(5L));
        assertThat(store.getVersioned(key).await().unwrap().map(Versioned::version)).isEqualTo(Option.some(1L));
    }

    @Test
    void get_absentKey_isNone() {
        assertThat(store().get(freshKey()).await().unwrap()).isEqualTo(Option.none());
    }

    @Test
    void create_existingKey_isRefusedAndKeepsTheOriginalState() {
        var store = store();
        var key = freshKey();
        store.create(key, 1L).await().unwrap();

        var cause = failureOf(store.create(key, 99L).await());

        assertThat(cause).isEqualTo(new EntityError.EntityAlreadyExists(key));
        assertThat(store.get(key).await().unwrap()).isEqualTo(Option.some(1L));
    }

    @Test
    void create_concurrentRace_exactlyOneSucceeds() {
        var store = store();
        var key = freshKey();
        var counter = new AtomicInteger();

        var results = race(8, () -> store.create(key, counter.incrementAndGet() * 1000L));

        var winners = results.stream().filter(Result::isSuccess).count();
        assertThat(winners).isEqualTo(1);
        results.stream()
               .filter(Result::isFailure)
               .forEach(result -> assertThat(failureOf(result)).isEqualTo(new EntityError.EntityAlreadyExists(key)));
        var winningState = results.stream().filter(Result::isSuccess).findFirst().orElseThrow().unwrap();
        assertThat(store.get(key).await().unwrap()).isEqualTo(Option.some(winningState));
    }

    @Test
    void update_concurrentIncrements_areTotallyOrderedAndNoneIsLost() {
        var store = store();
        var key = freshKey();
        store.create(key, 0L).await().unwrap();

        var results = race(CONTENDERS, () -> store.update(key, INCREMENT));

        assertThat(results).allMatch(Result::isSuccess);
        var returned = results.stream().map(result -> result.unwrap()).sorted().toList();
        assertThat(returned).as("each update saw a distinct predecessor: a total order 1..N").isEqualTo(oneTo(CONTENDERS));
        assertThat(store.get(key).await().unwrap()).isEqualTo(Option.some((long) CONTENDERS));
        assertThat(store.getVersioned(key).await().unwrap().map(Versioned::version)).isEqualTo(Option.some(CONTENDERS + 1L));
    }

    @Test
    void update_absentKey_isEntityNotFoundNamingTheKey() {
        var key = freshKey();

        assertThat(failureOf(store().update(key, INCREMENT).await())).isEqualTo(new EntityError.EntityNotFound(key));
    }

    @Test
    void delete_isRepeatable_firstDeletedThenAbsent() {
        var store = store();
        var key = freshKey();
        store.create(key, 1L).await().unwrap();

        assertThat(store.delete(key).await().unwrap()).as("first delete").isEqualTo(DeleteOutcome.DELETED);
        assertThat(store.get(key).await().unwrap()).isEqualTo(Option.none());
        assertThat(store.delete(key).await().unwrap()).as("second delete").isEqualTo(DeleteOutcome.ABSENT);
    }

    @Test
    void delete_neverCreatedKey_isAbsent() {
        assertThat(store().delete(freshKey()).await().unwrap()).isEqualTo(DeleteOutcome.ABSENT);
    }

    @Test
    void entityNames_areSeparateNamespaces_forEveryOperation() {
        var counters = store();
        var other = PgEntityStore.<String, Long, Mutator<Long>>pgEntityStore(connector, entity + "-other", key -> key, LONG_CODEC).unwrap();
        var key = freshKey();
        counters.create(key, 1L).await().unwrap();

        assertThat(other.get(key).await().unwrap()).as("get does not cross").isEqualTo(Option.none());
        assertThat(other.create(key, 2L).await().unwrap()).as("create does not collide").isEqualTo(2L);
        assertThat(other.update(key, INCREMENT).await().unwrap()).as("update touches only its own row").isEqualTo(3L);
        assertThat(counters.get(key).await().unwrap()).isEqualTo(Option.some(1L));
        assertThat(other.delete(key).await().unwrap()).isEqualTo(DeleteOutcome.DELETED);
        assertThat(counters.get(key).await().unwrap()).as("delete does not cross").isEqualTo(Option.some(1L));
    }

    @Test
    void pgEntityStore_blankEntityName_isRejected() {
        assertThat(PgEntityStore.<String, Long, Mutator<Long>>pgEntityStore(connector, " ", key -> key, LONG_CODEC).isFailure()).isTrue();
    }

    private PgEntityStore<String, Long, Mutator<Long>> store() {
        return PgEntityStore.<String, Long, Mutator<Long>>pgEntityStore(connector, entity, key -> key, LONG_CODEC).unwrap();
    }
}
