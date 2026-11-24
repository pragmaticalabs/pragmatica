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
package org.pragmatica.postgres.net;

import java.util.function.Supplier;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/**
 * The one way a lease-holding resource is released (#3635).
 * <p>
 * Invariant: every resource that holds a lease (an event-loop group lease, an open channel, a backend session) is released
 * on EVERY outcome of every step before it. A chain never short-circuits on failure: it runs each release, then resolves
 * with the FIRST failure, or with success when nothing failed.
 */
public final class ReleaseChain {
    private ReleaseChain() {}

    /// Runs every release in order, each once the previous one has settled, whatever its outcome; resolves with the first
    /// failure, or success when every release succeeded.
    @SafeVarargs
    public static Promise<Unit> releaseAll(Supplier<Promise<Unit>>... releases) {
        var chain = Promise.unitPromise();

        for (var release : releases) {
            chain = releasingAfter(chain, release);
        }

        return chain;
    }

    /// Runs `release` once `step` has settled, whatever its outcome; resolves with the failure of `step`, else with the
    /// failure of `release`, else with the value of `step`.
    public static <T> Promise<T> releasingAfter(Promise<T> step, Supplier<Promise<Unit>> release) {
        return step.fold(outcome -> release.get()
                                           .fold(released -> Promise.resolved(firstFailure(outcome, released))));
    }

    /// Runs `release` only when `step` failed, and resolves with `step`'s own outcome: on that path the step's cause is the
    /// first failure, so a failure of the release itself is not reported.
    public static <T> Promise<T> releasingOnFailure(Promise<T> step, Supplier<Promise<Unit>> release) {
        return step.fold(outcome -> outcome.isFailure()
                                    ? release.get()
                                             .fold(_ -> Promise.resolved(outcome))
                                    : Promise.resolved(outcome));
    }

    private static <T> Result<T> firstFailure(Result<T> outcome, Result<Unit> released) {
        return outcome.flatMap(value -> released.map(_ -> value));
    }
}
