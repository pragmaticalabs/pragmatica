// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.schema.model;

import org.pragmatica.lang.Option;

import java.util.List;


public sealed interface Constraint {
    Option<String> name();

    record PrimaryKey(Option<String> name, List<String> columns) implements Constraint{}

    record ForeignKey(Option<String> name,
                      List<String> columns,
                      String refTable,
                      List<String> refColumns,
                      FkAction onUpdate,
                      FkAction onDelete) implements Constraint{}

    record Unique(Option<String> name, List<String> columns) implements Constraint{}

    record Check(Option<String> name, String expression) implements Constraint{}

    record Exclusion(Option<String> name, String method, String definition) implements Constraint{}

    enum FkAction {
        NO_ACTION,
        RESTRICT,
        CASCADE,
        SET_NULL,
        SET_DEFAULT
    }
}
