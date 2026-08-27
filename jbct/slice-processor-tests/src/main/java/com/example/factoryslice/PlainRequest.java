// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package com.example.factoryslice;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;


/// Pure-body request record with no *validating factory*: the generated route keeps the
/// canonical-constructor path byte-identical -- the deserialized value is handed straight to the
/// delegate, with no reconstruction.
///
/// The `fromParts` method below is a deliberate decoy. It is static and returns `Result<Self>`, but
/// its parameter list does not equal the record components in order, so it cannot reconstruct this
/// record from its own accessors and the rule must not pick it up. Detection is a shape check, not
/// a return-type check.
public record PlainRequest(String note) {
    public static Result<PlainRequest> fromParts(String note, String suffix) {
        return Verify.ensure(note, Verify.Is::present)
                     .map(valid -> new PlainRequest(valid + suffix));
    }
}
