// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.ManagementApiResponses.AwaitQuiescedResponse;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.generation.ClusterQuiescence;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.http.routing.HttpError;
import org.pragmatica.http.routing.HttpStatus;
import org.pragmatica.http.routing.QueryParameter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.utils.Causes;

import java.util.function.Supplier;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public final class ClusterAwaitQuiescedRoute implements RouteSource {
    private static final Logger log = LoggerFactory.getLogger(ClusterAwaitQuiescedRoute.class);
    private static final TimeSpan POLL_INTERVAL = TimeSpan.timeSpan(200).millis();
    private static final int LOG_EVERY_N_POLLS = 25;
    private static final TimeSpan DEFAULT_TIMEOUT = TimeSpan.timeSpan(30).seconds();
    private static final TimeSpan MAX_TIMEOUT = TimeSpan.timeSpan(120).seconds();

    private static final Fn1<Cause, String> INVALID_EPOCH = Causes.forOneValue("Invalid epoch parameter [%s] (expected term:counter)");

    private static final Fn1<Cause, String> INVALID_TIMEOUT = Causes.forOneValue("Invalid timeout parameter [%s]");

    private static final Cause MISSING_EPOCH = Causes.cause("Missing required query parameter: epoch");

    private final Supplier<ManageableNode> nodeSupplier;

    private ClusterAwaitQuiescedRoute(Supplier<ManageableNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static ClusterAwaitQuiescedRoute clusterAwaitQuiescedRoute(Supplier<ManageableNode> nodeSupplier) {
        return new ClusterAwaitQuiescedRoute(nodeSupplier);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<AwaitQuiescedResponse> route(ManagementRoute.CLUSTER_AWAIT_QUIESCED)
                                         .<String, String> withQuery(QueryParameter.aString("epoch"),
                                                                     QueryParameter.aString("timeout"))
                                         .to(this::handle)
                                         .asJson());
    }

    private Promise<AwaitQuiescedResponse> handle(Option<String> epochParam, Option<String> timeoutParam) {
        return parseEpoch(epochParam).async()
                         .flatMap(epoch -> parseTimeout(timeoutParam).async()
                                                       .flatMap(timeout -> awaitQuiesced(epoch, timeout)));
    }

    static Result<Epoch> parseEpoch(Option<String> raw) {
        return raw.toResult(MISSING_EPOCH)
                  .flatMap(ClusterAwaitQuiescedRoute::parseEpochString);
    }

    private static Result<Epoch> parseEpochString(String raw) {
        var parts = raw.split(":");

        if (parts.length != 2) {return INVALID_EPOCH.apply(raw).result();}

        return parseLongPair(parts[0], parts[1]).mapError(_ -> INVALID_EPOCH.apply(raw));
    }

    private static Result<Epoch> parseLongPair(String termRaw, String counterRaw) {
        return Number.parseLong(termRaw).flatMap(term -> Number.parseLong(counterRaw).map(counter -> Epoch.epoch(term,
                                                                                                                 counter)));
    }

    static Result<TimeSpan> parseTimeout(Option<String> raw) {
        return raw.map(ClusterAwaitQuiescedRoute::parseTimeoutString)
                  .or(() -> Result.success(DEFAULT_TIMEOUT));
    }

    private static Result<TimeSpan> parseTimeoutString(String raw) {
        return parseTimeoutSeconds(raw).map(seconds -> TimeSpan.timeSpan(seconds).seconds())
                                  .map(ClusterAwaitQuiescedRoute::clampTimeout);
    }

    private static Result<Long> parseTimeoutSeconds(String raw) {
        var trimmed = raw.endsWith("s")
                      ? raw.substring(0, raw.length() - 1)
                      : raw;
        return Number.parseLong(trimmed).mapError(_ -> INVALID_TIMEOUT.apply(raw));
    }

    private static TimeSpan clampTimeout(TimeSpan ts) {
        return ts.millis() > MAX_TIMEOUT.millis()
               ? MAX_TIMEOUT
               : ts;
    }

    private Promise<AwaitQuiescedResponse> awaitQuiesced(Epoch requested, TimeSpan timeout) {
        var startNanos = System.nanoTime();
        var deadlineNanos = startNanos + timeout.nanos();
        log.debug("await-quiesced: requested={}, timeout={}ms", requested, timeout.millis());

        return pollUntilReadyOrTimeout(requested, startNanos, deadlineNanos, 0);
    }

    private Promise<AwaitQuiescedResponse> pollUntilReadyOrTimeout(Epoch requested,
                                                                   long startNanos,
                                                                   long deadlineNanos,
                                                                   int pollCount) {
        var node = nodeSupplier.get();
        var observed = node.currentGenerationEpoch();
        var quiescence = ClusterGenerationAssembler.clusterQuiescence(node).quiescence();

        if (pollCount > 0 && pollCount % LOG_EVERY_N_POLLS == 0) {logProgress(requested, observed, quiescence, pollCount, startNanos);}

        return matchesQuiesced(observed, quiescence, requested)
               ? Promise.success(buildResponse(observed, quiescence, startNanos))
               : waitOrTimeout(requested, startNanos, deadlineNanos, pollCount);
    }

    private Promise<AwaitQuiescedResponse> waitOrTimeout(Epoch requested,
                                                         long startNanos,
                                                         long deadlineNanos,
                                                         int pollCount) {
        if (System.nanoTime() >= deadlineNanos) {
            logTimeout(requested, pollCount, startNanos);

            return timeoutResponse();
        }
        return Promise.<Boolean> promise(POLL_INTERVAL,
                                         p -> p.succeed(true))
                      .flatMap(_ -> pollUntilReadyOrTimeout(requested, startNanos, deadlineNanos, pollCount + 1));
    }

    @Contract
    private static void logProgress(Epoch requested,
                                    Epoch observed,
                                    ClusterQuiescence quiescence,
                                    int pollCount,
                                    long startNanos) {
        var elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        log.info("await-quiesced still waiting (poll={}, elapsed={}ms): requested={}, observed=epoch={} quiescence={}",
                 pollCount,
                 elapsedMs,
                 requested,
                 observed,
                 quiescence);
    }

    @Contract
    private void logTimeout(Epoch requested, int pollCount, long startNanos) {
        var elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        var node = nodeSupplier.get();
        log.warn("await-quiesced TIMEOUT (polls={}, elapsed={}ms): requested={}, final=epoch={} quiescence={}",
                 pollCount,
                 elapsedMs,
                 requested,
                 node.currentGenerationEpoch(),
                 ClusterGenerationAssembler.clusterQuiescence(node).quiescence());
    }

    private static boolean matchesQuiesced(Epoch observed, ClusterQuiescence quiescence, Epoch requested) {
        return observed.isAtLeast(requested) && quiescence == ClusterQuiescence.QUIESCED;
    }

    private static AwaitQuiescedResponse buildResponse(Epoch observed, ClusterQuiescence quiescence, long startNanos) {
        var waitedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        return new AwaitQuiescedResponse(observed.toString(),
                                         quiescence.name(),
                                         waitedMs);
    }

    private static Promise<AwaitQuiescedResponse> timeoutResponse() {
        return HttpError.httpError(HttpStatus.REQUEST_TIMEOUT,
                                   Causes.cause("await-quiesced timed out before reaching requested epoch"))
                        .promise();
    }
}
