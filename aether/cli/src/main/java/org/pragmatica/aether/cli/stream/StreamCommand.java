// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.stream;

import org.pragmatica.aether.cli.AetherCli;
import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.aether.cli.Prompt;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.lang.Contract;

import java.util.List;
import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;


/// `aether stream` command group — operator surface for stream namespaces.
///
/// Spec event-stream-namespaces §8.6: six commands wrap the HTTP route surface added in Wave 4A
/// (`StreamApiRoutes`). Address parsing delegates to the canonical `ResourceAddress.resourceAddress`
/// from slice-api so error messages stay aligned with the rest of the platform.
///
/// Exit codes follow [StreamExitCode]: 0=success, 1=error, 2=validation, 3=user-cancelled,
/// 4=not-found, 5=conflict, 6=gone. HTTP errors are scraped from the JSON envelope returned by
/// the base CLI HTTP helpers (see [StreamHttpResponse]).
@Command(name = "stream",
        description = "Manage event streams (list, show, tail, delete, group create/delete)",
        subcommands = {
                StreamCommand.ListCommand.class,
                StreamCommand.ShowCommand.class,
                StreamCommand.TailCommand.class,
                StreamCommand.DeleteCommand.class,
                StreamCommand.GroupCommand.class
        })
public class StreamCommand implements Runnable {
    @CommandLine.ParentCommand private AetherCli parent;

    @Contract @Override public void run() {
        CommandLine.usage(this, System.out);
    }

    public AetherCli parent() {
        return parent;
    }

    @Command(name = "list", description = "List all streams (optionally filtered by namespace)")
    public static class ListCommand implements Callable<Integer> {
        @CommandLine.ParentCommand private StreamCommand streamParent;

        @CommandLine.Option(names = "--namespace", description = "Filter to a single namespace") private String namespace;

        @Override public Integer call() {
            var query = namespace == null || namespace.isBlank() ? "" : "namespace=" + namespace;
            var response = streamParent.parent().fetch(ManagementRoute.STREAMS_LIST, List.of(), query);
            return renderQueryOrError(response, streamParent.parent(), "Failed to list streams");
        }
    }

    @Command(name = "show", description = "Show stream metadata for a specific version")
    public static class ShowCommand implements Callable<Integer> {
        @CommandLine.ParentCommand private StreamCommand streamParent;

        @Parameters(index = "0", description = "Stream address: namespace:stream:version") private String address;

        @Override public Integer call() {
            return StreamAddressArg.parse(address)
                    .fold(StreamCommand::handleAddressError, addr -> fetchMetadata(addr, streamParent.parent()));
        }
    }

    @Command(name = "tail",
            description = "Tail events from a stream version via polling /events (press Ctrl-C to stop)")
    public static class TailCommand implements Callable<Integer> {
        @CommandLine.ParentCommand private StreamCommand streamParent;

        @Parameters(index = "0", description = "Stream address: namespace:stream:version") private String address;

        @CommandLine.Option(names = "--interval",
                description = "Polling interval in milliseconds (default: 500)",
                defaultValue = "500") private long intervalMs;

        @CommandLine.Option(names = "--from-offset",
                description = "Initial offset to read from (default: 0 — start from beginning)",
                defaultValue = "0") private long fromOffset;

        @CommandLine.Option(names = "--max-events",
                description = "Maximum events per poll page (default: 100, server-capped at 1000)",
                defaultValue = "100") private int maxEvents;

        @CommandLine.Option(names = "--follow",
                description = "Keep polling for new events (default: true). Use --no-follow for one-shot.",
                negatable = true,
                defaultValue = "true") private boolean follow;

        @Override public Integer call() {
            return StreamAddressArg.parse(address)
                    .fold(StreamCommand::handleAddressError,
                          addr -> tailStream(addr,
                                             streamParent.parent(),
                                             new StreamTailPoller.PollerOptions(fromOffset,
                                                                                maxEvents,
                                                                                follow,
                                                                                intervalMs)));
        }
    }

