// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.Arrays;
import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;


/// #1934: a throw out of a consumer's delivery pass — a reader that throws instead of failing its promise, or the
/// pass's own runaway recursion ([StackOverflowError]) — kept with the top frames of what was thrown. Both catch sites
/// used to drop the throwable, so a deterministic recursion was reported with no frame of it anywhere; the frames
/// are what an operator needs to find the defect. A throw is a defect, not a routine read failure, which is why a
/// consumer's run of these is reported ([ConsumerRuntimeState]) while a failed read stays a DEBUG line.
record PassEscape(String thrown, List<String> topFrames) implements Cause {
    /// How many frames of the throwable are kept: enough to show a recursion's repeating frame and what entered it.
    static final int TOP_FRAMES = 8;
    /// How many frames a call's overflow carries in its failure message, which reaches the retry log and the
    /// dead-letter entry.
    static final int CALL_FRAMES = 3;

    static PassEscape passEscape(Throwable thrown) {
        return new PassEscape(thrown.toString(), frames(thrown, TOP_FRAMES));
    }

    /// #1934: a foreign call (handler, sink) that overflowed its stack fails that call with its top frames, so the
    /// error strategy's log and the dead-letter entry name the recursing method.
    static Cause overflowedCall(StackOverflowError overflow) {
        return Causes.cause("Call overflowed its stack at " + String.join(" <- ", frames(overflow, CALL_FRAMES)));
    }

    private static List<String> frames(Throwable thrown, int count) {
        return Arrays.stream(thrown.getStackTrace())
                     .limit(count)
                     .map(StackTraceElement::toString)
                     .toList();
    }

    @Override
    public String message() {
        return "Delivery pass threw " + thrown;
    }

    /// The kept frames on one line, innermost first; `(no frames)` for a throwable the JVM created without a trace.
    String framesText() {
        return topFrames.isEmpty()
               ? "(no frames)"
               : String.join(" <- ", topFrames);
    }
}
