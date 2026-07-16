// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import java.util.List;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.SliceCodec;


public interface Slice {
    default Promise<Unit> start() {
        return Promise.unitPromise();
    }

    default Promise<Unit> stop() {
        return Promise.unitPromise();
    }

    List<SliceMethod<?, ?>> methods();

    default SliceCodec codec(SliceCodec parent) {
        return parent;
    }
}
