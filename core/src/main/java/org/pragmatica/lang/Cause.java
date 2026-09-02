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
package org.pragmatica.lang;

import java.util.stream.Stream;

import org.pragmatica.lang.Functions.Fn1;


/// Basic interface for failure cause types.
public interface Cause {
    /// **[Pure Transform]**
    /// Message associated with the failure.
    String message();

    /// **[Pure Transform]**
    /// True when no retry of the failed operation can change the outcome — the condition this cause
    /// reports is settled (a peer terminally removed, a resource that cannot exist, an argument that
    /// cannot parse). Retry facilities consult this and stop immediately rather than re-driving a
    /// verdict: the alternative was measured at 4,160 scheduled retries of a cause whose own message
    /// said "terminal". `false` by default — absence of classification means "unknown", and unknown
    /// stays retryable-with-bounds, so an unclassified cause behaves exactly as before.
    default boolean isTerminal() {
        return false;
    }

    /// **[Pure Transform]**
    /// The original cause (if any) of the error.
    default Option<Cause> source() {
        return Option.empty();
    }

    /// **[Factory]**
    /// Represent cause as a failure [Result] instance.
    ///
    /// @return cause converted into [Result] with the necessary type.
    default <T> Result<T> result() {
        return Result.failure(this);
    }

    /// **[Factory]**
    /// Represent cause as a failure [Promise] instance.
    ///
    /// @return cause converted into [Promise] with the necessary type.
    default <T> Promise<T> promise() {
        return Promise.failure(this);
    }

    /// **[Pure Transform]**
    /// Iterate over the cause chain, starting from this cause.
    ///
    /// @param action action to be applied to each cause in the chain.
    ///
    /// @return result of the last action.
    default <T> T iterate(Fn1<T, Cause> action) {
        var value = action.apply(this);

        return source().fold(() -> value, src -> src.iterate(action));
    }

    /// **[Pure Transform]**
    /// Stream of causes starting from this cause. For the single cause it will be a stream of one element. For composite cause, it will be a stream of all
    /// causes stored in this cause.
    ///
    /// @return stream of causes.
    default Stream<Cause> stream() {
        return Stream.of(this);
    }

    /// A cause reporting a settled condition: no retry of the failed operation can change the
    /// outcome. Implementing this interface is the classification — no override needed.
    interface Terminal extends Cause {
        @Override
        default boolean isTerminal() {
            return true;
        }
    }

    /// A cause wrapping an underlying cause. The `origin` component of the implementing record
    /// supplies [#source()]; the component cannot be named `source`, because the record accessor's
    /// return type (`Cause`) would clash with [#source()]'s (`Option<Cause>`).
    ///
    /// `source()` uses [Option#option] rather than [Option#some] deliberately: `some(null)` wraps a
    /// null without complaint, and a present-but-null source is strictly worse than an absent one.
    interface Wrapped extends Cause {
        Cause origin();

        @Override
        default Option<Cause> source() {
            return Option.option(origin());
        }
    }
}
