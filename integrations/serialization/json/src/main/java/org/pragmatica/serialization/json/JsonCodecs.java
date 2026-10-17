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

import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.utils.Causes.cause;


/// Lookup of generated codecs by class, for code that holds a `Class` rather than the generated `<Type>Json`
/// entry point (frameworks, generic adapters).
///
/// Lists exactly the types annotated with `@Json`. A record or enum reachable only as a component of one
/// (a nested record, an enum field) has codec code inside its root's generated `<Type>Json` but is not listed:
/// annotate it with `@Json` to make it a lookup target. Route body and response types that the slice
/// processor generates codecs for are not listed either; they are served by the slice's own router.
///
/// Providers are the per-package registries the codec processor generates. A class is resolved against the
/// providers its own class loader sees (`ServiceLoader.load(JsonCodecProvider.class, type.getClassLoader())`, the
/// system class loader for a bootstrap class), once per class: the answer, success or failure, is kept for the life
/// of the class, so a lookup never rescans the class path after the first one for that class.
///
/// A lookup never throws. A provider that cannot be read (a services entry the `ServiceLoader` cannot load or
/// instantiate, a `codecs()` that throws or returns `null`) is isolated: classes listed by the other providers still
/// resolve, and a class no healthy provider lists fails with that [ProviderFailed] rather than an empty success,
/// because the broken provider may have been the one listing it.
///
/// [ConflictingProviders] fires when two **differently named** providers both list the class: overlapping registries
/// (the same `@Json` type compiled into two artifacts alongside different other types of its package, so the
/// registries list different type sets), a registry generated with another `codec.registry.suffix`, or a
/// hand-written provider. Which copy wins must not depend on class-path order. Two artifacts carrying the **same**
/// registry (the same type set compiled twice: generated registry names are derived from the types they list) are
/// one provider to the `ServiceLoader`, which loads a provider name once; the lookup then succeeds with the copy the
/// class path finds first, which is the same code.
public sealed interface JsonCodecs {
    /// The generated codec for `type`: empty when `type` is not a `@Json` type its class loader sees, a
    /// [ConflictingProviders] failure when more than one provider lists it, a [ProviderFailed] failure when no
    /// healthy provider lists it and some provider could not be read.
    ///
    /// The cached answer itself is returned: a repeated lookup allocates nothing. The cast is sound because a
    /// provider maps each class to that class's codec.
    @SuppressWarnings("unchecked")
    static <T> Result<Option<JsonTypeCodec<T>>> codecFor(Class<T> type) {
        return (Result<Option<JsonTypeCodec<T>>>)(Result<?>) JsonCodecRegistry.CODECS.get(type);
    }

    /// More than one provider on the class path lists `type`.
    record ConflictingProviders(String type, List<String> providers, String message) implements Cause {
        static ConflictingProviders conflictingProviders(Class<?> type, List<String> providers) {
            return new ConflictingProviders(type.getName(),
                                            providers,
                                            "JSON codec for " + type.getName()
                                           + " is listed by more than one provider: " + String.join(", ", providers));
        }
    }

    /// A provider could not be read: `provider` names its class, or the services entry when the class itself could
    /// not be loaded; `origin` is what went wrong.
    record ProviderFailed(String provider, Cause origin, String message) implements Cause.Wrapped {
        static ProviderFailed providerFailed(String provider, Throwable failure) {
            return new ProviderFailed(provider,
                                      Causes.fromThrowable(failure),
                                      "JSON codec provider " + provider + " cannot be read: " + failure);
        }

        static ProviderFailed providerFailed(String provider, String reason) {
            return new ProviderFailed(provider,
                                      cause(reason),
                                      "JSON codec provider " + provider + " cannot be read: " + reason);
        }
    }

    record unused() implements JsonCodecs {}
}
