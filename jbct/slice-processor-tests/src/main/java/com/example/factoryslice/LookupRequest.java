// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.factoryslice;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;


/// Path-only request record that declares a validating factory: the path argument feeds the factory
/// instead of the canonical constructor.
public record LookupRequest(String code) {
    public static Result<LookupRequest> lookupRequest(String code) {
        return Verify.ensure(code, Verify.Is::present).map(LookupRequest::new);
    }
}
