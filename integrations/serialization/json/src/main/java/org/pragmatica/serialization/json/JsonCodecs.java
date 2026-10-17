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


/// Lookup of generated codecs by class, for code that holds a `Class` rather than the generated `<Type>Json`
/// entry point (frameworks, generic adapters).
///
/// Lists exactly the types annotated with `@Json`. A record or enum reachable only as a component of one
/// (a nested record, an enum field) has codec code inside its root's generated `<Type>Json` but is not listed:
/// annotate it with `@Json` to make it a lookup target. Route body and response types that the slice
/// processor generates codecs for are not listed either; they are served by the slice's own router.
///
/// Providers are the per-package registries the codec processor generates, loaded with
/// `ServiceLoader.load(JsonCodecProvider.class)` (the calling thread's context class loader) on the first lookup
/// and kept for the life of this class. The first lookup that completes publishes the map; a broken provider
/// entry fails that lookup with the `ServiceLoader`'s error and the next lookup tries again. A class listed by more
/// than one provider (the same `@Json` type compiled into two artifacts, or a shaded copy) is a class-path conflict:
/// its lookup fails with [ConflictingProviders] naming them, instead of the first one loaded silently winning.
public sealed interface JsonCodecs {
    /// The generated codec for `type`: empty when `type` is not a `@Json` type on the class path, a
    /// [ConflictingProviders] failure when more than one provider lists it.
    @SuppressWarnings("unchecked")
    static <T> Result<Option<JsonTypeCodec<T>>> codecFor(Class<T> type) {
        return Option.option(JsonCodecRegistry.CODECS.get().get(type))
                     .map(found -> found.map(codec -> (JsonTypeCodec<T>) codec))
                     .fold(() -> Result.success(Option.none()),
                           found -> found.map(Option::some));
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

    record unused() implements JsonCodecs {}
}
