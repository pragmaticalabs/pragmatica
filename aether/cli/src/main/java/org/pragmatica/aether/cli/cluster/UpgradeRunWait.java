// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import tools.jackson.databind.JsonNode;


/// #1543 F — waits for the rolling-upgrade run to end. Polls `GET /api/v1/upgrade/status` until the run is COMPLETED or ABORTED, or is
/// PAUSED (it needs an operator: waiting longer would only hide that), or the bound passes. A poll that fails (a leader change, a node
/// being replaced under the endpoint) is not an outcome: the next poll tries again until the bound.
sealed interface UpgradeRunWait {
    JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    record Completed(String targetVersion) implements UpgradeRunWait {}

    record Aborted(String reason) implements UpgradeRunWait {}

    record Paused(String reason) implements UpgradeRunWait {}

    record NoRun() implements UpgradeRunWait {}

    record TimedOut(String lastSeen) implements UpgradeRunWait {}

    static UpgradeRunWait await(Supplier<Result<String>> poll,
                                LongSupplier clockMs,
                                LongConsumer sleepMs,
                                long boundMs,
                                long pollMs,
                                Consumer<String> progress) {
        var deadline = clockMs.getAsLong() + boundMs;
        var lastSeen = "no answer yet";
        var lastLine = "";

        while (true) {
            var status = poll.get().flatMap(MAPPER::readTree);

            if (status.isSuccess()) {
                var node = status.unwrap();

                lastSeen = describe(node);
                if (!lastSeen.equals(lastLine)) {
                    progress.accept(lastSeen);
                    lastLine = lastSeen;
                }

                var decided = decide(node);

                if (decided.isPresent()) {
                    return decided.unwrap();
                }
            }

            if (clockMs.getAsLong() >= deadline) {
                return new TimedOut(lastSeen);
            }

            sleepMs.accept(pollMs);
        }
    }

    private static Option<UpgradeRunWait> decide(JsonNode node) {
        if (!node.path("present").asBoolean(false)) {
            return Option.some(new NoRun());
        }

        return switch (node.path("state")
                           .asText("")) {
            case "COMPLETED" -> Option.some(new Completed(node.path("targetVersion").asText("")));
            case "ABORTED" -> Option.some(new Aborted(node.path("reason").asText("")));
            case "PAUSED" -> Option.some(new Paused(node.path("reason").asText("")));
            default -> Option.none();
        };
    }

    static String describe(JsonNode node) {
        if (!node.path("present").asBoolean(false)) {
            return "no upgrade run";
        }

        var inFlight = node.path("inFlight").asText("");

        return "upgrade to " + node.path("targetVersion")
                                   .asText("?")
             + " " + node.path("state")
                         .asText("?")
             + ": " + node.path("index")
                          .asInt(0)
             + " of " + node.path("total")
                            .asInt(0)
             + " nodes replaced" + (inFlight.isEmpty()
                                    ? ""
                                    : ", replacing " + inFlight) + (node.path("stop")
                                                                        .asText("NONE")
                                                                        .equals("NONE")
                                                                    ? ""
                                                                    : " (" + node.path("stop")
                                                                                 .asText() + " requested)");
    }
}
