// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.split;

import org.pragmatica.lang.Cause;


/// Typed failures produced by [StatementSplitter] for malformed input.
///
/// A malformed script (an unterminated span at end of input) is a failure value, never an
/// exception or a silent mis-split. Each variant carries the 1-based line on which the
/// offending span was opened so an operator can locate the defect.
public sealed interface SplitError extends Cause {
    /// 1-based source line on which the unterminated span was opened.
    int openedAtLine();

    /// A string literal (`'…'` or `E'…'`) was opened but never closed before end of input.
    record UnterminatedString(int openedAtLine) implements SplitError {
        @Override
        public String message() {
            return "Unterminated string literal opened at line " + openedAtLine;
        }
    }

    /// A dollar-quoted body (`$tag$…$tag$`) was opened but its closing tag never appeared.
    record UnterminatedDollarQuote(String tag, int openedAtLine) implements SplitError {
        @Override
        public String message() {
            return "Unterminated dollar-quoted string with tag '" + tag + "' opened at line " + openedAtLine;
        }
    }

    /// A block comment (`/*…*/`) was opened but never closed before end of input.
    record UnterminatedBlockComment(int openedAtLine) implements SplitError {
        @Override
        public String message() {
            return "Unterminated block comment opened at line " + openedAtLine;
        }
    }

    /// A quoted identifier (`"…"`) was opened but never closed before end of input.
    record UnterminatedQuotedIdentifier(int openedAtLine) implements SplitError {
        @Override
        public String message() {
            return "Unterminated quoted identifier opened at line " + openedAtLine;
        }
    }

    /// A `COPY … FROM STDIN` data block was opened but its `\\.` terminator never appeared.
    record UnterminatedCopyData(int openedAtLine) implements SplitError {
        @Override
        public String message() {
            return "Unterminated COPY data block opened at line " + openedAtLine;
        }
    }
}
