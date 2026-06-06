// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.resource.ResourceAddress;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;


/// Tests for the polling-based tail loop introduced in Wave 6B.
///
/// Spec event-stream-namespaces §16: SSE/WebSocket subscription is deferred to issue #212; RC1
/// ships polling-based tail. The loop drains pages from `/events?fromOffset=N&maxEvents=K`,
/// emits one event-payload line per record, advances `fromOffset` from `nextOffset`, and either
/// exits (`--no-follow`) or sleeps and re-polls.
///
/// All tests use a stub HTTP fetcher and a deterministic [TestSleeper] that immediately returns
/// `false` after a configured number of calls, so the loop terminates without real waits.
class StreamTailPollerTest {

    private ResourceAddress addr;
    private ByteArrayOutputStream outBuf;
    private ByteArrayOutputStream errBuf;
    private PrintStream out;
    private PrintStream err;

    @BeforeEach
    void setUp() {
        addr = ResourceAddress.resourceAddress("com.example.app", "orders", "1.0.0").unwrap();
        outBuf = new ByteArrayOutputStream();
        errBuf = new ByteArrayOutputStream();
        out = new PrintStream(outBuf, true, StandardCharsets.UTF_8);
        err = new PrintStream(errBuf, true, StandardCharsets.UTF_8);
    }

    @Nested
    class OneShot {

        @Test
        void noFollow_singleEmptyPage_returnsSuccess() {
            var fetcher = scriptedFetcher(List.of(emptyPage(0)));
            var options = new StreamTailPoller.PollerOptions(0, 100, false, 100);
            var exit = StreamTailPoller.runTailLoop(addr, options, fetcher, out, err, alwaysAllowSleep());
            assertThat(exit).isEqualTo(StreamExitCode.SUCCESS);
            assertThat(outBuf.toString(StandardCharsets.UTF_8)).isEmpty();
        }

        @Test
        void noFollow_pageWithEvents_printsEachEventOnOwnLine() {
            var fetcher = scriptedFetcher(List.of(pageWithEvents(0, 2, false)));
            var options = new StreamTailPoller.PollerOptions(0, 100, false, 100);
            var exit = StreamTailPoller.runTailLoop(addr, options, fetcher, out, err, alwaysAllowSleep());
            assertThat(exit).isEqualTo(StreamExitCode.SUCCESS);
            var lines = outBuf.toString(StandardCharsets.UTF_8).strip().split("\n");
            assertThat(lines).hasSize(2);
        }

        @Test
        void noFollow_pageWithHasMore_drainsAllPages() {
            var fetcher = scriptedFetcher(List.of(pageWithEvents(0, 2, true),
                                                  pageWithEvents(2, 1, false)));
            var options = new StreamTailPoller.PollerOptions(0, 100, false, 100);
            var exit = StreamTailPoller.runTailLoop(addr, options, fetcher, out, err, alwaysAllowSleep());
            assertThat(exit).isEqualTo(StreamExitCode.SUCCESS);
            var lines = outBuf.toString(StandardCharsets.UTF_8).strip().split("\n");
            assertThat(lines).hasSize(3);
        }
    }

    @Nested
    class FollowMode {

        @Test
        void follow_terminatesOnInterruptedSleep() {
            var fetcher = scriptedFetcher(List.of(emptyPage(0)));
            var options = new StreamTailPoller.PollerOptions(0, 100, true, 100);
            var exit = StreamTailPoller.runTailLoop(addr,
                                                    options,
                                                    fetcher,
                                                    out,
                                                    err,
                                                    interruptingSleeperAfter(0));
            assertThat(exit).isEqualTo(StreamExitCode.SUCCESS);
        }

        @Test
        void follow_drainsThenWaits_emitsEventsBeforeStopping() {
            var fetcher = scriptedFetcher(List.of(pageWithEvents(0, 2, false), emptyPage(2)));
            var options = new StreamTailPoller.PollerOptions(0, 100, true, 50);
            var exit = StreamTailPoller.runTailLoop(addr,
                                                    options,
                                                    fetcher,
                                                    out,
                                                    err,
                                                    interruptingSleeperAfter(1));
            assertThat(exit).isEqualTo(StreamExitCode.SUCCESS);
            var lines = outBuf.toString(StandardCharsets.UTF_8).strip().split("\n");
            assertThat(lines).hasSize(2);
        }
    }

    @Nested
    class HttpErrors {

        @Test
        void notFound_returnsExitCode4() {
            var fetcher = scriptedFetcher(List.of("{\"error\":\"HTTP 404: Stream not found\"}"));
            var options = new StreamTailPoller.PollerOptions(0, 100, true, 100);
            var exit = StreamTailPoller.runTailLoop(addr, options, fetcher, out, err, alwaysAllowSleep());
            assertThat(exit).isEqualTo(StreamExitCode.NOT_FOUND);
            assertThat(errBuf.toString(StandardCharsets.UTF_8)).contains("stream not found");
        }

