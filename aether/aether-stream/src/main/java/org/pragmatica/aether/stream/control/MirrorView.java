// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.control;

import org.pragmatica.lang.Option;


/// The owner's view of committed state outside the control log that an [Admission] may consult (#2590 P1), such as the
/// committed consumer assignment a cursor record must match (P2). It reads THIS node's applied state, so a predicate
/// reading it is pure for a given owner. P1 has no user, so the shape is a keyed byte read until P2 types it.
@FunctionalInterface
public interface MirrorView {
    Option<byte[]> read(String key);
    MirrorView EMPTY = _ -> Option.none();
}
