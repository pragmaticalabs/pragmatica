// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.LifecycleMethodExecutionExceptionHandler;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;

/// Dumps every thread's stack when a LIFECYCLE method fails (#749 / #750).
///
/// ## Why this exists
///
/// #556 added `junit.jupiter.execution.timeout.lifecycle.method.default = 8m` to convert a silent
/// hang into a NAMED failure, and it worked exactly as designed: three CI runs reported
/// `tearDown() timed out after 8 minutes` on `ClusterProvisioningDiagnosticsProbeTest`. But a name
/// is not a diagnosis. The report gives the timeout and the harness's own outer frames — it does
/// NOT give the stack of the thread that was actually stuck, so "which step inside
/// `EmberCluster.stop()` blocks" stayed UNKNOWN across all three occurrences and could only be
/// answered on some future run.
///
/// This closes that gap. The next occurrence carries its own diagnosis.
///
/// ## What it captures and why all threads
///
/// The failing lifecycle thread is rarely the interesting one. When `stop()` hangs, the JUnit
/// thread is parked in `Promise.await()` while the actual blockage sits on a node's own thread —
/// so a dump of only the failing thread would show the wait, not its cause. `dumpAllThreads` with
/// locks included names the blocked thread, what it is blocked ON, and who holds it.
///
/// It also discriminates the two readings #750 turns on: a carrier pool that cannot schedule the
/// timeout task looks different from a timeout that fired and did not help, and both are visible
/// here — virtual threads' carriers appear as platform threads in this dump.
///
/// ## Deliberately not narrowed
///
/// Fires on ANY lifecycle failure, not only `TimeoutException`. A lifecycle method that fails for
/// another reason while a cluster is wedged is exactly as hard to diagnose after the fact, and the
/// cost is one dump on a run that is already failing. Narrowing it to timeouts would optimise the
/// case we happen to have seen.
///
/// Output goes to stderr, which surefire captures into the report the CI artifact already
/// contains — no new file to collect, no new plumbing to keep working.
///
/// ## Why BOTH handler interfaces
///
/// This first shipped implementing only `LifecycleMethodExecutionExceptionHandler`, scoped to the
/// failures then known: #727's two members are a `@BeforeAll` and an `@AfterAll` timeout. Within
/// hours, #749 recurred on CI through `NodeLifecyclePeriodicArmingForgeTest.stop_disarmsEveryNode`
/// — **a `@Test` method** — and produced no dump, because no lifecycle handler ran. The run that
/// would have answered "which step inside `stop()` blocks" happened with the diagnostic installed
/// and captured nothing, one interface away.
///
/// So it now implements `TestExecutionExceptionHandler` as well. The generalisable point, and the
/// reason this comment exists rather than a silent second `implements`: **an instrument validated
/// for one question is not validated for its neighbour.** A hang reaching JUnit by a different
/// path is still the hang you built the instrument for.
public class HangDiagnosticExtension implements LifecycleMethodExecutionExceptionHandler,
                                                TestExecutionExceptionHandler {
    /// `@Test` bodies — the path #749 recurred through. A node-stop hang can surface here just as
    /// readily as in a lifecycle method, and the dump is worth exactly as much.
    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        dump(context, "@Test", throwable);

        throw throwable;
    }

    @Override
    public void handleBeforeAllMethodExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        dump(context, "@BeforeAll", throwable);

        throw throwable;
    }

    @Override
    public void handleAfterAllMethodExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        dump(context, "@AfterAll", throwable);

        throw throwable;
    }

    @Override
    public void handleBeforeEachMethodExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        dump(context, "@BeforeEach", throwable);

        throw throwable;
    }

    @Override
    public void handleAfterEachMethodExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        dump(context, "@AfterEach", throwable);

        throw throwable;
    }

    /// Never allow diagnostics to change the outcome: the original throwable is always rethrown by
    /// the callers, and a failure to produce the dump is reported rather than propagated. An
    /// instrument that can convert a timeout into a different error would corrupt the very ledger
    /// it exists to inform.
    private static void dump(ExtensionContext context, String phase, Throwable throwable) {
        try {
            var header = "=== HANG DIAGNOSTIC (#749/#750) === " + phase + " failed in "
                         + context.getDisplayName() + ": " + throwable.getClass().getSimpleName()
                         + ": " + throwable.getMessage();

            System.err.println(header);

            var infos = ManagementFactory.getThreadMXBean().dumpAllThreads(true, true);

            System.err.println("--- " + infos.length + " threads ---");

            for (var info : infos) {
                System.err.print(render(info));
            }

            System.err.println("=== END HANG DIAGNOSTIC ===");
        } catch (Throwable diagnosticFailure) {
            System.err.println("HANG DIAGNOSTIC failed to capture a thread dump: " + diagnosticFailure);
        }
    }

    /// `ThreadInfo.toString()` truncates the stack at 8 frames, which is routinely too shallow to
    /// reach the blocking call through a Promise chain. Rendered explicitly for the full depth.
    private static String render(ThreadInfo info) {
        var sb = new StringBuilder(512);

        sb.append('"').append(info.getThreadName()).append("\" #").append(info.getThreadId())
          .append(' ').append(info.getThreadState());

        if (info.getLockName() != null) {
            sb.append(" on ").append(info.getLockName());
        }

        if (info.getLockOwnerName() != null) {
            sb.append(" owned by \"").append(info.getLockOwnerName()).append('"');
        }

        sb.append('\n');

        for (var frame : info.getStackTrace()) {
            sb.append("\tat ").append(frame).append('\n');
        }

        return sb.append('\n').toString();
    }
}
