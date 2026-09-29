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
package org.pragmatica.utility.warning;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Result.lift;
import static org.pragmatica.lang.Unit.unit;


/// The narrow port through which a lower module reaches the cluster event log (#1574).
///
/// Every component receives its sink per instance, and nothing binds a static one. Ember runs several
/// nodes in one JVM, so a process-wide binding would attribute one node's warnings to another. The node
/// assembly binds each component to its own node's aggregator. [#logOnly] is the default for a
/// component that was never wired, in which case the log line written by [OperatorWarnings#raise] is the
/// whole report.
///
/// **Sealed so that `raise` cannot block its caller (#1617 R2).** Warnings are raised from hot paths such
/// as SWIM, replication and the core-absence fence, so a slow publisher must never stall them. The only
/// sink that publishes anything is [#handingOffTo], which enqueues onto a bounded queue drained by one
/// virtual thread. A caller-supplied consumer can never run on the raising thread, because no other kind
/// of sink can be constructed.
public sealed interface OperatorWarningSink {
    /// Queue capacity of a [#handingOffTo] sink. A burst beyond this is dropped and counted.
    int HAND_OFF_CAPACITY = 256;

    /// Offers `warning` for publication and returns at once. It never runs the publisher on this thread.
    @Contract
    void accept(OperatorWarning warning);

    /// Emits nothing. [OperatorWarnings#raise] has already logged the warning.
    static OperatorWarningSink logOnly() {
        return LogOnly.INSTANCE;
    }

    /// A sink that hands each warning to `publisher` on its own virtual thread, one at a time and in
    /// order, through a queue of [#HAND_OFF_CAPACITY]. When the queue is full the warning is dropped and
    /// counted. Its log line has already been written, so only the event is lost. The next warning that
    /// is accepted logs how many were dropped since the last report.
    static OperatorWarningSink handingOffTo(Consumer<OperatorWarning> publisher) {
        return HandOff.handOff(publisher);
    }

    enum LogOnly implements OperatorWarningSink {
        INSTANCE;
        @Contract
        @Override
        public void accept(OperatorWarning warning) {
        // intentionally empty — the log line is the report when no event log is wired
        }
    }

    /// Bounded hand-off. The executor's single thread is virtual and times out when idle, so a sink that a
    /// stopped node leaves behind holds no thread.
    final class HandOff implements OperatorWarningSink {
        private static final Logger LOG = LoggerFactory.getLogger(HandOff.class);
        private static final long IDLE_THREAD_TIMEOUT_SECONDS = 10L;

        private final Consumer<OperatorWarning> publisher;
        private final AtomicLong dropped = new AtomicLong();
        private final AtomicLong droppedSinceReport = new AtomicLong();
        private final ThreadPoolExecutor executor;

        private HandOff(Consumer<OperatorWarning> publisher) {
            this.publisher = publisher;
            this.executor = new ThreadPoolExecutor(1,
                                                   1,
                                                   IDLE_THREAD_TIMEOUT_SECONDS,
                                                   TimeUnit.SECONDS,
                                                   new ArrayBlockingQueue<>(HAND_OFF_CAPACITY),
                                                   Thread.ofVirtual().name("operator-warning-hand-off").factory(),
                                                   countingRejection());
            this.executor.allowCoreThreadTimeOut(true);
        }

        static HandOff handOff(Consumer<OperatorWarning> publisher) {
            return new HandOff(publisher);
        }

        @Contract
        @Override
        public void accept(OperatorWarning warning) {
            var droppedBefore = dropped.get();

            executor.execute(() -> publish(warning));
            reportDroppedIfAccepted(droppedBefore);
        }

        /// Total warnings dropped because the queue was full.
        public long dropped() {
            return dropped.get();
        }

        private Unit reportDroppedIfAccepted(long droppedBefore) {
            return dropped.get() == droppedBefore
                   ? reportDropped(droppedSinceReport.getAndSet(0L))
                   : unit();
        }

        private static Unit reportDropped(long count) {
            if (count > 0L) {
                LOG.warn("{} operator warning event(s) dropped: the hand-off queue ({}) was full. Their log lines were written",
                         count,
                         HAND_OFF_CAPACITY);
            }

            return unit();
        }

        /// A publisher that throws must not kill the drain; it is logged and the next warning is published.
        /// That is forward recovery: the warning's log line is already written.
        private Unit publish(OperatorWarning warning) {
            return lift(Causes::fromThrowable,
                        () -> publisher.accept(warning)).onFailure(cause -> LOG.warn("[{}] operator warning for {} not emitted, the log line stands: {}",
                                                                                     warning.code().code(),
                                                                                     warning.subject(),
                                                                                     cause.message()))
                       .or(unit());
        }

        private RejectedExecutionHandler countingRejection() {
            return (_, _) -> countDrop();
        }

        private Unit countDrop() {
            dropped.incrementAndGet();
            droppedSinceReport.incrementAndGet();

            return unit();
        }
    }
}
