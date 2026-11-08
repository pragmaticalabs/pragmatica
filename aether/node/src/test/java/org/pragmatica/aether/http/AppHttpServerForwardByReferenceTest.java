// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

/// U2 forward by reference ([ForwardByReferenceScenario]): an upload taken on node A for a route node B serves is stored
/// once and forwarded as a handle; B's slice reads the same bytes through it.
///
/// Spec §4 U2: forwarded request bytes < 64 KiB for a 1 GiB upload, and the upload streams with a heap smaller than the
/// body. The 1 GiB run is a child JVM with `-Xmx256m` and `-XX:MaxDirectMemorySize=256m` (both nodes, the client and
/// the slice share that heap), so an ingress that gathers the body, or a forward that carries it, cannot finish.
///
/// Mutations: forward the body (an ingress that aggregates despite `streaming_ingest`) fails the < 64 KiB bound and the
/// child's heap; dropping the handle from the forwarded context answers 500 "no upload handle" on B.
@Timeout(value = 10, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class AppHttpServerForwardByReferenceTest {
    private static final long MIB = 1024L * 1024;
    private static final long FORWARD_BOUND = 64 * 1024;
    private static final int CHILD_HEAP_MB = 256;

    @Test
    void upload_forwardedFromAToB_byReference_readsBackTheSentBytes_inProcess() throws Exception {
        var size = 48 * MIB + 7;
        var outcome = ForwardByReferenceScenario.run(size);

        assertThat(outcome.status()).as("%s", outcome).isEqualTo(200);
        assertThat(outcome.forwards()).as("forwarded once, to B").isEqualTo(1);
        assertThat(outcome.forwardedBytes()).as("the forward carries the handle, not the body").isLessThan(FORWARD_BOUND);
        assertThat(outcome.bodyOnB()).as("B's slice is handed an empty body").isZero();
        assertThat(outcome.handleSize()).isEqualTo(size);
        assertThat(outcome.servedSha()).as("B read the bytes the client sent").isEqualTo(outcome.sentSha());
    }

    /// The object is deleted from the content storage after A forwarded the handle and before B received it: B's slice is
    /// never handed a handle that names nothing, and the client gets the typed 503 (a forward failure: B refused with upload-unavailable, which A can only report as outcome unknown) inside the test's bound, not a hang.
    @Test
    void upload_deletedMidForward_failsTyped503_andBsSliceIsNeverReached() throws Exception {
        var outcome = ForwardByReferenceScenario.run(2 * MIB, ForwardByReferenceScenario.Fault.DELETE_MID_FORWARD);

        assertThat(outcome.status()).as("%s", outcome).isEqualTo(503);
        assertThat(outcome.text()).as("a typed forward failure (B refused it, so A cannot know it was not applied): %s", outcome)
                                  .contains("outcome unknown");
        assertThat(outcome.forwards()).as("forwarded once, to B").isEqualTo(1);
        assertThat(outcome.sliceCalls()).as("B's slice was not reached").isZero();
    }

    /// The node that held the object is gone: B's content storage holds nothing of A's upload. Same typed 503, no hang.
    @Test
    void upload_whoseHolderIsGone_failsTyped503_andBsSliceIsNeverReached() throws Exception {
        var outcome = ForwardByReferenceScenario.run(2 * MIB, ForwardByReferenceScenario.Fault.HOLDER_GONE);

        assertThat(outcome.status()).as("%s", outcome).isEqualTo(503);
        assertThat(outcome.text()).as("a typed forward failure (B refused it, so A cannot know it was not applied): %s", outcome)
                                  .contains("outcome unknown");
        assertThat(outcome.forwards()).as("forwarded once, to B").isEqualTo(1);
        assertThat(outcome.sliceCalls()).as("B's slice was not reached").isZero();
    }

    /// The write to B is refused locally: nothing left A, so the stored upload is deleted and the client is told nothing
    /// was applied.
    @Test
    void upload_whoseForwardNeverLeftA_isDeleted_andAnswered503NothingApplied() throws Exception {
        var outcome = ForwardByReferenceScenario.run(2 * MIB, ForwardByReferenceScenario.Fault.PEER_UNREACHABLE);

        assertThat(outcome.status()).as("%s", outcome).isEqualTo(503);
        assertThat(outcome.text()).as("%s", outcome).contains("nothing was applied");
        assertThat(outcome.catalogDelta()).as("the stored upload is deleted: %s", outcome).isZero();
    }

    /// The 503 is answered only after the stored upload is gone: the storage takes 1.5 s per delete, and a client holding the
    /// answer must already see the catalog empty (#2915). Mutation: answering before the delete finishes (the former
    /// fire-and-forget discard) leaves the object visible when the 503 arrives.
    @Test
    void upload_whoseForwardNeverLeftA_isGoneBeforeTheAnswerIsObservable_whenTheDeleteIsSlow() throws Exception {
        var outcome = ForwardByReferenceScenario.run(2 * MIB, ForwardByReferenceScenario.Fault.PEER_UNREACHABLE_SLOW_DELETE);

        assertThat(outcome.status()).as("%s", outcome).isEqualTo(503);
        assertThat(outcome.catalogDelta()).as("the stored upload is deleted before the answer: %s", outcome).isZero();
    }

    /// A delete that ends in a failure must not swallow the answer: the client is still told 503 nothing was applied.
    /// Mutation: answering only when the delete succeeded leaves the client without an answer.
    @Test
    void upload_whoseForwardNeverLeftA_isStillAnswered503_whenTheDeleteFails() throws Exception {
        var outcome = ForwardByReferenceScenario.run(2 * MIB, ForwardByReferenceScenario.Fault.PEER_UNREACHABLE_DELETE_FAILS);

        assertThat(outcome.status()).as("%s", outcome).isEqualTo(503);
        assertThat(outcome.text()).as("%s", outcome).contains("nothing was applied");
    }

    /// As above for the 413 of a forward no cluster frame can carry.
    @Test
    void upload_whoseForwardIsTooLargeToSend_isStillAnswered413_whenTheDeleteFails() throws Exception {
        var outcome = ForwardByReferenceScenario.run(2 * MIB, ForwardByReferenceScenario.Fault.OVERSIZED_FRAME_DELETE_FAILS);

        assertThat(outcome.status()).as("%s", outcome).isEqualTo(413);
    }

    /// The cluster frame limit refuses the forward before it is sent: nothing left A, so the stored upload is deleted
    /// and the client is told 413. Mutation: removing the `TooLargeToForward` arm of `discardUnsent` leaves the object.
    @Test
    void upload_whoseForwardIsTooLargeToSend_isDeleted_andAnswered413() throws Exception {
        var outcome = ForwardByReferenceScenario.run(2 * MIB, ForwardByReferenceScenario.Fault.OVERSIZED_FRAME);

        assertThat(outcome.status()).as("%s", outcome).isEqualTo(413);
        assertThat(outcome.catalogDelta()).as("the stored upload is deleted: %s", outcome).isZero();
    }

    /// The limit, pinned: B refused after receiving the forward, so A cannot tell whether anything was applied and keeps
    /// the stored upload for the unclaimed TTL (nothing reclaims it before A4).
    @Test
    void upload_whoseOutcomeIsUnknown_isKept() throws Exception {
        var outcome = ForwardByReferenceScenario.run(2 * MIB, ForwardByReferenceScenario.Fault.HOLDER_GONE);

        assertThat(outcome.catalogDelta()).as("kept: %s", outcome).isEqualTo(1);
    }

    /// Two slices on B serve the route: the ingress stores in the namespace of the first, in coordinate order, so the choice
    /// does not depend on the order the route table lists them.
    @Test
    void upload_forARouteTwoSlicesPublish_isStoredInTheNamespaceOfTheFirstInCoordinateOrder() throws Exception {
        var outcome = ForwardByReferenceScenario.run(2 * MIB, ForwardByReferenceScenario.Fault.TWO_PUBLISHERS);

        assertThat(outcome.status()).as("%s", outcome).isEqualTo(200);
        assertThat(outcome.handleNamespace()).as("%s", outcome).isEqualTo("com.example.aaa");
    }

    @Test
    void upload_forARouteOneSlicePublishes_isStoredInThatSlicesNamespace() throws Exception {
        var outcome = ForwardByReferenceScenario.run(2 * MIB);

        assertThat(outcome.handleNamespace()).as("%s", outcome).isEqualTo("com.example.svc");
    }

    @Test
    void oneGiBUpload_forwardedByReference_underA256MiBHeap_inAChildJvm() throws Exception {
        var size = 1024 * MIB;
        var values = runChild(size);
        var diagnostics = values.toString();

        assertThat(Long.parseLong(values.getOrDefault("MAX_HEAP_MB", "0"))).as("the child ran with a small heap: " + diagnostics)
                                                                           .isLessThanOrEqualTo(CHILD_HEAP_MB);
        assertThat(size).as("the body must be larger than the heap, or this proves nothing")
                        .isGreaterThan(4L * CHILD_HEAP_MB * MIB - 1);
        assertThat(values.get("STATUS")).as(diagnostics).isEqualTo("200");
        assertThat(values.get("EXIT")).as(diagnostics).isEqualTo("0");
        assertThat(values.get("FORWARDS")).as(diagnostics).isEqualTo("1");
        assertThat(Long.parseLong(values.getOrDefault("FORWARDED_BYTES", "-1"))).as(diagnostics)
                                                                                .isBetween(1L, FORWARD_BOUND - 1);
        assertThat(values.get("BODY_ON_B")).as(diagnostics).isEqualTo("0");
        assertThat(values.get("HANDLE_SIZE")).as(diagnostics).isEqualTo(Long.toString(size));
        assertThat(values.get("SERVED_SHA")).as(diagnostics).isEqualTo(values.get("SENT_SHA"));
    }

    private static HashMap<String, String> runChild(long size) throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(java,
                                         "-Xmx" + CHILD_HEAP_MB + "m",
                                         "-XX:MaxDirectMemorySize=" + CHILD_HEAP_MB + "m",
                                         "-cp",
                                         System.getProperty("java.class.path"),
                                         ForwardByReferenceScenario.class.getName(),
                                         Long.toString(size)).redirectErrorStream(true).start();
        var lines = new ArrayList<String>();

        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            var reading = Thread.ofVirtual().start(() -> reader.lines().forEach(line -> {
                synchronized (lines) {
                    lines.add(line);
                }
            }));

            if (!process.waitFor(8, TimeUnit.MINUTES)) {
                process.destroyForcibly();
            }
            reading.join(10_000);
        }

        var values = new HashMap<String, String>();

        synchronized (lines) {
            lines.stream()
                 .filter(line -> line.matches("[A-Z_]+=.*"))
                 .forEach(line -> values.put(line.substring(0, line.indexOf('=')), line.substring(line.indexOf('=') + 1)));
            values.put("EXIT", process.isAlive() ? "timeout" : Integer.toString(process.exitValue()));
            values.put("TAIL", String.join(" | ", lines.subList(Math.max(0, lines.size() - 15), lines.size())));
        }

        return values;
    }
}
