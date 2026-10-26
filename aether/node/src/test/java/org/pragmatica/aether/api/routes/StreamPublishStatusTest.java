// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.pragmatica.aether.stream.StreamError;
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
    private static final Set<String> PRE_APPEND = Set.of("StreamConfigNotYetVisible", "OwnerNotActivated", "NotOwnerAppend");

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

        assertThat(types).as("the three pre-append types are all in the swept set").containsAll(List.of("StreamConfigNotYetVisible", "OwnerNotActivated", "NotOwnerAppend"));
        assertThat(types).hasSizeGreaterThan(40);
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
        return error instanceof StreamError.General general
               ? "General." + general.name()
               : error.getClass().getSimpleName();
    }
}
