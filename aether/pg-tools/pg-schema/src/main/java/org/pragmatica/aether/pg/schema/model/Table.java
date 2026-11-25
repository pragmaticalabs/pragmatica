// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.pg.schema.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.lang.Option;


public record Table(String name,
                    String schema,
                    List<Column> columns,
                    List<Constraint> constraints,
                    List<Index> indexes,
                    Option<PartitionBy> partitioning,
                    Option<String> comment) {
    public record PartitionBy(PartitionStrategy strategy, List<String> columns) {}

    public enum PartitionStrategy {
        RANGE,
        LIST,
        HASH
    }

    public static Table table(String name, String schema, List<Column> columns, List<Constraint> constraints) {
        return new Table(name,
                         schema,
                         withKeyColumnsNotNull(columns, constraints),
                         constraints,
                         List.of(),
                         Option.empty(),
                         Option.empty());
    }

    public Option<Column> column(String columnName) {
        return columns.stream()
                      .filter(c -> c.name()
                                    .equals(columnName))
                      .findFirst()
                      .map(Option::present)
                      .orElse(Option.empty());
    }

    public Table withColumn(Column col) {
        var newCols = new ArrayList<>(columns);

        newCols.add(col);

        return new Table(name, schema, List.copyOf(newCols), constraints, indexes, partitioning, comment);
    }

    public Table withoutColumn(String colName) {
        var newCols = columns.stream().filter(c -> !c.name()
                                                     .equals(colName)).toList();

        return new Table(name, schema, newCols, constraints, indexes, partitioning, comment);
    }

    public Table withColumnReplaced(String colName, Column newCol) {
        var newCols = columns.stream().map(c -> c.name()
                                                 .equals(colName)
                                                ? newCol
                                                : c).toList();

        return new Table(name, schema, newCols, constraints, indexes, partitioning, comment);
    }

    public Table withConstraint(Constraint constraint) {
        var newConstraints = new ArrayList<>(constraints);

        newConstraints.add(constraint);

        return new Table(name,
                         schema,
                         withKeyColumnsNotNull(columns, List.of(constraint)),
                         List.copyOf(newConstraints),
                         indexes,
                         partitioning,
                         comment);
    }

    /// Removing a primary key leaves its columns NOT NULL, as PostgreSQL 17 does: only DROP NOT NULL relaxes a column.
    public Table withoutConstraint(String constraintName) {
        var newConstraints = constraints.stream()
                                        .filter(c -> !c.name()
                                                       .isPresent() || !c.name()
                                                                         .unwrap()
                                                                         .equals(constraintName))
                                        .toList();

        return new Table(name, schema, columns, newConstraints, indexes, partitioning, comment);
    }

    public Table withIndex(Index index) {
        var newIndexes = new ArrayList<>(indexes);

        newIndexes.add(index);

        return new Table(name, schema, columns, constraints, List.copyOf(newIndexes), partitioning, comment);
    }

    public Table withComment(String text) {
        return new Table(name, schema, columns, constraints, indexes, partitioning, Option.present(text));
    }

    public Table renamed(String newName) {
        return new Table(newName, schema, columns, constraints, indexes, partitioning, comment);
    }

    /// PostgreSQL makes every column of a primary key NOT NULL when the key is declared (#2795), whatever its definition
    /// says. Applied once per added constraint, so a later DROP NOT NULL holds. No other constraint changes nullability.
    private static List<Column> withKeyColumnsNotNull(List<Column> columns, List<Constraint> added) {
        var keyColumns = added.stream()
                              .filter(Constraint.PrimaryKey.class::isInstance)
                              .map(Constraint.PrimaryKey.class::cast)
                              .flatMap(key -> key.columns()
                                                 .stream())
                              .collect(Collectors.toSet());

        return columns.stream()
                      .map(column -> withKeyNullability(column, keyColumns))
                      .toList();
    }

    private static Column withKeyNullability(Column column, Set<String> keyColumns) {
        return keyColumns.contains(column.name())
               ? column.withNullable(false)
               : column;
    }
}
