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
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.pragmatica.lang.Lazy;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.json.spi.JsonCodecProvider;


/// The registry behind [JsonCodecs#codecFor]: every provider's codecs, loaded once, on first use. `Lazy`
/// publishes the map safely and does not cache a failed load.
sealed interface JsonCodecRegistry {
    Lazy<Map<Class<?>, Result<JsonTypeCodec<?>>>> CODECS = Lazy.lazy(JsonCodecRegistry::load);

    private static Map<Class<?>, Result<JsonTypeCodec<?>>> load() {
        return index(ServiceLoader.load(JsonCodecProvider.class));
    }

    /// Each listed class with its codec, or with a [JsonCodecs.ConflictingProviders] failure when more than one
    /// provider lists it: which copy wins must not depend on class-path order.
    static Map<Class<?>, Result<JsonTypeCodec<?>>> index(Iterable<JsonCodecProvider> providers) {
        return StreamSupport.stream(providers.spliterator(),
                                    false)
                            .flatMap(JsonCodecRegistry::listings)
                            .collect(Collectors.groupingBy(Listing::type))
                            .entrySet()
                            .stream()
                            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                                                                  entry -> resolve(entry.getKey(),
                                                                                   entry.getValue())));
    }

    private static Stream<Listing> listings(JsonCodecProvider provider) {
        return provider.codecs()
                       .entrySet()
                       .stream()
                       .map(entry -> new Listing(entry.getKey(),
                                                 provider.getClass().getName(),
                                                 entry.getValue()));
    }

    private static Result<JsonTypeCodec<?>> resolve(Class<?> type, List<Listing> found) {
        return found.size() == 1
               ? Result.success(found.getFirst().codec())
               : JsonCodecs.ConflictingProviders.conflictingProviders(type,
                                                                      found.stream().map(Listing::provider).toList())
                                                .result();
    }

    record Listing(Class<?> type, String provider, JsonTypeCodec<?> codec) {}

    record unused() implements JsonCodecRegistry {}
}
