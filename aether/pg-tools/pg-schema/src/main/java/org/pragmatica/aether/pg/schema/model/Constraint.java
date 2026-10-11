// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.pg.schema.model;

import java.util.List;

import org.pragmatica.lang.Option;


public sealed interface Constraint {
    Option<String> name();

    record PrimaryKey(Option<String> name, List<String> columns) implements Constraint {}

    record ForeignKey(Option<String> name,
                      List<String> columns,
                      String refTable,
                      List<String> refColumns,
                      FkAction onUpdate,
                      FkAction onDelete) implements Constraint {}

    record Unique(Option<String> name, List<String> columns) implements Constraint {}

    record Check(Option<String> name, String expression) implements Constraint {}

    record Exclusion(Option<String> name, String method, String definition) implements Constraint {}

    enum FkAction {
        NO_ACTION,
        RESTRICT,
        CASCADE,
        SET_NULL,
        SET_DEFAULT
    }
}
