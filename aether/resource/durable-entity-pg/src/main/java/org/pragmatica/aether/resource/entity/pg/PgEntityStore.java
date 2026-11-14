// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.entity.pg;

import org.pragmatica.aether.resource.Mutator;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.resource.entity.EntityError;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;

/// State-stored durable entities on PostgreSQL (#2100 E-B): one row `(entity, entity_key, version, state, updated_at)`
/// per entity instance, in the table of `schema/V1__create_durable_entity_state.sql`.
///
/// This is the storage half of a `DurableEntity` provider. It does not implement `DurableEntity`: timers (E-C/E-D)
/// wrap it, and [DeleteHook] is the one seam they need (timer removal inside the delete transaction).
///
/// ## Guarantees, per operation, with the mechanism that earns each
///
///   - **[#create]**: of N concurrent creates for one key, exactly one inserts and the rest fail with
///     `EntityAlreadyExists`. Mechanism: `INSERT ... ON CONFLICT (entity, entity_key) DO NOTHING` against the primary
///     key; the insert count (1 or 0) is the verdict. The loser blocks on the winner's uncommitted row until it
///     commits, so there is no window in which two inserts both see the key absent.
///   - **[#get]** (any consistency): reads the committed row on the primary the connector is bound to, in one
///     statement. It reflects every update, create and delete that committed before the statement started; it can
///     miss work still in flight. That is the read-committed read of a single primary, which is what `LINEARIZABLE`
///     asks for with one copy of the data. Mechanism: single-row primary read; there is no replica or cache in this
///     path. A connector bound to a replica or a pooler that routes reads to one would void this guarantee.
///   - **[#update]**: same-key updates are totally ordered and none is lost. Mechanism: `SELECT ... FOR UPDATE` takes the
///     row lock, the mutator runs on the locked value, and `UPDATE ... WHERE version = <read version>` writes it, all in
///     ONE transaction; a competing update blocks on the lock until this one commits, then reads the new value. The
///     version compare-and-set is a second guard: were the lock ever missing, the loser would update zero rows and
///     fail with a version conflict instead of overwriting. A mutator that throws rolls the transaction back and the
///     row is unchanged. Different keys do not contend.
///   - **[#updateIfVersion]**: optimistic update. It reads without locking and writes only if the row is still at
///     `expectedVersion`; of N concurrent calls expecting the same version, at most one succeeds and the rest fail
///     with a version conflict (`StorageUnavailable` wrapping [PgEntityError.VersionConflict]). Mechanism: the
///     compare-and-set in the `UPDATE`'s `WHERE` clause, evaluated under the row lock the `UPDATE` takes.
///   - **[#delete]**: removes the row; deleting an absent key succeeds (idempotent), unlike the fenced-log
///     `DurableEntity.delete`, which fails with `EntityNotFound`. Mechanism: `DELETE ... WHERE` with the row count
///     ignored, then the [DeleteHook] in the same transaction.
///
/// ## Not guaranteed
///
/// No ordering across keys. No guarantee for a connector routed to a replica. Timers are outside this class.
///
/// ## Error mapping
///
/// Every failure leaves as an `EntityError`: `EntityAlreadyExists`, `EntityNotFound`, `StorageUnavailable` (transient
/// cause, retry) or `StorageFailed`. See [PgEntityErrors] for the members that are never produced.
public final class PgEntityStore<K, S, C extends Mutator<S>> {
    private static final String INSERT = "INSERT INTO durable_entity_state (entity, entity_key, version, state, updated_at)"
                                         + " VALUES (?, ?, 1, ?, now()) ON CONFLICT (entity, entity_key) DO NOTHING";
    private static final String SELECT_STATE = "SELECT state FROM durable_entity_state WHERE entity = ? AND entity_key = ?";
    private static final String SELECT_VERSIONED = "SELECT version, state FROM durable_entity_state WHERE entity = ? AND entity_key = ?";
    private static final String SELECT_FOR_UPDATE = SELECT_VERSIONED + " FOR UPDATE";
    private static final String CAS_UPDATE = "UPDATE durable_entity_state SET version = version + 1, state = ?, updated_at = now()"
                                             + " WHERE entity = ? AND entity_key = ? AND version = ?";
    private static final String DELETE = "DELETE FROM durable_entity_state WHERE entity = ? AND entity_key = ?";
    private static final RowMapper<byte[]> STATE_ROW = row -> row.getBytes("state");
    private static final RowMapper<Versioned<byte[]>> VERSIONED_ROW = row -> Result.all(row.getLong("version"),
                                                                                         row.getBytes("state"))
                                                                                    .map(Versioned::versioned);

