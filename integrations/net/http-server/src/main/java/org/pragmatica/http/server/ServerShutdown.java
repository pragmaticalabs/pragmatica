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

import java.util.concurrent.TimeUnit;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.Future;

import static org.pragmatica.lang.Promise.promise;
import static org.pragmatica.lang.Promise.resolved;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Bounded, outcome-reporting shutdown steps shared by [NettyHttpServer] and [Http3Server] (#1612). This is the
/// shape `org.pragmatica.net.tcp.Server` got in #1610/#1614, whose helpers are private to that interface.
///
/// **Why the quiet period is 0 for HTTP.** A quiet period only delays termination: the loop waits until no
/// task has been submitted for that long. It protects nothing here.
/// - By the time the groups are asked to shut down, `stop()` has already closed the server channel, so no new
///   connection or datagram can arrive.
/// - Accepted connections are not drained by it either. In Netty 4.2.9, `SingleThreadIoEventLoop.run` calls
///   `ioHandler.prepareToDestroy()` on the first iteration after shutdown begins, and
///   `NioIoHandler.prepareToDestroy` closes every channel still registered — HTTP/1.1 children and the HTTP/3
///   datagram channel alike — before `confirmShutdown` ever consults the quiet period.
/// - A response that a handler completes asynchronously during the window is submitted to the loop as a
///   task, and would run, but its write then fails on the channel `prepareToDestroy` already closed.
///
/// Draining in-flight requests is therefore the caller's step before `stop()` (Aether's drain procedure
/// quiesces app traffic first), not something a quiet period could provide. A non-zero period would only
/// stretch every stop, and a loop that keeps receiving tasks might never go quiet until the timeout.
sealed interface ServerShutdown {
    /// Group termination bound, enforced on the event loop itself. A wedged loop cannot enforce it, so
    /// [#terminated] adds a caller-side bound.
    long SHUTDOWN_TIMEOUT_MS = 5_000L;
    /// Bound on each channel close. A close runs on the channel's event loop, so a wedged loop never completes
    /// it. Without this bound, `stop()` would hang there and never ask the groups to shut down.
    long CHANNEL_CLOSE_TIMEOUT_MS = 2_000L;

    /// A channel close, bounded.
    static Promise<Unit> closed(Channel channel) {
        return completion(channel.close()).timeout(timeSpan(CHANNEL_CLOSE_TIMEOUT_MS).millis());
    }

    /// Requests the group's shutdown with no quiet period, then waits for termination, bounded caller-side.
    static Promise<Unit> terminated(EventLoopGroup group) {
        return completion(group.shutdownGracefully(0, SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        .timeout(timeSpan(SHUTDOWN_TIMEOUT_MS + 1_000L).millis());
    }

    /// Resolves once `second` settles, with `first`'s failure if it has one, otherwise `second`'s outcome. The
    /// typed cause itself is reported, never an `all(...)` composite.
    static Promise<Unit> firstFailureOf(Result<Unit> first, Promise<Unit> second) {
        return second.fold(secondOutcome -> resolved(first.flatMap(_ -> secondOutcome)));
    }

    /// A Netty future as a promise: success, or its failure cause.
    private static Promise<Unit> completion(Future<?> future) {
        return promise(settled -> future.addListener(done -> settled.resolve(outcomeOf(done))));
    }

    private static Result<Unit> outcomeOf(Future<?> done) {
        return done.isSuccess()
               ? Result.unitResult()
               : Causes.fromThrowable(done.cause())
                       .result();
    }

    record unused() implements ServerShutdown {}
}
