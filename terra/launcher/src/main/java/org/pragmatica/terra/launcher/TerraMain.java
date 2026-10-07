// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.terra.launcher;

import java.nio.file.Path;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.terra.http.TerraHttpServer;


/// Process boundary: startup diagnostics, signal handling, and blocking waits live here.
public final class TerraMain {
    private TerraMain() {}

    @Contract
    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2 || (args.length == 2 && !args[1].equals("--check"))) {
            System.err.println("Usage: TerraMain <application-directory> [--check]");
            System.exit(2);

            return;
        }

        TerraLaunchPlan.load(Path.of(args[0]))
                       .await()
                       .fold(cause -> {
                                 System.err.println("Terra configuration failed: " + cause.message());
                                 System.exit(1);

                                 return Unit.unit();
                             },
                             plan -> run(plan, args.length == 2));
    }

    private static Unit run(TerraLaunchPlan plan, boolean checkOnly) {
        if (checkOnly) {
            System.out.println("Terra check passed: " + plan.artifacts().size() + " selected slices");

            return Unit.unit();
        }

        var started = plan.start();

        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform()
                                                   .name("terra-shutdown")
                                                   .unstarted(() -> shutdown(started)));
        started.await()
               .fold(cause -> {
                         System.err.println("Terra startup failed: " + cause.message());
                         System.exit(1);

                         return Unit.unit();
                     },
                     server -> {
                         System.out.println("Terra ready on port " + server.port()
                                           + ": " + plan.artifacts()
                                                        .size()
                                           + " slices");

                         return Unit.unit();
                     });

        return Unit.unit();
    }

    @Contract
    private static void shutdown(Promise<TerraHttpServer> started) {
        started.fold(result -> result.fold(_ -> Promise.unitPromise(),
                                           TerraHttpServer::close))
               .await()
               .onFailure(cause -> System.err.println("Terra shutdown failed: " + cause.message()));
    }
}