    private final SqlConnector connector;
    private final String entity;
    private final Fn1<String, K> keyEncoder;
    private final StateCodec<S> codec;
    private final DeleteHook deleteHook;

    private PgEntityStore(SqlConnector connector,
                          String entity,
                          Fn1<String, K> keyEncoder,
                          StateCodec<S> codec,
                          DeleteHook deleteHook) {
        this.connector = connector;
        this.entity = entity;
        this.keyEncoder = keyEncoder;
        this.codec = codec;
        this.deleteHook = deleteHook;
    }

    /// A store for the named entity type. Rows of different `entity` names share the table and never interact.
    ///
    /// @param connector  the connector to the primary database
    /// @param entity     the entity type name (the row namespace); must not be blank
    /// @param keyEncoder renders a key as its stable text form; equal keys must render equal
    /// @param codec      state to bytes and back
    /// @param deleteHook the timer-layer seam run inside the delete transaction
    public static <K, S, C extends Mutator<S>> Result<PgEntityStore<K, S, C>> pgEntityStore(SqlConnector connector,
                                                                                            String entity,
                                                                                            Fn1<String, K> keyEncoder,
                                                                                            StateCodec<S> codec,
                                                                                            DeleteHook deleteHook) {
        return Verify.ensure(entity, Verify.Is::notBlank)
                     .map(name -> new PgEntityStore<>(connector, name, keyEncoder, codec, deleteHook));
    }

    /// A store with no timer layer ([DeleteHook#noTimers()]).
    public static <K, S, C extends Mutator<S>> Result<PgEntityStore<K, S, C>> pgEntityStore(SqlConnector connector,
                                                                                            String entity,
                                                                                            Fn1<String, K> keyEncoder,
                                                                                            StateCodec<S> codec) {
        return pgEntityStore(connector, entity, keyEncoder, codec, DeleteHook.noTimers());
    }

    /// Insert `initial` at version 1; fails with `EntityAlreadyExists` if the key holds a row.
    public Promise<S> create(K key, S initial) {
        return createAt(keyEncoder.apply(key), initial);
    }

    /// The committed state of `key`, or none. Serves every `ReadConsistency` (see the class guarantees).
    public Promise<Option<S>> get(K key) {
        return getAt(keyEncoder.apply(key));
    }

    /// The committed state with its version, or none: the input of [#updateIfVersion].
    public Promise<Option<Versioned<S>>> getVersioned(K key) {
        return getVersionedAt(keyEncoder.apply(key));
    }

    /// Pessimistic update: row lock, mutator, version CAS, one transaction. Fails with `EntityNotFound` for an absent key.
    public Promise<S> update(K key, C mutator) {
        return mutate(keyEncoder.apply(key), mutator, SELECT_FOR_UPDATE, Option.none());
    }

    /// Optimistic update: applies `mutator` only if the row is still at `expectedVersion`. Fails with
    /// `EntityNotFound` for an absent key and with a version conflict if another writer committed first.
    public Promise<S> updateIfVersion(K key, long expectedVersion, C mutator) {
        return mutate(keyEncoder.apply(key), mutator, SELECT_VERSIONED, Option.some(expectedVersion));
    }

    /// Remove `key`; succeeds whether or not it existed.
    public Promise<Unit> delete(K key) {
        return deleteAt(keyEncoder.apply(key));
    }

    private Promise<S> createAt(String id, S initial) {
        return codec.encode(initial)
                    .async()
                    .flatMap(bytes -> connector.update(INSERT, entity, id, bytes))
                    .flatMap(inserted -> createdOrExists(inserted, id, initial))
                    .mapError(cause -> PgEntityErrors.toEntityError(id, cause));
    }

    private static <S> Promise<S> createdOrExists(int inserted, String id, S initial) {
        return inserted == 1
               ? Promise.success(initial)
               : new EntityError.EntityAlreadyExists(id).promise();
    }

