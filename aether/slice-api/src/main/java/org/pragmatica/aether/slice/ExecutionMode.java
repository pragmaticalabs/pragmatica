// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

public enum ExecutionMode {
    SINGLE,
    ALL,
    /// Wire sentinel (#964): an ordinal this node cannot name decodes here instead of throwing.
    /// Runs nowhere: an unreadable execution mode must not be guessed into leader-only or everywhere.
    /// Must stay LAST — a new constant appended after it, or inserted before it, is read as UNKNOWN
    /// by an older node either way.
    UNKNOWN
}
