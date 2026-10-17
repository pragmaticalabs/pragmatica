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

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.option;


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
/// entry fails that lookup with the `ServiceLoader`'s error and the next lookup tries again. When two providers
/// list the same class, the first one loaded wins.
public sealed interface JsonCodecs {
    /// The generated codec for `type`, or empty when `type` is not a `@Json` type on the class path.
    @SuppressWarnings("unchecked")
    static <T> Option<JsonTypeCodec<T>> codecFor(Class<T> type) {
        return option((JsonTypeCodec<T>) JsonCodecRegistry.CODECS.get().get(type));
    }

    record unused() implements JsonCodecs {}
}
