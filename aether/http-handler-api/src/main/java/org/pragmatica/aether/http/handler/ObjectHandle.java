// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.handler;

import org.pragmatica.serialization.Codec;

/// A stored object an ingress wrote for a request, by reference: what a slice opens to read the bytes (U2). Each component
/// is the text form storage prints for it, so this module needs no storage types on its classpath.
///
/// @param namespace the namespace the object was written in (the serving slice's)
/// @param id        the object id, as `ObjectId.asString()` prints it (26 base32 characters)
/// @param manifest  the manifest block, as `BlockId.hexString()` prints it
/// @param size      the object's length in bytes
/// @param digest    the SHA-256 of the object's bytes, as `Sha256.hexString()` prints it
@Codec
public record ObjectHandle(String namespace, String id, String manifest, long size, String digest) {
    public static ObjectHandle objectHandle(String namespace, String id, String manifest, long size, String digest) {
        return new ObjectHandle(namespace, id, manifest, size, digest);
    }
}
