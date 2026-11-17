// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.entity.pg;

import org.pragmatica.lang.Result;


/// Converts an entity state to and from the `bytea` the store keeps. The store never interprets the bytes.
///
/// Both directions can fail and say so: `encode` for a state the codec cannot represent, `decode` for stored bytes
/// that are corrupt or were written by an incompatible codec. The failure surfaces to the caller as
/// `EntityError.StorageFailed`.
public interface StateCodec<S> {
    Result<byte[]> encode(S state);
    Result<S> decode(byte[] bytes);
}