        @Test
        void gone_returnsExitCode6() {
            var fetcher = scriptedFetcher(List.of("{\"error\":\"HTTP 410: Stream was deleted\"}"));
            var options = new StreamTailPoller.PollerOptions(0, 100, true, 100);
            var exit = StreamTailPoller.runTailLoop(addr, options, fetcher, out, err, alwaysAllowSleep());
            assertThat(exit).isEqualTo(StreamExitCode.GONE);
            assertThat(errBuf.toString(StandardCharsets.UTF_8)).contains("re-registered");
        }

        @Test
        void serverError_retriesUpToMaxThenFails() {
            var fetcher = scriptedFetcher(List.of(
                    "{\"error\":\"HTTP 500: Internal\"}",
                    "{\"error\":\"HTTP 500: Internal\"}",
                    "{\"error\":\"HTTP 500: Internal\"}",
                    "{\"error\":\"HTTP 500: Internal\"}"));
            var options = new StreamTailPoller.PollerOptions(0, 100, true, 10);
            var exit = StreamTailPoller.runTailLoop(addr, options, fetcher, out, err, alwaysAllowSleep());
            assertThat(exit).isEqualTo(StreamExitCode.ERROR);
            assertThat(errBuf.toString(StandardCharsets.UTF_8)).contains("after " + StreamTailPoller.MAX_5XX_RETRIES + " retries");
        }

        @Test
        void serverError_thenSuccess_resetsRetryCounter() {
            var fetcher = scriptedFetcher(List.of(
                    "{\"error\":\"HTTP 500: Internal\"}",
                    pageWithEvents(0, 1, false)));
            var options = new StreamTailPoller.PollerOptions(0, 100, false, 10);
            var exit = StreamTailPoller.runTailLoop(addr, options, fetcher, out, err, alwaysAllowSleep());
            assertThat(exit).isEqualTo(StreamExitCode.SUCCESS);
        }
    }

    @Nested
    class OffsetAdvancement {

        @Test
        void offsetAdvances_acrossSuccessivePages() {
            var captured = new ArrayList<String>();
            BiFunction<ResourceAddress, String, String> fetcher = (_, query) -> {
                captured.add(query);
                if (captured.size() == 1) {return pageWithEvents(0, 2, true);}
                return pageWithEvents(2, 1, false);
            };
            var options = new StreamTailPoller.PollerOptions(0, 50, false, 10);
            StreamTailPoller.runTailLoop(addr, options, fetcher, out, err, alwaysAllowSleep());
            assertThat(captured).hasSize(2);
            assertThat(captured.get(0)).contains("fromOffset=0").contains("maxEvents=50");
            assertThat(captured.get(1)).contains("fromOffset=2").contains("maxEvents=50");
        }
    }

    // ====================== Helpers ======================

    /// Build a fetcher that returns successive responses from the given list. Once exhausted, the
    /// last entry is returned indefinitely (useful for tests that rely on the sleeper to terminate).
    private static BiFunction<ResourceAddress, String, String> scriptedFetcher(List<String> responses) {
        var idx = new AtomicInteger(0);
        return (_, _) -> {
            var i = Math.min(idx.getAndIncrement(), responses.size() - 1);
            return responses.get(i);
        };
    }

    private static StreamTailPoller.Sleeper alwaysAllowSleep() {
        return _ -> true;
    }

    /// Sleeper that returns `true` for the first `allowedSleeps` calls, then `false` to simulate
    /// Ctrl-C. Lets tests drain N follow-iterations and then bail out cleanly.
    private static StreamTailPoller.Sleeper interruptingSleeperAfter(int allowedSleeps) {
        var counter = new AtomicInteger(0);
        return _ -> counter.getAndIncrement() < allowedSleeps;
    }

    private static String emptyPage(long nextOffset) {
        return "{\"address\":\"com.example.app:orders:1.0.0\",\"events\":[],\"nextOffset\":"
                + nextOffset + ",\"hasMore\":false}";
    }

    private static String pageWithEvents(long startOffset, int count, boolean hasMore) {
        var events = new StringBuilder("[");
        for (int i = 0;i < count; i++) {
            if (i > 0) {events.append(",");}
            events.append("{\"offset\":").append(startOffset + i)
                  .append(",\"timestamp\":\"2026-05-03T00:00:00Z\",\"partition\":0,\"payload\":\"event-")
                  .append(startOffset + i).append("\"}");
        }
        events.append("]");
        return "{\"address\":\"com.example.app:orders:1.0.0\",\"events\":" + events
                + ",\"nextOffset\":" + (startOffset + count)
                + ",\"hasMore\":" + hasMore + "}";
    }
}