    private Promise<Option<S>> getAt(String id) {
        return connector.queryOptional(SELECT_STATE, STATE_ROW, entity, id)
                        .flatMap(this::decodeOptional)
                        .mapError(cause -> PgEntityErrors.toEntityError(id, cause));
    }

    private Promise<Option<S>> decodeOptional(Option<byte[]> stored) {
        return stored.fold(() -> Promise.success(Option.<S>none()),
                           bytes -> codec.decode(bytes).map(Option::some).async());
    }

    private Promise<Option<Versioned<S>>> getVersionedAt(String id) {
        return connector.queryOptional(SELECT_VERSIONED, VERSIONED_ROW, entity, id)
                        .flatMap(this::decodeVersionedOptional)
                        .mapError(cause -> PgEntityErrors.toEntityError(id, cause));
    }

    private Promise<Option<Versioned<S>>> decodeVersionedOptional(Option<Versioned<byte[]>> stored) {
        return stored.fold(() -> Promise.success(Option.<Versioned<S>>none()),
                           row -> decodeRow(row).map(Option::some).async());
    }

    private Result<Versioned<S>> decodeRow(Versioned<byte[]> row) {
        return codec.decode(row.state()).map(state -> Versioned.versioned(row.version(), state));
    }

    private Promise<S> mutate(String id, C mutator, String select, Option<Long> expectedVersion) {
        return connector.transactional(tx -> mutateIn(tx, id, mutator, select, expectedVersion))
                        .mapError(cause -> PgEntityErrors.toEntityError(id, cause));
    }

    private Promise<S> mutateIn(SqlConnector tx, String id, C mutator, String select, Option<Long> expectedVersion) {
        return tx.queryOptional(select, VERSIONED_ROW, entity, id)
                 .flatMap(row -> present(row, id))
                 .flatMap(row -> versionMatches(row, id, expectedVersion))
                 .flatMap(row -> applyAndWrite(tx, id, mutator, row));
    }

    private static Promise<Versioned<byte[]>> present(Option<Versioned<byte[]>> row, String id) {
        return row.fold(() -> new EntityError.EntityNotFound(id).<Versioned<byte[]>>promise(), Promise::success);
    }

    private static Promise<Versioned<byte[]>> versionMatches(Versioned<byte[]> row, String id, Option<Long> expectedVersion) {
        return expectedVersion.fold(() -> Promise.success(row),
                                    expected -> sameVersion(row, id, expected));
    }

    private static Promise<Versioned<byte[]>> sameVersion(Versioned<byte[]> row, String id, long expected) {
        return row.version() == expected
               ? Promise.success(row)
               : PgEntityError.VersionConflict.versionConflict(id, expected).promise();
    }

    private Promise<S> applyAndWrite(SqlConnector tx, String id, C mutator, Versioned<byte[]> row) {
        return codec.decode(row.state())
                    .flatMap(current -> applyMutator(id, mutator, current))
                    .async()
                    .flatMap(next -> writeNext(tx, id, row.version(), next));
    }

    private static <S, C extends Mutator<S>> Result<S> applyMutator(String id, C mutator, S current) {
        return Result.lift(thrown -> PgEntityError.MutatorFailed.mutatorFailed(id, thrown), () -> mutator.apply(current));
    }

    private Promise<S> writeNext(SqlConnector tx, String id, long readVersion, S next) {
        return codec.encode(next)
                    .async()
                    .flatMap(bytes -> tx.update(CAS_UPDATE, bytes, entity, id, readVersion))
                    .flatMap(updated -> casApplied(updated, id, readVersion, next));
    }

    private static <S> Promise<S> casApplied(int updated, String id, long readVersion, S next) {
        return updated == 1
               ? Promise.success(next)
               : PgEntityError.VersionConflict.versionConflict(id, readVersion).promise();
    }

    private Promise<Unit> deleteAt(String id) {
        return connector.transactional(tx -> deleteIn(tx, id))
                        .mapError(cause -> PgEntityErrors.toEntityError(id, cause));
    }

    private Promise<Unit> deleteIn(SqlConnector tx, String id) {
        return tx.update(DELETE, entity, id)
                 .flatMap(_ -> deleteHook.afterDelete(tx, entity, id));
    }
}
