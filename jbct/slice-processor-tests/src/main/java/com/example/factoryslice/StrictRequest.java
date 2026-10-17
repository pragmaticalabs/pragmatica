// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package com.example.factoryslice;

/// #1573 (v1608 R2-2) fixture: a path-built request record with NO validating factory whose canonical
/// constructor throws on client input. The generated handler must answer that with a typed 400 before the
/// slice method runs, never let it escape as if the method itself had thrown.
public record StrictRequest(String code) {
    public StrictRequest {
        if (code.startsWith("bad")) {
            throw new IllegalArgumentException("code must not start with 'bad': " + code);
        }
    }
}
