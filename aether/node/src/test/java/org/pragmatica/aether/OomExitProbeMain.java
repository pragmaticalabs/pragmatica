// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether;

import java.util.ArrayList;
import java.util.List;


/// Child process of `OomExitProbeTest`: exhausts the Java heap for real and swallows the error.
///
/// The heap must be EXHAUSTED, never `throw new OutOfMemoryError()`: only an allocation the VM cannot
/// satisfy routes through HotSpot's `report_java_out_of_memory`, which is where
/// `-XX:+ExitOnOutOfMemoryError` acts. And the `catch` must not allocate until the hoard is released
/// — `t.toString()` on a full heap throws a second OOM out of the handler and kills the main thread,
/// which would make the control arm die for a reason unrelated to the flag (measured before this
/// class was written). The `CAUGHT` line is printed only after `clear()` freed the heap.
///
/// Plain `main`, no framework, no dependencies: the test launches it on a classpath of one directory.
public final class OomExitProbeMain {
    static final String STARTED = "probe: allocating";
    static final String CAUGHT = "probe: caught OutOfMemoryError";

    private OomExitProbeMain() {}

    @SuppressWarnings({"JBCT-EX-01", "JBCT-EX-02"})
    public static void main(String[] args) throws InterruptedException {
        System.out.println(STARTED);
        List<byte[]> hoard = new ArrayList<>();

        while (true) {
            try {
                hoard.add(new byte[1 << 20]);
            } catch (Throwable t) {
                hoard.clear();
                System.out.println(CAUGHT);
                Thread.sleep(50);
            }
        }
    }
}
