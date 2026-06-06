// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.stream;

import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.json.JsonMapper;

import java.io.PrintStream;
import java.util.List;
import java.util.function.BiFunction;

import tools.jackson.databind.JsonNode;


/// Polling-based tail loop for `aether stream tail`.
///
/// Spec event-stream-namespaces §16: SSE/WebSocket subscription on `/tail` is deferred to issue
/// #212; RC1 ships polling-based tail via paginated GETs to `/events?fromOffset=N&maxEvents=K`.
///
/// The loop is structured as a sealed utility with a single public entrypoint (`runTailLoop`) so
/// the interactive Ctrl-C path stays in [StreamCommand] while the per-poll arithmetic is unit
/// testable in isolation.
public sealed interface StreamTailPoller {
    JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    /// Run a polling loop against `/events`. Each iteration fetches a page, prints raw payloads
    /// one per line, advances the offset, and either exits (`--no-follow`) or sleeps before the
    /// next poll. HTTP errors short-circuit to spec exit codes (404→4, 410→6, 5xx→1 after retries).
    ///
    /// `httpFetch` is parameterized so unit tests can drive the loop with a stub response sequence
    /// without spinning up a server. The fetcher receives `(addr, query)` and returns the raw JSON
    /// body the server would have sent.
    static int runTailLoop(ResourceAddress addr,
                           PollerOptions options,
                           BiFunction<ResourceAddress, String, String> httpFetch,
                           PrintStream out,
                           PrintStream err,
                           Sleeper sleeper) {
        var state = new PollState(options.fromOffset(), 0);
        while (true) {
            var query = buildQuery(state.offset(), options.maxEvents());
            var response = httpFetch.apply(addr, query);
            var outcome = pollOnce(response, state, out, err);
            if (outcome.exitCode().isPresent()) {return outcome.exitCode().getAsInt();}
            state = outcome.nextState();
            if (!options.follow() && !outcome.hasMore()) {return StreamExitCode.SUCCESS;}
            if (!outcome.hasMore() && options.follow()) {
                if (!sleeper.sleep(options.intervalMs())) {return StreamExitCode.SUCCESS;}
            }
        }
    }

    /// Single-iteration step extracted for testability. Returns the next state plus an optional
    /// exit code (set when the loop must terminate immediately, e.g. on 404 or repeated 5xx).
    static PollOutcome pollOnce(String response, PollState state, PrintStream out, PrintStream err) {
        if (OutputFormatter.isErrorResponse(response)) {return handleErrorResponse(response, state, err);}
        return MAPPER.readTree(response)
                     .fold(_ -> handleParseFailure(response, state, err),
                           node -> handleSuccess(node, state, out));
    }

    private static PollOutcome handleErrorResponse(String response, PollState state, PrintStream err) {
        var status = StreamHttpResponse.extractHttpStatus(response);
        return status.map(code -> handleHttpStatus(code, response, state, err))
                     .or(() -> retryOrFail(response, state, err));
    }

    private static PollOutcome handleHttpStatus(int code, String response, PollState state, PrintStream err) {
        if (code == 404) {
            err.println("Error: stream not found");
            return PollOutcome.exit(StreamExitCode.NOT_FOUND);
        }
        if (code == 410) {
            err.println("Error: stream was deleted; address can be re-registered");
            return PollOutcome.exit(StreamExitCode.GONE);
        }
        if (code >= 500) {return retryOrFail(response, state, err);}
        err.println("Error: " + OutputFormatter.extractErrorMessage(response));
        return PollOutcome.exit(StreamExitCode.ERROR);
    }

    private static PollOutcome retryOrFail(String response, PollState state, PrintStream err) {
        var nextRetry = state.consecutive5xx() + 1;
        if (nextRetry > MAX_5XX_RETRIES) {
            err.println("Error: server failure after " + MAX_5XX_RETRIES + " retries: "
                                + OutputFormatter.extractErrorMessage(response));
            return PollOutcome.exit(StreamExitCode.ERROR);
        }
        err.println("Warning: server failure (retry " + nextRetry + "/" + MAX_5XX_RETRIES + "): "
                            + OutputFormatter.extractErrorMessage(response));
        return PollOutcome.continueRetry(state.withRetry(nextRetry));
    }

    private static PollOutcome handleParseFailure(String response, PollState state, PrintStream err) {
        err.println("Error: malformed server response: " + truncate(response, 200));
        return PollOutcome.exit(StreamExitCode.ERROR);
    }

    private static PollOutcome handleSuccess(JsonNode node, PollState state, PrintStream out) {
        var events = resolveEventsArray(node);
        events.forEach(eventNode -> out.println(formatEventLine(eventNode)));
        var nextOffset = node.has("nextOffset")
                ? node.get("nextOffset").asLong(state.offset())
                : state.offset();
        var hasMore = node.has("hasMore") && node.get("hasMore").asBoolean(false);
        return PollOutcome.advance(new PollState(nextOffset, 0), hasMore);
    }

    private static List<JsonNode> resolveEventsArray(JsonNode root) {
        if (!root.has("events") || !root.get("events").isArray()) {return List.of();}
        var arr = root.get("events");
        var list = new java.util.ArrayList<JsonNode>(arr.size());
        arr.forEach(list::add);
        return list;
    }

    /// Print one JSON object per line in compact form. Operators that want jq-style processing
    /// can pipe stdout; humans reading the raw output get a stable per-event terminator.
    private static String formatEventLine(JsonNode eventNode) {
        return eventNode.toString();
    }

    private static String buildQuery(long fromOffset, int maxEvents) {
        return "fromOffset=" + fromOffset + "&maxEvents=" + maxEvents;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "...";
    }

    int MAX_5XX_RETRIES = 3;

    /// Per-iteration polling state — the offset to fetch from on the next call, and how many
    /// consecutive 5xx responses we've seen (resets to 0 on any 2xx).
    record PollState(long offset, int consecutive5xx) {
        PollState withRetry(int retry) {return new PollState(offset, retry);}
    }

    /// Loop options bundled into a record so the picocli command can package them once and the
    /// loop signature stays narrow.
    record PollerOptions(long fromOffset, int maxEvents, boolean follow, long intervalMs) {}

    /// Outcome of a single poll iteration:
    ///  - `exit` — terminate the loop with the given exit code (success or error).
    ///  - `advance` — continue with the next state (after a successful page).
    ///  - `continueRetry` — continue with the next state (after a retryable 5xx).
    ///
    /// `hasMore=true` triggers immediate next-poll without sleeping.
    record PollOutcome(java.util.OptionalInt exitCode, PollState nextState, boolean hasMore) {
        static PollOutcome exit(int code) {return new PollOutcome(java.util.OptionalInt.of(code),
                                                                  new PollState(0, 0), false);}
        static PollOutcome advance(PollState next, boolean hasMore) {return new PollOutcome(java.util.OptionalInt.empty(),
                                                                                            next, hasMore);}
        static PollOutcome continueRetry(PollState next) {return new PollOutcome(java.util.OptionalInt.empty(),
                                                                                 next, false);}
    }

    /// Sleep abstraction so tests can drive the loop deterministically without real waits.
    /// Returns `false` if sleep was interrupted (Ctrl-C), `true` to continue.
    @FunctionalInterface
    interface Sleeper {
        boolean sleep(long millis);

        Sleeper REAL = millis -> {
            try {Thread.sleep(millis);return true;}
            catch (InterruptedException _) {Thread.currentThread().interrupt();return false;}
        };
    }

    static String routeForEvents() {return ManagementRoute.STREAMS_EVENTS.name();}

    record unused() implements StreamTailPoller {}
}
