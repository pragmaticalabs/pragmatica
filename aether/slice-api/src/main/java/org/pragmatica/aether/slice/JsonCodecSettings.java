// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Unit;


/// A slice's JSON codec selection as it stands now (`[json]` in its configuration). Provisioned implicitly for every
/// slice with HTTP routes, and live: a committed configuration change reaches it through [#refresh], and the next
/// [#current] returns the new selection. A slice without routes, or one built before this handle existed, has
/// [#defaults].
public interface JsonCodecSettings {
    /// The selection in force: an immutable snapshot, one read.
    JsonCodecSelection current();
    /// Re-reads the configuration and adopts it when it is valid; an invalid one keeps the current selection. A
    /// handle without a configuration to read does nothing.
    Unit refresh();

    /// A handle that always answers `selection`.
    static JsonCodecSettings fixed(JsonCodecSelection selection) {
        record fixed(JsonCodecSelection current) implements JsonCodecSettings {
            @Override
            public Unit refresh() {
                return Unit.unit();
            }
        }

        return new fixed(selection);
    }

    /// The generated codecs under default options, unchanging.
    static JsonCodecSettings defaults() {
        return fixed(JsonCodecSelection.DEFAULT);
    }
}
