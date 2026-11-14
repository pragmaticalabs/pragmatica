// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.entity.pg;

/// The verdict of [PgEntityStore#delete]. The store never fails on an absent row; the `DurableEntity` adapter (E-D, a
/// later lane) maps `ABSENT` to `EntityNotFound`, per the `DurableEntity.delete` contract.
public enum DeleteOutcome {
    /// This call removed the row.
    DELETED,
    /// There was no row: never created, or already deleted.
    ABSENT;
    /// `DELETED` when the `DELETE` removed at least one row, otherwise `ABSENT`.
    static DeleteOutcome fromRemovedRows(int removed) {
        return removed > 0
               ? DELETED
               : ABSENT;
    }
}
