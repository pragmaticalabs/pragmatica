// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// D1 exit (q'), the production half (KVStore dispatcher design 8.1, "Who installs what"): `Main` installs the
/// fatal-escape relay as the first statement of its run, with the very sink the node's store gets.
///
/// Two pins, because the call site is not reachable from a unit test (`Main.run` needs a whole boot configuration):
/// - the installer: an `Error` thrown from a `Promise` continuation reaches the sink it was given. Mutation: the
///   installer registers nothing (RED);
/// - the call site, read from the source as `NodeLauncherExitOnOomPinnedTest` reads launchers: `run()` installs
///   before its first other statement and hands the node that same sink. Mutation: delete the install call (RED).
class MainFatalEscapeInstallTest {
    private static final String MAIN_SOURCE = "aether/node/src/main/java/org/pragmatica/aether/Main.java";

    @Test
    @Timeout(value = 30, unit = SECONDS)
    void installFatalEscapeRelay_routesAContinuationErrorToTheSink() throws Exception {
        var stopped = new CountDownLatch(1);
        var seen = new AtomicReference<Throwable>();
        var error = new AssertionError("continuation");
        var registration = Main.installFatalEscapeRelay((reason, cause) -> record(seen, stopped, cause))
                               .onFailure(cause -> fail("install must succeed: " + cause.message()))
                               .unwrap();

        try {
            Promise.success(Unit.unit()).onSuccess(_ -> { throw error; });

            assertThat(stopped.await(10, TimeUnit.SECONDS)).as("the sink was asked to stop the node").isTrue();
            assertThat(seen.get()).isSameAs(error);
        } finally {
            registration.uninstall();
        }
    }

    private static Unit record(AtomicReference<Throwable> seen, CountDownLatch stopped, Throwable cause) {
        seen.set(cause);
        stopped.countDown();
        return Unit.unit();
    }

    @Test
    void run_installsTheRelayFirst_andSharesItsSinkWithTheNode() throws Exception {
        var source = Files.readString(repositoryRoot().resolve(MAIN_SOURCE));
        var run = source.indexOf("private void run() {");
        var install = source.indexOf("installFatalEscapeRelay(dispatcherSink)", run);
        var firstOther = source.indexOf("publishConfigPath();", run);

        assertThat(run).as("control: Main has a run()").isNotNegative();
        assertThat(install).as("run() installs the fatal-escape relay").isGreaterThan(run);
        assertThat(install).as("the install is before run()'s other work").isLessThan(firstOther);
        assertThat(source).as("the node gets the sink the relay got")
                          .contains("AetherNode.aetherNode(config, dispatcherSink)");
    }

    private static Path repositoryRoot() throws Exception {
        var current = Path.of(MainFatalEscapeInstallTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());

        while (current != null && !Files.isRegularFile(current.resolve(MAIN_SOURCE))) {
            current = current.getParent();
        }

        return current == null ? fail("repository root not found above the test's code source") : current;
    }
}
