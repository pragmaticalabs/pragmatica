// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.routes.ProblemResponses;
import org.pragmatica.aether.api.routes.RequestParse;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.management.route.MatchedRoute;
import org.pragmatica.aether.slice.SliceDefect;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// #3723 / #3794: a caught throwable's text (its stack trace) never reaches a management client body. A management
/// request failure carries the CAUSE of the failure, never a String copied out of it, so the origin stays a type all the
/// way to the renderer. Each test writes the failure through the exact `ProblemResponses.writeProblem` call the router
/// makes, and reads the body. The sentinel is the throwable's own message: it must not appear.
class ManagementClientErrorsNoTraceTest {
    private static final String SENTINEL = "SENTINEL-3794-management";

    private record Written(HttpStatus status, String body) {}

    private static Written written(Cause failure) {
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
        }, failure, "/x", "req_1");

        return new Written(status.get(), body.get());
    }

    private static String body(Cause failure) {
        return written(failure).body();
    }

    private static void assertNoTrace(String body) {
        assertThat(body).doesNotContain(SENTINEL)
                        .doesNotContain("NumberFormatException")
                        .doesNotContain("IllegalStateException")
                        .doesNotContain("\\tat ")
                        .doesNotContain("\tat ")
                        .doesNotContain("at java");
    }

    private static Cause failureOf(Result<?> result) {
        return result.fold(cause -> cause,
                           _ -> {
                               throw new AssertionError("expected failure");
                           });
    }

    @Test
    void malformedArtifactVersion_inManagementParse_carriesNoTrace() {
        assertNoTrace(body(failureOf(RequestParse.asRequest(Artifact.artifact("org.example:foo:1.x.0")))));
    }

    @Test
    void malformedVersion_inManagementParse_carriesNoTrace() {
        assertNoTrace(body(failureOf(RequestParse.asRequest(Version.version("1.x.0")))));
    }

    @Test
    void malformedVersion_inManagementParse_stillAnswersBadRequest() {
        assertThat(written(failureOf(RequestParse.asRequest(Version.version("1.x.0")))).status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void managementRouteFailingWithMethodThrew_carriesNoTrace() {
        assertNoTrace(body(SliceDefect.MethodThrew.FACTORY.apply(Causes.fromThrowable(new IllegalStateException(SENTINEL)))));
    }

    @Test
    void managementRouteFailingWithAPlainThrowable_carriesNoTrace() {
        assertNoTrace(body(Causes.fromThrowable(new IllegalStateException(SENTINEL))));
    }

    @Test
    void forwardGuard_malformedNodeId_answersARequestFailureWithNoTrace() {
        var refusal = ManagementServerImpl.malformedForwardParam(MatchedRoute.matchedRoute(ManagementRoute.NODE_STATUS_GET,
                                                                                           List.of("   ")));

        assertThat(refusal.isPresent()).isTrue();
        refusal.onPresent(cause -> assertNoTrace(body(cause)));
    }

    @Test
    void forwardGuard_malformedStreamAddressVersion_answersARequestFailureWithNoTrace() {
        var refusal = ManagementServerImpl.malformedForwardParam(MatchedRoute.matchedRoute(ManagementRoute.STREAM_REPLICAS,
                                                                                           List.of("ns", "orders", "1.x.0", "0")));

        assertThat(refusal.isPresent()).isTrue();
        refusal.onPresent(cause -> assertNoTrace(body(cause)));
    }
}
