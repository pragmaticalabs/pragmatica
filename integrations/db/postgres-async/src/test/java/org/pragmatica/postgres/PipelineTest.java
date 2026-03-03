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
import org.pragmatica.postgres.net.Connection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.Deque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;

import static org.pragmatica.postgres.DatabaseExtension.block;
import static java.lang.System.currentTimeMillis;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pragmatica.lang.Unit.unit;

/**
 * Tests for statements pipelining.
 *
 * @author Mikko Tiihonen
 * @author Marat Gainullin
 */
@Tag("Slow")
public class PipelineTest {

    @RegisterExtension
    static final DatabaseExtension dbr = DatabaseExtension.defaultConfiguration();

    private Connectible pool;

    @BeforeEach
    public void setupPool() {
        pool = dbr.builder.pool();
    }

    @AfterEach
    public void closePool() {
        if (pool != null) {
            block(pool.close());
        }
    }

    @Test
    public void connectionPoolPipelinesQueries() throws InterruptedException {
        int count = 5;
        double sleep = 0.5;
        Deque<Long> results = new LinkedBlockingDeque<>();
        long startWrite = currentTimeMillis();
        for (int i = 0; i < count; ++i) {
            pool.completeQuery("select " + i + ", pg_sleep(" + sleep + ")")
                .withSuccess(_ -> results.add(currentTimeMillis()))
                .recover(cause -> {
                    throw new AssertionError("failed", new Exception(cause.message()));
                });
        }
        long writeTime = currentTimeMillis() - startWrite;

        long remoteWaitTimeSeconds = (long) (sleep * count);
        SECONDS.sleep(2 + remoteWaitTimeSeconds);
        long readTime = results.getLast() - results.getFirst();

        assertEquals(count, results.size());
        assertEquals(0L, MILLISECONDS.toSeconds(writeTime));
        assertTrue(MILLISECONDS.toSeconds(readTime + 999) >= remoteWaitTimeSeconds);
    }

    @Test
    public void poolPipelinesMultipleConcurrentQueries() throws InterruptedException {
        int count = 10;
        Deque<Long> results = new LinkedBlockingDeque<>();
        long startWrite = currentTimeMillis();
        for (int i = 0; i < count; ++i) {
            pool.completeQuery("select " + i)
                .withSuccess(_ -> results.add(currentTimeMillis()))
                .recover(cause -> {
                    throw new AssertionError("failed", new Exception(cause.message()));
                });
        }
        long writeTime = currentTimeMillis() - startWrite;

        SECONDS.sleep(3);

        assertEquals(count, results.size());
        assertEquals(0L, MILLISECONDS.toSeconds(writeTime));
    }

}
