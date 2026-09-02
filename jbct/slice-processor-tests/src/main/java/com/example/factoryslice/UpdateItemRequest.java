// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package com.example.factoryslice;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;


/// Path + body request record that declares a validating factory: the merged argument list (path
/// lambda var + body accessors) feeds the factory instead of the canonical constructor.
public record UpdateItemRequest(Long id, String name) {
    public static Result<UpdateItemRequest> updateItemRequest(Long id, String name) {
        return Result.all(Verify.ensure(id, Verify.Is::positive),
                          Verify.ensure(name, Verify.Is::present))
                     .map(UpdateItemRequest::new);
    }
}
