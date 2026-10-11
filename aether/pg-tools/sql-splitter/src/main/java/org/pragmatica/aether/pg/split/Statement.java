// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.pg.split;

/// A single SQL statement extracted from a multi-statement script.
///
/// The text is verbatim — exactly the characters that made up the statement in the
/// source (excluding the splitting terminator) — so that downstream checksums remain
/// stable. The start line is 1-based and points at the line on which the statement
/// begins, for diagnostics.
///
/// @param text      verbatim statement text (without the trailing terminator)
/// @param startLine 1-based source line where the statement begins
public record Statement(String text, int startLine) {}
