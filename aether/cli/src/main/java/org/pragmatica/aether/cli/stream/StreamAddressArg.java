// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.stream;

import org.pragmatica.aether.slice.stream.StreamAddress;
import org.pragmatica.lang.Result;

/// Adapter Leaf — wraps the canonical `<namespace>:<stream>:<version>` parser from the
/// shared `slice-api` for use as a CLI argument. Surfaces the same structured
/// `StreamAddressError` causes that the rest of the platform uses, so error messages stay
/// aligned across CLI / HTTP / SDK.
public sealed interface StreamAddressArg {
    static Result<StreamAddress> parse(String raw) {
        return StreamAddress.streamAddress(raw);
    }

    record unused() implements StreamAddressArg {}
}
