// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.deployment.cluster.HierarchyStateWriter;
import org.pragmatica.aether.slice.kvstore.SliceTargetFence.SliceTargetFenceError;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2101: a management failure wrapped in an [HttpError] keeps its origin chain; the problem body carries the top
/// cause's message only.
class ProblemResponsesChainTest {
    private static final String SENTINEL = "SENTINEL-origin-detail";

    record Wrapped(String message, Cause origin) implements Cause {
        @Override
        public Option<Cause> source() {
            return Option.some(origin);
        }
    }

    @Test
    void writeProblem_ofChainedHttpError_omitsOriginChainFromBody() {
        var body = new AtomicReference<String>();
        var failure = HttpError.httpError(HttpStatus.SERVICE_UNAVAILABLE, new Wrapped("top", Causes.cause(SENTINEL)));

        ProblemResponses.writeProblem(new ResponseWriter() {
            @Override
            public void write(HttpStatus status, byte[] bytes, ContentType contentType) {
                body.set(new String(bytes, StandardCharsets.UTF_8));
            }

            @Override
            public ResponseWriter header(String name, String value) {
                return this;
            }
        }, failure, "/x", "req-1");

        assertThat(body.get()).contains("top").doesNotContain(SENTINEL);
    }

    /// #2944: a fence CONFLICT that outlasted the leader-side retry reaches the wire as 409 with the retry hint, not as 500.
    @Test
    void writeProblem_ofFenceConflictRefusal_answers409WithRetryHint() {
        var status = new AtomicReference<HttpStatus>();
        var body = new AtomicReference<String>();

        ProblemResponses.writeProblem(new ResponseWriter() {
            @Override
            public void write(HttpStatus responseStatus, byte[] bytes, ContentType contentType) {
                status.set(responseStatus);
                body.set(new String(bytes, StandardCharsets.UTF_8));
            }

            @Override
            public ResponseWriter header(String name, String value) {
                return this;
            }
        }, HierarchyStateWriter.Refusal.CONFLICT, "/api/cluster/topology/auto-heal/disable", "req-2");

        assertThat(status.get()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body.get()).contains("retry the request");
    }

    /// #2944: an exhausted ab-test / rolling-update fence write used to reach the wire as a 500; it is a 409 carrying the retry hint.
    @Test
    void writeProblem_ofExhaustedSliceTargetFence_answers409WithRetryHint() {
        var status = new AtomicReference<HttpStatus>();
        var body = new AtomicReference<String>();

        ProblemResponses.writeProblem(new ResponseWriter() {
            @Override
            public void write(HttpStatus responseStatus, byte[] bytes, ContentType contentType) {
                status.set(responseStatus);
                body.set(new String(bytes, StandardCharsets.UTF_8));
            }

            @Override
            public ResponseWriter header(String name, String value) {
                return this;
            }
        }, new SliceTargetFenceError.Contended("ab-test-conclude t1", 6), "/api/ab-test/t1/conclude", "req-3");

        assertThat(status.get()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body.get()).contains("retry the request");
    }
}
