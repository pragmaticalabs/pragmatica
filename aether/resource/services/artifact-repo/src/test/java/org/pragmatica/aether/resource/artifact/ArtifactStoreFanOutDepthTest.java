// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.artifact;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// The chunk fan-out runs its batches as a LOOP: a batch that settles synchronously (a memory-tier storage does) is
/// consumed in place, so the stack depth at an operation does not grow with the number of batches. It used to nest
/// 6 frames per batch through `flatMap` continuations (measured), which a large artifact of 64 KB chunks turns into
/// thousands of frames. The bound is relative, many batches against few: an absolute figure would depend on the
/// test runner's own depth. The method is private, reached by reflection so the loop is measured alone.
class ArtifactStoreFanOutDepthTest {
    private static final int IN_FLIGHT = 8;
    private static final int SLACK_FRAMES = 60;

    private Object store;
    private Method fanOut;

    @BeforeEach
    void setUp() throws Exception {
        var storage = StorageInstance.storageInstance("fanout-depth", List.of(MemoryTier.memoryTier(1024 * 1024)));

        store = ArtifactStore.artifactStore(ReplicatedTestDht.single(), storage);
        fanOut = store.getClass().getDeclaredMethod("boundedFanOut", List.class, int.class, Function.class);
        fanOut.setAccessible(true);
    }

    @Test
    @Timeout(60)
    void stackDepthDoesNotGrowWithTheBatchCount_andResultsKeepInputOrder() throws Exception {
        var shallow = run(IN_FLIGHT * 2, false);
        var deep = run(IN_FLIGHT * 400, false);

        assertThat(deep.results()).as("results in input order across 400 batches").containsExactlyElementsOf(range(IN_FLIGHT * 400));
        assertThat(deep.maxDepth()).as("max stack depth at an operation: 400 batches vs 2").isLessThanOrEqualTo(shallow.maxDepth() + SLACK_FRAMES);
    }

    /// The pending path: every operation settles on another thread, so each batch resumes the loop from `onResult`.
    @Test
    @Timeout(60)
    void batchesThatSettleLater_keepInputOrder() throws Exception {
        var run = run(IN_FLIGHT * 40, true);

        assertThat(run.results()).containsExactlyElementsOf(range(IN_FLIGHT * 40));
    }

    @Test
    @Timeout(60)
    void firstFailure_stopsLaterBatches() throws Exception {
        var started = new CopyOnWriteArrayList<Integer>();
        Function<Integer, Promise<Integer>> op = i -> {
            started.add(i);

            return i == 20 ? Causes.cause("chunk 20 failed").promise() : Promise.success(i);
        };

        var result = invoke(range(IN_FLIGHT * 10), op).await();

        assertThat(result.isFailure()).isTrue();
        assertThat(started).as("no batch after the failing one is started").doesNotContain(IN_FLIGHT * 3);
    }

    @Test
    @Timeout(30)
    void anOperationThatThrows_failsTheFanOut_ratherThanHanging() throws Exception {
        Function<Integer, Promise<Integer>> op = i -> {
            if (i == 11) {
                throw new IllegalStateException("op threw instead of returning a promise");
            }

            return Promise.success(i);
        };

        invoke(range(IN_FLIGHT * 4), op).await()
                                        .onSuccess(_ -> fail("a throwing operation must fail the fan-out"))
                                        .onFailure(cause -> assertThat(cause.message()).contains("op threw"));
    }

    private record Run(List<Integer> results, int maxDepth) {}

    private Run run(int items, boolean settleLater) throws Exception {
        var depths = new CopyOnWriteArrayList<Integer>();
        Function<Integer, Promise<Integer>> op = i -> {
            depths.add((int) (long) StackWalker.getInstance().walk(frames -> frames.count()));

            if (!settleLater) {
                return Promise.success(i);
            }

            var promise = Promise.<Integer>promise();

            Thread.ofVirtual().start(() -> promise.succeed(i));

            return promise;
        };
        var results = new ArrayList<Integer>();

        invoke(range(items), op).await().onFailure(cause -> fail("fan-out failed: " + cause.message())).onSuccess(results::addAll);

        return new Run(results, depths.stream().mapToInt(Integer::intValue).max().orElse(-1));
    }

    @SuppressWarnings("unchecked")
    private Promise<List<Integer>> invoke(List<Integer> items, Function<Integer, Promise<Integer>> op) throws Exception {
        try {
            return (Promise<List<Integer>>) fanOut.invoke(store, items, IN_FLIGHT, op);
        } catch (InvocationTargetException e) {
            throw new AssertionError("boundedFanOut threw instead of returning a promise", e.getCause());
        }
    }

    private static List<Integer> range(int count) {
        var items = new ArrayList<Integer>();

        for (var i = 0; i < count; i++) {
            items.add(i);
        }

        return items;
    }
}
