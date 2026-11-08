/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.http.server;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Unit.unit;


/// A [BodySink] for the streaming tests that records what the server did to it: every chunk (hashed, counted, its size),
/// how many `accept`s were outstanding at once, and every terminal call, with a violation noted when one overlapped an
/// outstanding `accept`.
///
/// It can be slow (each `accept` resolves after a delay proportional to the chunk), held (`accept` resolves only when
/// the test releases it), or failing (an `accept` fails once `failAfter` bytes were accepted). On `complete` it answers
/// `200` with the byte count and SHA-256 it received; on `fail` it answers `503`.
final class RecordingSink implements BodySink {
    private static final HexFormat HEX = HexFormat.of();
    private static final ScheduledExecutorService DELAYS = Executors.newSingleThreadScheduledExecutor(runnable -> {
        var thread = new Thread(runnable, "recording-sink-delays");

        thread.setDaemon(true);

        return thread;
    });

    private final MessageDigest digest = sha256();
    private final AtomicInteger outstanding = new AtomicInteger();
    private final AtomicInteger maxOutstanding = new AtomicInteger();
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicLong accepts = new AtomicLong();
    private final AtomicLong maxChunk = new AtomicLong();
    private final AtomicLong maxSample = new AtomicLong(Long.MIN_VALUE);
    private final List<String> terminals = new CopyOnWriteArrayList<>();
    private final List<String> violations = new CopyOnWriteArrayList<>();
    private final CompletableFuture<HttpRequest> completed = new CompletableFuture<>();
    private final CompletableFuture<String> ended = new CompletableFuture<>();
    private volatile long nanosPerByte;
    private volatile long failAfter = -1;
    private volatile CompletableFuture<Void> hold = CompletableFuture.completedFuture(null);
    private volatile LongSupplier sampler = () -> 0;
    private volatile String sha = "";

    /// Each `accept` resolves after `nanosPerByte` times the chunk's size.
    RecordingSink slow(long nanosPerByte) {
        this.nanosPerByte = nanosPerByte;

        return this;
    }

    /// The next `accept`s resolve only once the returned future is completed.
    CompletableFuture<Void> hold() {
        var latch = new CompletableFuture<Void>();

        hold = latch;

        return latch;
    }

    /// The first `accept` after `count` bytes were accepted fails.
    RecordingSink failAfter(long count) {
        this.failAfter = count;

        return this;
    }

    /// `sampler` is read on every `accept`; its largest value is [#maxSample].
    RecordingSink sampling(LongSupplier sampler) {
        this.sampler = sampler;

        return this;
    }

    @Override
    public Promise<Unit> accept(ByteBuffer chunk) {
        var now = outstanding.incrementAndGet();
        var size = chunk.remaining();

        maxOutstanding.accumulateAndGet(now, Math::max);
        maxChunk.accumulateAndGet(size, Math::max);
        maxSample.accumulateAndGet(sampler.getAsLong(), Math::max);
        accepts.incrementAndGet();
        if (failAfter >= 0 && bytes.get() >= failAfter) {
            outstanding.decrementAndGet();

            return Rejected.REJECTED.promise();
        }

        synchronized (digest) {
            digest.update(chunk);
        }

        bytes.addAndGet(size);
        var result = Promise.<Unit>promise();
        var delay = nanosPerByte * size;

        hold.thenRun(() -> DELAYS.schedule(() -> resolve(result), delay, TimeUnit.NANOSECONDS));

        return result;
    }

    private void resolve(Promise<Unit> result) {
        outstanding.decrementAndGet();
        result.succeed(unit());
    }

    @Override
    public void complete(HttpRequest request, ResponseWriter response) {
        terminal("complete");
        synchronized (digest) {
            sha = HEX.formatHex(digest.digest());
        }

        completed.complete(request);
        response.okText("received " + bytes.get() + " sha " + sha);
    }

    @Override
    public void fail(Cause cause, ResponseWriter response) {
        terminal("fail:" + cause.message());
        response.error(HttpStatus.SERVICE_UNAVAILABLE, "sink refused the body");
    }

    @Override
    public void abort(Cause cause) {
        terminal("abort:" + (cause instanceof Ended reason
                             ? reason.name()
                             : cause.message()));
    }

    private void terminal(String name) {
        if (outstanding.get() != 0) {
            violations.add(name + " while an accept was outstanding");
        }

        terminals.add(name);
        ended.complete(name);
    }

    List<String> terminals() {
        return terminals;
    }

    List<String> violations() {
        return violations;
    }

    /// The first terminal call, within `seconds`.
    String awaitTerminal(long seconds) throws Exception {
        return ended.get(seconds, TimeUnit.SECONDS);
    }

    HttpRequest awaitComplete(long seconds) throws Exception {
        return completed.get(seconds, TimeUnit.SECONDS);
    }

    boolean hasEnded() {
        return ended.isDone();
    }

    long bytes() {
        return bytes.get();
    }

    long accepts() {
        return accepts.get();
    }

    int maxOutstanding() {
        return maxOutstanding.get();
    }

    long maxChunk() {
        return maxChunk.get();
    }

    long maxSample() {
        return maxSample.get();
    }

    String sha() {
        return sha;
    }

    static String hex(byte[] digest) {
        return HEX.formatHex(digest);
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    /// Why the failing sink refused a chunk.
    enum Rejected implements Cause {
        REJECTED;

        @Override
        public String message() {
            return "the sink refused a chunk";
        }
    }
}
