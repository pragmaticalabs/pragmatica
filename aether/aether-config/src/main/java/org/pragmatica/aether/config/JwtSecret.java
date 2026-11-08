// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// The HS256 shared secret (#2880). Its `toString` never prints the key, so a `JwtConfig` that is logged, or a
/// failed assertion that renders it, cannot leak the secret; equality compares in constant time.
///
/// The configured text is used as its UTF-8 bytes. RFC 7518 section 3.2 requires a key at least as long as the hash
/// output, 256 bits, so a shorter secret is refused at construction ([#MIN_BYTES]). Build it with [#jwtSecret];
/// the canonical constructor is public only because a record's must be.
public record JwtSecret(byte[] value) {
    /// RFC 7518 section 3.2: HS256 keys carry at least 256 bits.
    public static final int MIN_BYTES = 32;

    public JwtSecret {
        value = value.clone();
    }

    /// The secret, or a cause that names the required length and never the value.
    public static Result<JwtSecret> jwtSecret(String text) {
        var bytes = text.getBytes(StandardCharsets.UTF_8);

        return bytes.length >= MIN_BYTES
               ? Result.success(new JwtSecret(bytes))
               : Causes.cause("HS256 secret is too short: " + bytes.length + " bytes, at least " + MIN_BYTES
                              + " (256 bits, RFC 7518 section 3.2) are required").result();
    }

    @Override
    public byte[] value() {
        return value.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JwtSecret(var otherValue) && MessageDigest.isEqual(value, otherValue);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return "JwtSecret[redacted]";
    }
}
