/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.pragmatica.postgres;

import org.pragmatica.postgres.net.Connectible;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.pragmatica.postgres.DatabaseExtension.block;
import static java.lang.System.currentTimeMillis;
import static java.lang.System.out;
import static org.pragmatica.lang.Unit.unit;

@Tag("Slow")
public class PerformanceTest {
    private static final DatabaseExtension dbr;
    private static final boolean IS_MAC = System.getProperty("os.name").toLowerCase().startsWith("mac");

    static {
        // Uncomment to run with single event loop thread, although I see no big value in it
//        System.setProperty("io.netty.eventLoopThreads", "1");
        dbr = DatabaseExtension.withMaxConnections(1);
    }

    private static final String SELECT_42 = "select 42";

    static Stream<Arguments> data() {
        var numbers = IS_MAC ? List.of(1, 4, 8) : List.of(1, 6, 12);
        return numbers.stream()
            .flatMap(poolSize -> numbers.stream()
                .map(threads -> Arguments.of(poolSize, threads)));
    }

    private static final int batchSize = IS_MAC ? 300 : 400;
    private static final int repeats = IS_MAC ? 3 : 4;
    private static final SortedMap<Integer, SortedMap<Integer, Long>> simpleQueryResults = new TreeMap<>();
    private static final SortedMap<Integer, SortedMap<Integer, Long>> preparedStatementResults = new TreeMap<>();

    @ParameterizedTest(name = "{index}: maxConnections={0}, threads={1}")
    @MethodSource("data")
    public void observeBatches(int poolSize, int numThreads) {
        // Setup
        DatabaseExtension.postgres.start();
        Connectible pool = dbr.builder
            .maxConnections(poolSize)
            .port(DatabaseExtension.postgres.getMappedPort(5432))
            .hostname(DatabaseExtension.postgres.getHost())
            .password(DatabaseExtension.postgres.getPassword())
            .database(DatabaseExtension.postgres.getDatabaseName())
            .username(DatabaseExtension.postgres.getUsername())
            .pool();
        var connections = IntStream.range(0, poolSize)
                                   .mapToObj(_ -> block(pool.getConnection())).toList();
        connections.forEach(connection -> {
            var ps = block(connection.prepareStatement(SELECT_42));
            block(ps.close());
            block(connection.close());
        });

        try {
            performBatches(simpleQueryResults, poolSize, numThreads, pool, _ -> new Batch(batchSize, pool).startWithSimpleQuery());
            performBatches(preparedStatementResults, poolSize, numThreads, pool, _ -> new Batch(batchSize, pool).startWithPreparedStatement());
        } finally {
            // Teardown
            block(pool.close());
        }
    }

    @SuppressWarnings("OptionalGetWithoutIsPresent")
    private void performBatches(SortedMap<Integer, SortedMap<Integer, Long>> results, int poolSize, int numThreads, Connectible pool, IntFunction<Promise<Long>> batchStarter) {
        double mean = LongStream.range(0, repeats)
                                .map(_ -> {
                                    try {
                                        var batches = IntStream.range(0, poolSize)
                                                               .mapToObj(batchStarter)
                                                               .toList();
                                        awaitAll(batches);
                                        return batches.stream()
                                                      .map(b -> block(b))
                                                      .max(Long::compare)
                                                      .get();
                                    } catch (Exception ex) {
                                        throw new RuntimeException(ex);
                                    }
                                })
                                .average()
                                .getAsDouble();
        results.computeIfAbsent(poolSize, _ -> new TreeMap<>())
               .put(numThreads, Math.round(mean));
    }

    private static void awaitAll(List<Promise<Long>> promises) {
        if (promises.isEmpty()) {
            return;
        }
        var result = Promise.<Unit>promise();
        var remaining = new AtomicInteger(promises.size());
        for (var p : promises) {
            p.onResult(r -> r.fold(
                cause -> {
                    result.fail(cause);
                    return null;
                },
                _ -> {
                    if (remaining.decrementAndGet() == 0) {
                        result.succeed(unit());
                    }
                    return null;
                }
            ));
        }
        block(result);
    }

    private static class Batch {
        private final long batchSize;
        private final Connectible pool;
        private long performed;
        private long startedAt;
        private Promise<Long> onBatch;

        Batch(long batchSize, Connectible pool) {
            this.batchSize = batchSize;
            this.pool = pool;
        }

        private Promise<Long> startWithPreparedStatement() {
            onBatch = Promise.promise();
            startedAt = System.currentTimeMillis();
            nextSamplePreparedStatement();
            return onBatch;
        }

        private Promise<Long> startWithSimpleQuery() {
            onBatch = Promise.promise();
            startedAt = System.currentTimeMillis();
            nextSampleSimpleQuery();
            return onBatch;
        }

        private void nextSamplePreparedStatement() {
            pool.getConnection()
                .flatMap(connection ->
                             connection.prepareStatement(SELECT_42)
                                       .flatMap(stmt ->
                                                    stmt.query()
                                                        .fold(_ -> stmt.close())
                                                        .fold(_ -> connection.close())))

                .withSuccess(_ -> completeBatchStep(true))
                .recover(this::failBatch);

        }

        private void completeBatchStep(boolean preparedStatement) {
            if (++performed < batchSize) {
                if (preparedStatement) {
                    nextSamplePreparedStatement();
                } else {
                    nextSampleSimpleQuery();
                }
            } else {
                long duration = currentTimeMillis() - startedAt;
                onBatch.succeed(duration);
            }
        }

        private Unit failBatch(Cause cause) {
            onBatch.fail(cause);
            return unit();
        }

        private void nextSampleSimpleQuery() {
            pool.completeScript(SELECT_42)
                .withSuccess(_ -> completeBatchStep(false))
                .withFailure(cause -> onBatch.fail(cause));
        }
    }

    @AfterAll
    public static void printResults() {
        out.println();
        out.println("Requests per second, Hz:");
        out.println();
        out.println("Simple query protocol");
        printResults(simpleQueryResults);
        out.println();
        out.println("Extended query protocol (reusing prepared statement)");
        printResults(preparedStatementResults);
    }

    private static void printResults(SortedMap<Integer, SortedMap<Integer, Long>> results) {
        if (results.isEmpty()) {
            return;
        }
        out.print(" threads");
        results.keySet().forEach(i -> out.printf("\t%d conn\t", i));
        out.println();

        results.values().iterator().next().keySet().forEach(threads -> {
            out.print("    " + threads);
            results.keySet().forEach(connections -> {
                long batchDuration = results.get(connections).get(threads);
                double rps = 1000 * batchSize * connections / (double) batchDuration;
                out.printf("\t\t%d", Math.round(rps));
            });
            out.println();
        });
    }
}
