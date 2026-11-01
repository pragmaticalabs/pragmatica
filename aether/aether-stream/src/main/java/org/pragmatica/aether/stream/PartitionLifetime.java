// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/// #2201 NT-24: the declared lifetime of one row of [PartitionState], and the methods that remove its entries. Read by
/// reflection in a test, so the declaration cannot drift from the class without that test going red.
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@interface PartitionLifetime {
    Scope value();
    /// The names of the methods (of [StreamPartitionManager], [StreamEntries] or [PartitionState]) that remove entries of
    /// this row.
    String[] removedBy();

    /// The sub-record a row belongs to.
    enum Scope {
        /// Lives as long as the ring it describes: dropped when the ring is released or installed.
        RING,
        /// Lives as long as the partition on this node, across ring replacements.
        STANDING,
        /// Lives for one repair episode, and is closed when the subject goes away.
        REPAIR_EPISODE
    }
}
