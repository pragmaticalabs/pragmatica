// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit.fake;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.aether.resource.db.PoolConfig;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.testkit.TestKitError;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;


/// Shared state + query engine for the in-memory SQL connector fakes. Records every executed
/// statement and returns scripted rows/update-counts matched by SQL substring. Never runs SQL —
/// the honest-guarantees boundary of the fake path (spec §4.3).
final class StatementRecorder {
    private static final int DEFAULT_AFFECTED = 1;

    private static final DatabaseConnectorConfig FAKE_CONFIG = new DatabaseConnectorConfig(some("testkit"),
                                                                                           some(DatabaseType.POSTGRESQL),
                                                                                           some("localhost"),
                                                                                           some(5432),
                                                                                           some("testkit"),
                                                                                           none(),
                                                                                           none(),
                                                                                           PoolConfig.DEFAULT,
                                                                                           Map.of(),
                                                                                           none(),
                                                                                           none(),
                                                                                           some("postgresql://localhost:5432/testkit"));

    private final Map<String, List<Map<String, Object>>> rowScripts = new ConcurrentHashMap<>();
    private final Map<String, Integer> updateScripts = new ConcurrentHashMap<>();
    private final List<RecordedStatement> statements = new CopyOnWriteArrayList<>();

    @Contract
    void scriptRows(String sqlSubstring, List<Map<String, Object>> rows) {
        rowScripts.put(sqlSubstring, List.copyOf(rows));
    }

    @Contract
    void scriptUpdate(String sqlSubstring, int affected) {
        updateScripts.put(sqlSubstring, affected);
    }

    List<RecordedStatement> recorded() {
        return List.copyOf(statements);
    }

    DatabaseConnectorConfig config() {
        return FAKE_CONFIG;
    }

    <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object[] params) {
        recordStatement(sql, params);

        return matchRows(sql).flatMap(rows -> firstRow(rows, sql))
                        .flatMap(row -> mapper.map(new MapRowAccessor(row)))
                        .async();
    }

    <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object[] params) {
        recordStatement(sql, params);

        return matchRows(sql).flatMap(rows -> mapOptional(mapper, rows))
                        .async();
    }

    <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object[] params) {
        recordStatement(sql, params);

        return matchRows(sql).flatMap(rows -> mapAll(mapper, rows))
                        .async();
    }

    Promise<Integer> update(String sql, Object[] params) {
        recordStatement(sql, params);

        return Promise.success(findScript(updateScripts, sql).or(DEFAULT_AFFECTED));
    }

    Promise<int[]> batch(String sql, List<Object[]> paramsList) {
        paramsList.forEach(params -> recordStatement(sql, params));
        var counts = new int[paramsList.size()];

        Arrays.fill(counts, findScript(updateScripts, sql).or(DEFAULT_AFFECTED));

        return Promise.success(counts);
    }

    @Contract
    private void recordStatement(String sql, Object[] params) {
        statements.add(new RecordedStatement(sql, Arrays.asList(params)));
    }

    private Result<List<Map<String, Object>>> matchRows(String sql) {
        return findScript(rowScripts, sql).toResult(new TestKitError.UnscriptedInteraction("No scripted rows for SQL: " + sql));
    }

    private static Result<Map<String, Object>> firstRow(List<Map<String, Object>> rows, String sql) {
        return option(rows.isEmpty()
                      ? null
                      : rows.getFirst()).toResult(new TestKitError.UnscriptedInteraction("Scripted result set is empty for SQL: " + sql));
    }

    private static <T> Result<Option<T>> mapOptional(RowMapper<T> mapper, List<Map<String, Object>> rows) {
        return rows.isEmpty()
               ? Result.success(none())
               : mapper.map(new MapRowAccessor(rows.getFirst()))
                       .map(Option::some);
    }

    private static <T> Result<List<T>> mapAll(RowMapper<T> mapper, List<Map<String, Object>> rows) {
        return Result.allOf(rows.stream().map(row -> mapper.map(new MapRowAccessor(row))).toList());
    }

    private static <V> Option<V> findScript(Map<String, V> scripts, String sql) {
        return option(scripts.entrySet()
                             .stream()
                             .filter(entry -> sql.contains(entry.getKey()))
                             .map(Map.Entry::getValue)
                             .findFirst()
                             .orElse(null));
    }
}
