// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.forward.StreamForwardError;
import org.pragmatica.aether.stream.forward.StreamForwardHandler;
import org.pragmatica.aether.stream.replication.ReplicationError;
import org.pragmatica.lang.Cause;
import org.pragmatica.http.HttpStatus;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// A publish is not idempotent: on the publish route a raw [StreamError] may answer a retry-inviting status (503, 429)
/// ONLY when it is on the pre-append refusal list. Every other raw stream error keeps the 500 it answered before the
/// management funnel started classifying stream errors by class. One test per stream error type, through the same
/// `retryableRefusal` the route applies and the same funnel the response is written through.
class StreamPublishStatusTest {
    /// The pre-append refusals (`StreamApiRoutes.isPreAppendRefusal`): nothing was written, so a retry is safe.
    private static final Set<String> PRE_APPEND = Set.of("StreamConfigNotYetVisible",
                                                          "OwnerNotActivated",
                                                          "NotOwnerAppend",
                                                          "ReshufflePaced",
                                                          "MaterializeBudgetExceeded",
                                                          "General.STREAM_MEMORY_EXCEEDED",
                                                          "General.SEALING_BEHIND",
                                                          "General.SEGMENT_TIER_FULL");

    @TestFactory
    Stream<DynamicTest> everyRawStreamError_onPublish_isRetryInvitingOnlyWhenPreAppend() {
        return StreamErrorStatusTest.expectations()
                                    .keySet()
                                    .stream()
                                    .map(key -> (StreamError) key)
                                    .map(error -> DynamicTest.dynamicTest(label(error), () -> assertPublishStatus(error)));
    }

    @Test
    void publishRule_coversEveryStreamErrorType() {
        var types = StreamErrorStatusTest.expectations().keySet().stream().map(StreamPublishStatusTest::label).toList();

        assertThat(types).as("the three pre-append types are all in the swept set").containsAll(PRE_APPEND);
        assertThat(types).hasSizeGreaterThan(40);
    }

    /// A refusal the owner raises before anything is written answers the SAME status whether the publisher is the owner
    /// itself (the raw cause) or a forwarder (the owner answers retryable, the publisher rebuilds `RemotePublishRetryable`):
    /// 503 on both paths, for every pre-append refusal.
    @TestFactory
    Stream<DynamicTest> everyPreAppendRefusal_answersTheSameStatus_ownerAndForwarded() {
        var refusals = Stream.concat(StreamErrorStatusTest.expectations().keySet().stream()
                                                          .map(key -> (Cause) key)
                                                          .filter(error -> PRE_APPEND.contains(label(error))),
                                     Stream.of((Cause) ReplicationError.General.NOT_ENOUGH_REPLICAS));

        return refusals.map(raw -> DynamicTest.dynamicTest(label(raw), () -> assertSameStatusOnBothPaths(raw)));
    }

    private static void assertSameStatusOnBothPaths(Cause raw) {
        var owner = ManagementStatus.statusOf(StreamApiRoutes.retryableRefusal("s", raw));

        assertThat(StreamForwardHandler.isRetryable(raw)).as("the owner answers a forwarded publish retryable: " + label(raw)).isTrue();

        var forwarded = ManagementStatus.statusOf(StreamApiRoutes.retryableRefusal("s", new StreamForwardError.RemotePublishRetryable(raw.message())));

        assertThat(owner).as("owner path " + label(raw)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(forwarded).as("forwarded path " + label(raw)).isEqualTo(owner);
    }

    private static void assertPublishStatus(StreamError error) {
        var onPublish = StreamApiRoutes.retryableRefusal("s", error);
        var expected = PRE_APPEND.contains(label(error))
                       ? HttpStatus.SERVICE_UNAVAILABLE
                       : HttpStatus.INTERNAL_SERVER_ERROR;

        assertThat(ManagementStatus.statusOf(onPublish)).as(label(error)).isEqualTo(expected);
        assertThat(onPublish.message()).as("the engine's message is kept").contains(error.message());
    }

    private static String label(Object error) {
        if (error instanceof ReplicationError.General general) {
            return "Replication." + general.name();
        }

        return error instanceof StreamError.General general
               ? "General." + general.name()
               : error.getClass().getSimpleName();
    }
}
