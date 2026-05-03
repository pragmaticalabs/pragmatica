// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.util.List;
import java.util.Set;


public record SshPublicKey(String value) {
    private static final Set<String> SUPPORTED_ALGORITHMS = Set.of("ssh-rsa",
                                                                   "ssh-ed25519",
                                                                   "ssh-dss",
                                                                   "ecdsa-sha2-nistp256",
                                                                   "ecdsa-sha2-nistp384",
                                                                   "ecdsa-sha2-nistp521");

    public static Result<SshPublicKey> sshPublicKey(String raw) {
        return validateNonNull(raw).flatMap(SshPublicKey::validateSingleLine)
                              .flatMap(SshPublicKey::validateAlgorithm)
                              .map(SshPublicKey::new);
    }

    public static Result<List<SshPublicKey>> parseAll(List<String> rawLines) {
        return Result.allOf(rawLines.stream().map(SshPublicKey::sshPublicKey)
                                           .toList());
    }

    public String hetznerKeyName(String prefix) {
        var blob = blob();
        var suffix = blob.length() >= 8
                    ? blob.substring(0, 8)
                    : blob;
        return prefix + "-" + suffix;
    }

    public String comment() {
        var parts = value.split("\\s+", 3);
        return parts.length >= 3
              ? parts[2]
              : "";
    }

    public String algorithm() {
        return value.split("\\s+", 3) [0];
    }

    public String blob() {
        return value.split("\\s+", 3) [1];
    }

    private static Result<String> validateNonNull(String raw) {
        if (raw == null) {return new InvalidPublicKey("Public key is null").result();}
        var trimmed = raw.trim();
        if (trimmed.isEmpty()) {return new InvalidPublicKey("Public key is blank").result();}
        return Result.success(trimmed);
    }

    private static Result<String> validateSingleLine(String raw) {
        if (raw.indexOf('\n') >= 0 || raw.indexOf('\r') >= 0) {return new InvalidPublicKey("Public key must be a single line").result();}
        return Result.success(raw);
    }

    private static Result<String> validateAlgorithm(String raw) {
        var parts = raw.split("\\s+", 3);
        if (parts.length <2) {return new InvalidPublicKey("Public key must have format '<algo> <base64-blob> [<comment>]'").result();}
        if (!SUPPORTED_ALGORITHMS.contains(parts[0])) {return new InvalidPublicKey("Unsupported SSH key algorithm '" + parts[0] + "'. Supported: " + SUPPORTED_ALGORITHMS).result();}
        if (parts[1].isBlank()) {return new InvalidPublicKey("Public key blob is blank").result();}
        return Result.success(raw);
    }

    public record InvalidPublicKey(String detail) implements Cause {
        @Override public String message() {
            return "Invalid SSH public key: " + detail;
        }
    }
}
