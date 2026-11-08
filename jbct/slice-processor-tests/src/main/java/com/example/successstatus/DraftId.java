// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.successstatus;

import org.pragmatica.aether.slice.mapping.ValueMapping;
import org.pragmatica.lang.Result;

/// A value object: a Location template field of this type renders through valueMapping().lower(), not toString().
public record DraftId(String value) {
    public static Result<DraftId> draftId(String raw) {
        return Result.success(new DraftId(raw));
    }

    public static ValueMapping<DraftId, String> valueMapping() {
        return ValueMapping.of(DraftId::value, DraftId::draftId);
    }
}
