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
package org.pragmatica.serialization.json;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.utils.Causes.cause;


/// Lookup of generated codecs by class, for code that holds a `Class` rather than the generated `<Type>Json`
/// entry point (frameworks, generic adapters).
///
/// Serves exactly the types annotated with `@Json`. Each one's generated entry point `<Flat>Json` is its own
/// `ServiceLoader` provider ([org.pragmatica.serialization.json.spi.JsonCodecProvider]), listed in
/// `META-INF/services/org.pragmatica.serialization.json.spi.JsonCodecProvider`. A record or enum reachable only as a
/// component of one (a nested record, an enum field) has codec code inside its root's entry point but no provider of
/// its own: annotate it with `@Json` to make it a lookup target. Route body and response types that the slice
/// processor generates codecs for have no provider either; they are served by the slice's own router.
///
/// A lookup derives the provider's binary name from the class (the entry point naming of the codec processor:
/// package, then the flat name, then `Json`), streams the providers of the class's own loader
/// (`ServiceLoader.load(JsonCodecProvider.class, type.getClassLoader())`, the system class loader for a bootstrap
/// class) without instantiating them, and instantiates only the provider of that name, after checking that its class
/// carries a `GeneratedFor` marker naming the class. The instance must then serve exactly the requested class with a
/// non-null codec. The answer, success or failure, is computed once per class and kept for the life of the class, so a
/// lookup never rescans the class path after the first one for that class.
///
/// The same type compiled into two artifacts gives the same provider name in both, and the `ServiceLoader` loads a
/// provider name once, so the lookup resolves to the copy the class path finds first, which is the same code. Two
/// artifacts holding different types of one package each list their own providers, so both resolve.
///
/// A lookup never throws, except a `VirtualMachineError` (an exhausted stack or heap), which deliberately propagates:
/// no `Cause` can carry it. It fails in two cases:
///
/// - [ProviderFailed]: the provider of the requested name is broken. Either its class was found and fails the checks
///   above or cannot be instantiated (a constructor or initialiser that throws, a `type()` or `codec()` that throws,
///   returns `null` or names another class), or the services files list the name but the `ServiceLoader` could not
///   load it (the class is missing, does not implement the interface, or has no public no-argument constructor). A
///   broken entry of **another** name does not change the answer: the `ServiceLoader` reports such an entry while it
///   is streamed, before its name is known, so the lookup records it, streams on, and consults it only when no
///   provider of the requested name was found and the services files list that name (then the recorded failures,
///   which include the requested entry's, are the failure's origin). Building the failure never throws: an
///   exception's cause chain is walked to a bounded depth and stops at a throwable seen before, and an exception
///   whose own `toString` throws is described by its class name.
/// - [ConflictingProviders]: the providers streamed for the class include more than one distinct provider class of the
///   requested name, so which one serves it would depend on load order. A single class loader's `ServiceLoader`
///   yields one class per provider name, so this needs providers gathered across class loaders (a hand-written
///   provider defined by a second loader under the generated name); it is refused rather than resolved first-wins.
public sealed interface JsonCodecs {
    /// The generated codec for `type`: empty when `type` is not a `@Json` type its class loader sees, a
    /// [ProviderFailed] failure when its provider is broken, a [ConflictingProviders] failure when more than one
    /// provider class claims it.
    ///
    /// The cached answer itself is returned: a repeated lookup allocates nothing. The cast is sound because a
    /// provider is used only when it serves exactly `type`.
    @SuppressWarnings("unchecked")
    static <T> Result<Option<JsonTypeCodec<T>>> codecFor(Class<T> type) {
        return (Result<Option<JsonTypeCodec<T>>>)(Result<?>) JsonCodecRegistry.CODECS.get(type);
    }

    /// More than one provider class of `type`'s provider name was found; `providers` describes each one (its name and
    /// the identity of its class loader).
    record ConflictingProviders(String type, List<String> providers, String message) implements Cause {
        static ConflictingProviders conflictingProviders(Class<?> type, List<String> providers) {
            return new ConflictingProviders(type.getName(),
                                            providers,
                                            "JSON codec for " + type.getName()
                                           + " is claimed by more than one provider class: " + String.join(", ",
                                                                                                           providers));
        }
    }

    /// A provider could not be used: `provider` names its class, or the services entry when the failure is not one
    /// provider's; `origin` is what went wrong.
    record ProviderFailed(String provider, Cause origin, String message) implements Cause.Wrapped {
        /// How many levels of a throwable's cause chain a failure records; deeper levels are not walked.
        static final int MAX_CAUSE_DEPTH = 32;

        static ProviderFailed providerFailed(String provider, Throwable failure) {
            return new ProviderFailed(provider,
                                      originOf(failure),
                                      "JSON codec provider " + provider + " cannot be used: " + describe(failure));
        }

        static ProviderFailed providerFailed(String provider, String reason) {
            return providerFailed(provider, reason, cause(reason));
        }

        static ProviderFailed providerFailed(String provider, String reason, Cause origin) {
            return new ProviderFailed(provider, origin, "JSON codec provider " + provider + " cannot be used: " + reason);
        }

        // FER: printing a throwable runs its own toString and getMessage, which may throw; such a throwable degrades
        // to its class name, which cannot fail, so building this failure never throws. The class name is the
        // diagnosis that survives.
        private static String describe(Throwable failure) {
            return Option.lift(failure::toString).or(() -> failure.getClass()
                                                                  .getName());
        }

        // The cause chain is walked here, never by printStackTrace (as Causes.fromThrowable does): getCause is the
        // throwable's own code and may return the throwable itself, a cycle, or a fresh throwable on every call, which
        // an unbounded walk follows into a hang or an exhausted stack. One level per throwable, outermost first.
        private static Cause originOf(Throwable failure) {
            return chainCause(chainOf(failure), 0);
        }

        private static Cause chainCause(List<Throwable> chain, int index) {
            return cause(levelText(chain.get(index)),
                         index + 1 < chain.size()
                         ? some(chainCause(chain, index + 1))
                         : none());
        }

        // The chain stops at MAX_CAUSE_DEPTH levels, at the first throwable already in it (by identity), or where
        // getCause returns null or throws. The stream is sequential and lazy: each level's getCause runs only after
        // the level before it was added, so the identity check sees every earlier level.
        private static List<Throwable> chainOf(Throwable failure) {
            var chain = new ArrayList<Throwable>();

            Stream.iterate(some(failure),
                           level -> level.filter(throwable -> isNew(chain, throwable))
                                         .isPresent(),
                           level -> level.flatMap(ProviderFailed::causeOf))
                  .limit(MAX_CAUSE_DEPTH)
                  .forEach(level -> level.onPresent(chain::add));

            return chain;
        }

        private static boolean isNew(List<Throwable> chain, Throwable throwable) {
            return chain.stream()
                        .noneMatch(seen -> seen == throwable);
        }

        // FER: a getCause that throws ends the chain at this level; the levels already walked are the diagnosis.
        private static Option<Throwable> causeOf(Throwable level) {
            return Option.lift(level::getCause);
        }

        // FER: as describe; a getStackTrace that throws leaves the level without frames.
        private static String levelText(Throwable level) {
            return describe(level) + Option.lift(level::getStackTrace)
                                           .map(ProviderFailed::frames)
                                           .or("");
        }

        private static String frames(StackTraceElement[] trace) {
            return Arrays.stream(trace)
                         .map(frame -> "\n\tat " + frame)
                         .collect(Collectors.joining());
        }
    }

    record unused() implements JsonCodecs {}
}