    @Command(name = "delete", description = "Force-purge a specific stream version")
    public static class DeleteCommand implements Callable<Integer> {
        @CommandLine.ParentCommand private StreamCommand streamParent;

        @Parameters(index = "0", description = "Stream address: namespace:stream:version") private String address;

        @CommandLine.Option(names = {"--force", "-f"}, description = "Skip confirmation prompt") private boolean force;

        @Override public Integer call() {
            return StreamAddressArg.parse(address)
                    .fold(StreamCommand::handleAddressError,
                          addr -> deleteStreamWithConfirmation(addr, force, streamParent.parent()));
        }
    }

    @Command(name = "group", description = "Manage durable consumer groups",
            subcommands = {GroupCommand.CreateCommand.class, GroupCommand.DeleteCommand.class})
    public static class GroupCommand implements Runnable {
        @CommandLine.ParentCommand private StreamCommand streamParent;

        @Contract @Override public void run() {
            CommandLine.usage(this, System.out);
        }

        public StreamCommand streamParent() {
            return streamParent;
        }

        @Command(name = "create", description = "Create a durable consumer group on a stream version")
        public static class CreateCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private GroupCommand groupParent;

            @Parameters(index = "0", description = "Stream address: namespace:stream:version") private String address;

            @Parameters(index = "1", description = "Consumer group ID") private String group;

            @CommandLine.Option(names = "--initial-position",
                    description = "Initial position: 'earliest' or 'latest' (default: latest)",
                    defaultValue = "latest") private String initialPosition;

            @Override public Integer call() {
                return StreamAddressArg.parse(address)
                        .fold(StreamCommand::handleAddressError,
                              addr -> createGroup(addr, group, initialPosition, groupParent.streamParent().parent()));
            }
        }

        @Command(name = "delete", description = "Delete a durable consumer group; releases its reference")
        public static class DeleteCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private GroupCommand groupParent;

            @Parameters(index = "0", description = "Stream address: namespace:stream:version") private String address;

            @Parameters(index = "1", description = "Consumer group ID") private String group;

            @CommandLine.Option(names = {"--force", "-f"}, description = "Skip confirmation prompt") private boolean force;

