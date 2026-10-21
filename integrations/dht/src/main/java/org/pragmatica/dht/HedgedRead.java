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
package org.pragmatica.dht;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;


/// A verified read hedged across replicas (A6, D2; spec A3b): the first candidate is asked at once, and the next one
/// as soon as the previous answered with a miss or has not answered within the hedge delay, whichever comes first. The
/// first verified answer wins; the read is empty once every candidate has answered with a miss. Each probe carries its
/// own deadline, so the whole read ends by the latest probe deadline, never by the sum of per-replica timeouts.
final class HedgedRead {
    private final List<NodeId> candidates;
    private final Fn1<Promise<Option<byte[]>>, NodeId> probe;
    private final TimeSpan hedgeDelay;
    private final Runnable onMovePast;
    private final Promise<Option<byte[]>> result = Promise.promise();
    private final AtomicInteger launched = new AtomicInteger();
    private final AtomicInteger settled = new AtomicInteger();

    private HedgedRead(List<NodeId> candidates,
                       Fn1<Promise<Option<byte[]>>, NodeId> probe,
                       TimeSpan hedgeDelay,
                       Runnable onMovePast) {
        this.candidates = List.copyOf(candidates);
        this.probe = probe;
        this.hedgeDelay = hedgeDelay;
        this.onMovePast = onMovePast;
    }

    /// Read `candidates` in order with hedging. `probe` answers one candidate's verified value or a miss and never fails;
    /// `onMovePast` runs each time the read asks a further candidate.
    static Promise<Option<byte[]>> hedgedRead(List<NodeId> candidates,
                                              Fn1<Promise<Option<byte[]>>, NodeId> probe,
                                              TimeSpan hedgeDelay,
                                              Runnable onMovePast) {
        var read = new HedgedRead(candidates, probe, hedgeDelay, onMovePast);

        read.launchNext();

        return read.result;
    }

    @Contract
    private void launchNext() {
        var index = launched.getAndUpdate(count -> Math.min(count + 1, candidates.size()));

        if (index >= candidates.size() || result.isResolved()) {
            return;
        }

        if (index > 0) {
            onMovePast.run();
        }

        probe.apply(candidates.get(index)).onResult(outcome -> onSettled(outcome.or(Option.none())));
        hedgeAfter(index + 1);
    }

    /// Ask the next candidate after the hedge delay, unless one was asked meanwhile or the read has ended.
    @Contract
    private void hedgeAfter(int launchedSoFar) {
        if (launchedSoFar < candidates.size()) {
            SharedScheduler.schedule(() -> hedge(launchedSoFar), hedgeDelay);
        }
    }

    @Contract
    private void hedge(int launchedSoFar) {
        if (!result.isResolved() && launched.get() == launchedSoFar) {
            launchNext();
        }
    }

    @Contract
    private void onSettled(Option<byte[]> value) {
        if (value.isPresent()) {
            result.succeed(value);

            return;
        }

        var answered = settled.incrementAndGet();

        if (launched.get() < candidates.size()) {
            launchNext();
        } else if (answered == candidates.size()) {
            result.succeed(Option.none());
        }
    }
}
