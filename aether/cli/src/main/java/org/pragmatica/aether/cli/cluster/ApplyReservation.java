// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

/// A block of node numbers `[fromN, toN)` reserved for `(source, role)` before any create uses them.
public record ApplyReservation(String source, String role, long fromN, long toN) {
    public static ApplyReservation applyReservation(String source, String role, long fromN, long toN) {
        return new ApplyReservation(source, role, fromN, toN);
    }
}