            @Override public Integer call() {
                return StreamAddressArg.parse(address)
                        .fold(StreamCommand::handleAddressError,
                              addr -> deleteGroupWithConfirmation(addr, group, force,
                                                                  groupParent.streamParent().parent()));
            }
        }
    }

    // ====================== Helpers (Leaves) ======================

    private static int handleAddressError(org.pragmatica.lang.Cause cause) {
        System.err.println("Error: invalid stream address: " + cause.message());
        return StreamExitCode.VALIDATION;
    }

    private static int fetchMetadata(ResourceAddress addr, AetherCli cli) {
        var response = cli.fetch(ManagementRoute.STREAMS_METADATA,
                                 List.of(addr.namespace(), addr.name(), addr.version().asString()));
        return renderQueryOrError(response, cli, "Failed to load stream metadata");
    }

    /// Polling-based tail (Wave 6B). SSE/WebSocket on `/tail` is deferred to issue #212; this
    /// helper drives the polling loop against `/events`, printing each event payload on its own
    /// stdout line. The HTTP `fetch` callback delegates to [AetherCli#fetch] with the assembled
    /// route + query string so the wire-level concerns stay inside the existing CLI HTTP layer.
    private static int tailStream(ResourceAddress addr, AetherCli cli, StreamTailPoller.PollerOptions options) {
        return StreamTailPoller.runTailLoop(addr,
                                            options,
                                            (a, query) -> cli.fetch(ManagementRoute.STREAMS_EVENTS,
                                                                    List.of(a.namespace(),
                                                                            a.name(),
                                                                            a.version().asString()),
                                                                    query),
                                            System.out,
                                            System.err,
                                            StreamTailPoller.Sleeper.REAL);
    }

    private static int deleteStreamWithConfirmation(ResourceAddress addr, boolean force, AetherCli cli) {
        if (!force && !confirm("Delete stream version '" + addr.asString() + "'?")) {
            return StreamExitCode.USER_CANCELLED;
        }
        var response = cli.delete(ManagementRoute.STREAMS_DELETE,
                                  List.of(addr.namespace(), addr.name(), addr.version().asString()));
        var errorCode = OutputFormatter.checkResponseError(response, cli.outputOptions(), "Failed to delete stream");
        if (errorCode >= 0) {
            return mapHttpErrorOrFallback(response, errorCode);
        }
        return OutputFormatter.printAction(response, cli.outputOptions(), "Deleted stream: " + addr.asString());
    }

    private static int createGroup(ResourceAddress addr, String groupId, String initialPosition, AetherCli cli) {
        var body = "{\"groupId\":\"" + escapeJson(groupId) + "\",\"initialPosition\":\"" + escapeJson(initialPosition)
                + "\"}";
        var response = cli.post(ManagementRoute.STREAMS_GROUP_CREATE,
                                List.of(addr.namespace(), addr.name(), addr.version().asString()),
                                body);
        var errorCode = OutputFormatter.checkResponseError(response, cli.outputOptions(), "Failed to create group");
        if (errorCode >= 0) {
            return mapHttpErrorOrFallback(response, errorCode);
        }
        return OutputFormatter.printAction(response, cli.outputOptions(),
                                           "Created group '" + groupId + "' on " + addr.asString());
    }

    private static int deleteGroupWithConfirmation(ResourceAddress addr, String groupId, boolean force, AetherCli cli) {
        if (!force && !confirm("Delete consumer group '" + groupId + "' on '" + addr.asString() + "'?")) {
            return StreamExitCode.USER_CANCELLED;
        }
        var response = cli.delete(ManagementRoute.STREAMS_GROUP_DELETE,
                                  List.of(addr.namespace(), addr.name(), addr.version().asString(), groupId));
        var errorCode = OutputFormatter.checkResponseError(response, cli.outputOptions(), "Failed to delete group");
        if (errorCode >= 0) {
            return mapHttpErrorOrFallback(response, errorCode);
        }
        return OutputFormatter.printAction(response, cli.outputOptions(),
                                           "Deleted group '" + groupId + "' on " + addr.asString());
    }

    private static int renderQueryOrError(String response, AetherCli cli, String errorLabel) {
        var errorCode = OutputFormatter.checkResponseError(response, cli.outputOptions(), errorLabel);
        if (errorCode >= 0) {
            return mapHttpErrorOrFallback(response, errorCode);
        }
        return OutputFormatter.printQuery(response, cli.outputOptions());
    }

    /// HTTP-status remap: when the OutputFormatter generic handler returns a non-zero exit, scrape
    /// the wire-level status from the JSON envelope and remap to spec §8.6 codes (4/5/6). When the
    /// envelope doesn't carry a status (network failure, malformed body), keep the generic exit.
    private static int mapHttpErrorOrFallback(String response, int genericExit) {
        var mapped = StreamHttpResponse.mapErrorExitCode(response);
        // Prefer HTTP-mapped exit when it's specifically NOT_FOUND/CONFLICT/GONE; otherwise fall back
        // to the generic exit so non-HTTP failures stay at the OutputFormatter-level code.
        return switch (mapped) {
            case StreamExitCode.NOT_FOUND, StreamExitCode.CONFLICT, StreamExitCode.GONE -> mapped;
            default -> genericExit == 0 ? StreamExitCode.ERROR : genericExit;
        };
    }

    private static boolean confirm(String question) {
        return new Prompt().confirm(question, false);
    }

    /// Minimal JSON string escape — handles the two chars that can break the body envelope.
    /// Group IDs are spec-constrained to a tame charset; this is belt-and-suspenders for operator
    /// pastes that include a stray backslash or quote.
    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
