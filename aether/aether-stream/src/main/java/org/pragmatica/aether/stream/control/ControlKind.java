// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.control;

import java.util.Map;

import org.pragmatica.lang.Option;


/// The kind of a [ControlRecord] (#2590 P1): which admission predicate judges it, and how the latest-per-key index
/// applies it. Two kinds are the mechanism's own: [Builtin#CARRY], the owner's restatement of a key's latest record,
/// which bypasses admission, and [Builtin#TOMBSTONE], which removes a key. Every other kind is [Named] by its user
/// (P2's CURSOR and REWIND, P3's FOLD_MARKER) and judged by the [Admission] registered for it.
public sealed interface ControlKind {
    String name();

    /// The kind written as `name`: a builtin when the name is one, otherwise a named kind. The name is what the record's
    /// framing carries, so this is the decode half of [#name()]; a user kind therefore cannot take a builtin's name.
    static ControlKind controlKind(String name) {
        return Builtin.byName(name)
                      .<ControlKind> map(builtin -> builtin)
                      .or(() -> new Named(name));
    }

    enum Builtin implements ControlKind {
        CARRY,
        TOMBSTONE;
        private static final Map<String, Builtin> BY_NAME = Map.of(CARRY.name(), CARRY, TOMBSTONE.name(), TOMBSTONE);
        static Option<Builtin> byName(String name) {
            return Option.option(BY_NAME.get(name));
        }
    }

    record Named(String name) implements ControlKind {}

    /// The admission SPI lookup: the predicate registered for a named kind, none for a kind nobody registered.
    @FunctionalInterface
    interface Admissions {
        Option<Admission> admissionFor(Named kind);

        static Admissions admissions(Map<String, Admission> byName) {
            var registered = Map.copyOf(byName);

            return kind -> Option.option(registered.get(kind.name()));
        }
    }
}
