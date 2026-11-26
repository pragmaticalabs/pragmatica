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
    /// The text safe to put in a client-facing body, as opposed to [#message()], which is for logs and may carry what a
    /// client must never read (a caught throwable's stack trace, an origin chain). The default is [#message()]: a cause
    /// that holds nothing hidden has nothing to hide. A cause that holds another cause derives its client text from the
    /// held cause's `clientMessage()`, never from its `message()` (#3723): text stays honest only while it never leaves
    /// the type that knows what is safe, so a wrapper never copies a source's message into its own client text.
    default String clientMessage() {
        return message();
    }

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

    /// True when the condition this cause reports is expected to pass — a timeout, a refused
    /// connection, an exhausted pool, a 5xx from a peer — so the same operation may succeed if
    /// retried. The mirror of [#isTerminal]: that one says "never retry", this one says "worth
    /// retrying". Retry facilities that retry ONLY classified causes consult it (the slice retry
    /// interceptor's default policy, #280); [org.pragmatica.lang.utils.Retry] itself keeps retrying
    /// anything non-terminal within its bounds, so an unclassified cause is unchanged there.
    /// `false` by default — absence means "unknown", and a business verdict such as "insufficient
    /// funds" is exactly the kind of cause nobody classifies.
    default boolean isTransient() {
        return false;
    }

    /// A cause reporting a settled condition: no retry of the failed operation can change the
    /// outcome. Implementing this interface is the classification — no override needed.
    interface Terminal extends Cause {
        @Override
        default boolean isTerminal() {
            return true;
        }
    }

    /// A cause reporting a passing condition: the operation may succeed if retried. Implementing
    /// this interface is the classification — no override needed. A cause is never both
    /// [Terminal] and [Transient]; the two say opposite things.
    interface Transient extends Cause {
        @Override
        default boolean isTransient() {
            return true;
        }
    }

    /// Marker for a domain-validation failure: the input was well-formed but violates a domain rule.
    /// Produced by the validation helpers core owns ([Verify], the built-in value objects, and
    /// [org.pragmatica.lang.utils.Causes#validation]). Carries no behaviour; an HTTP boundary may map it
    /// to 422. A cause without the marker is never inferred to be a validation failure.
    interface Validation extends Cause {}

    /// A cause wrapping an underlying cause. The `origin` component of the implementing record
    /// supplies [#source()]; the component cannot be named `source`, because the record accessor's
    /// return type (`Cause`) would clash with [#source()]'s (`Option<Cause>`).
    ///
    /// `source()` uses [Option#option] rather than [Option#some] deliberately: `some(null)` wraps a
    /// null without complaint, and a present-but-null source is strictly worse than an absent one.
    ///
    /// The wrapper reports its origin's classification: [#isTerminal()] and [#isTransient()] answer what the origin answers
    /// (`false` when there is no origin). A wrapper that needs a different answer overrides explicitly, as one that wraps an
    /// operation whose outcome is unknown must: that classification is the store's to get right, and a wrapper that
    /// inherits "transient" from a timeout would be retried by a transient-only policy. A wrapper that also implements
    /// [Terminal] or [Transient] must say which answer wins, by overriding (the compiler requires it).
    interface Wrapped extends Cause {
        Cause origin();

        @Override
        default Option<Cause> source() {
            return Option.option(origin());
        }

        @Override
        default boolean isTerminal() {
            return source().map(Cause::isTerminal)
                         .or(false);
        }

        @Override
        default boolean isTransient() {
            return source().map(Cause::isTransient)
                         .or(false);
        }

        /// The client text derives from the origin's: when the origin hides something (its client text differs from its
        /// message), the wrapper answers the origin's client text, because its own `message()` may have formatted what
        /// the origin hides; when the origin hides nothing, the wrapper's own `message()` is already safe and stays. A
        /// wrapper whose `message()` is fixed text that never mentions the origin overrides this with that text; one
        /// that formats its origin states the format over the origin's client text ([org.pragmatica.lang.utils.Causes#forOneCause]).
        @Override
        default String clientMessage() {
            return source().flatMap(Wrapped::hiddenOriginText)
                         .or(this::message);
        }

        private static Option<String> hiddenOriginText(Cause origin) {
            return Option.some(origin.clientMessage()).filter(text -> !text.equals(origin.message()));
        }
    }
}
