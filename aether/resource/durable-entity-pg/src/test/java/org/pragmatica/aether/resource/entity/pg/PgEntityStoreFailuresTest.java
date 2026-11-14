// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.entity.pg;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.pragmatica.aether.resource.Mutator;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.resource.entity.EntityError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.entity.pg.PgFixture.INCREMENT;
import static org.pragmatica.aether.resource.entity.pg.PgFixture.LONG_CODEC;
import static org.pragmatica.aether.resource.entity.pg.PgFixture.failureOf;
import static org.pragmatica.aether.resource.entity.pg.PgFixture.freshKey;
import static org.pragmatica.aether.resource.entity.pg.PgFixture.race;

/// #2100 E-B failure paths against a LIVE PostgreSQL 16: what a caller can read out of every failure (key, version,
/// reason), the delete hook contract, the version compare-and-set with the row lock stripped, and `updated_at` on the
/// database clock. Every test owns its entity name.
class PgEntityStoreFailuresTest {
    private static final String ROW_MICROS = "SELECT (extract(epoch FROM updated_at) * 1000000)::bigint AS micros"
                                           + " FROM durable_entity_state WHERE entity = ? AND entity_key = ?";
    private static SqlConnector connector;

    private final String entity = PgFixture.freshEntity();

    @BeforeAll
    static void startPostgres() throws IOException {
        connector = PgFixture.connector();
    }

    @Test
    void update_mutatorThrows_rollsBackAndReportsKeyAndReason() {
        var store = store(connector);
        var key = freshKey();
        store.create(key, 7L).await().unwrap();

        var cause = failureOf(store.update(key, state -> {
            throw new IllegalStateException("boom");
        }).await());

        var failed = (EntityError.StorageFailed) cause;
        assertThat(failed.key()).isEqualTo(key);
        var mutatorFailed = (PgEntityError.MutatorFailed) failed.cause();
        assertThat(mutatorFailed.key()).isEqualTo(key);
        assertThat(mutatorFailed.reason()).contains("IllegalStateException").contains("boom");
        assertThat(store.getVersioned(key).await().unwrap()).isEqualTo(Option.some(Versioned.versioned(1L, 7L)));
    }

    @Test
    void update_rowLockMissing_theVersionCasStillLetsExactlyOneWrite_andLosersNameKeyAndReadVersion() {
        var parties = 4;
        var store = store(GatedConnector.unlocked(connector, parties));
        var key = freshKey();
        store.create(key, 0L).await().unwrap();

        var results = race(parties, () -> store.update(key, INCREMENT));

        assertThat(results.stream().filter(Result::isSuccess).count()).as("every contender read version 1; one write may win").isEqualTo(1);
        results.stream()
               .filter(Result::isFailure)
               .forEach(result -> assertConflictAtVersionOne(failureOf(result), key));
        assertThat(store.getVersioned(key).await().unwrap()).isEqualTo(Option.some(Versioned.versioned(2L, 1L)));
    }

    @Test
    void update_advancesUpdatedAtOnTheDatabaseClock() throws InterruptedException {
        var store = store(connector);
        var key = freshKey();
        store.create(key, 0L).await().unwrap();
        var before = rowMicros(key);

        Thread.sleep(20);
        store.update(key, INCREMENT).await().unwrap();

        assertThat(rowMicros(key)).as("updated_at is now() of the update transaction").isGreaterThan(before);
    }

    @Test
    void delete_hookFailure_rollsBackTheRowRemoval() {
        var hookFailure = Causes.cause("timer layer refused");
        var store = storeWith(connector, (_, _, _) -> hookFailure.<Unit>promise());
        var key = freshKey();
        store.create(key, 3L).await().unwrap();

        assertThat(store.delete(key).await().isFailure()).isTrue();
        assertThat(store.get(key).await().unwrap()).as("same transaction: the removal rolled back").isEqualTo(Option.some(3L));
    }

    @Test
    void delete_runsTheHookWithEntityThenKey_forDeletedAndForAbsent() {
        var calls = new ArrayList<List<String>>();
        var store = storeWith(connector, recording(calls));
        var present = freshKey();
        var absent = freshKey();
        store.create(present, 1L).await().unwrap();

        assertThat(store.delete(present).await().unwrap()).isEqualTo(DeleteOutcome.DELETED);
        assertThat(store.delete(absent).await().unwrap()).isEqualTo(DeleteOutcome.ABSENT);

        assertThat(calls).as("hook runs once per delete, ABSENT included, as (entity, key)")
                         .containsExactly(List.of(entity, present), List.of(entity, absent));
    }

    @Test
    void get_corruptStoredBytes_isStorageFailedNamingTheKey() {
        var key = freshKey();
        insertCorrupt(key);

        assertThat(failureOf(store(connector).get(key).await())).isInstanceOf(EntityError.StorageFailed.class)
                                                              .extracting(cause -> ((EntityError.StorageFailed) cause).key())
                                                              .isEqualTo(key);
    }

    @Test
    void getVersioned_corruptStoredBytes_isStorageFailedNamingTheKey() {
        var key = freshKey();
        insertCorrupt(key);

        assertThat(failureOf(store(connector).getVersioned(key).await())).isInstanceOf(EntityError.StorageFailed.class)
                                                                         .extracting(cause -> ((EntityError.StorageFailed) cause).key())
                                                                         .isEqualTo(key);
    }

    @Test
    void create_unencodableState_isStorageFailedNamingTheKey() {
        var key = freshKey();

        var cause = failureOf(store(connector).create(key, -1L).await());

        assertThat(cause).isInstanceOf(EntityError.StorageFailed.class);
        assertThat(((EntityError.StorageFailed) cause).key()).isEqualTo(key);
    }

    private static void assertConflictAtVersionOne(Cause cause, String key) {
        var unavailable = (EntityError.StorageUnavailable) cause;
        assertThat(unavailable.key()).isEqualTo(key);
        assertThat(unavailable.cause()).isEqualTo(PgEntityError.VersionConflict.versionConflict(key, 1L));
    }

    private long rowMicros(String key) {
        return connector.queryOptional(ROW_MICROS, row -> row.getLong("micros"), entity, key).await().unwrap().or(-1L);
    }

    private void insertCorrupt(String key) {
        connector.update("INSERT INTO durable_entity_state (entity, entity_key, version, state) VALUES (?, ?, 1, ?)",
                         entity,
                         key,
                         new byte[]{1, 2, 3}).await().unwrap();
    }

    private static DeleteHook recording(List<List<String>> calls) {
        return (_, recordedEntity, recordedKey) -> recordCall(calls, recordedEntity, recordedKey);
    }

    private static Promise<Unit> recordCall(List<List<String>> calls, String recordedEntity, String recordedKey) {
        calls.add(List.of(recordedEntity, recordedKey));
        return Promise.success(Unit.unit());
    }

    private PgEntityStore<String, Long, Mutator<Long>> store(SqlConnector via) {
        return storeWith(via, DeleteHook.noTimers());
    }

    private PgEntityStore<String, Long, Mutator<Long>> storeWith(SqlConnector via, DeleteHook hook) {
        return PgEntityStore.<String, Long, Mutator<Long>>pgEntityStore(via, entity, key -> key, LONG_CODEC, hook).unwrap();
    }
}
