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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.json.spi.JsonCodecProvider;

import static org.pragmatica.lang.Option.option;


/// The registry behind [JsonCodecs#codecFor]: one answer per class, computed on the first lookup of that class and
/// kept in a `ClassValue`, so the answer lives and dies with the class itself and a discarded class loader is not
/// kept reachable by this cache.
///
/// Each class is resolved against the providers its own class loader sees (`type.getClassLoader()`, the system
/// class loader for a bootstrap class), so a type loaded by a child loader finds the registry shipped beside it.
sealed interface JsonCodecRegistry {
    /// A broken services file whose `hasNext` keeps failing without advancing must not hang the lookup; no real
    /// class path holds this many broken provider entries.
    int MAX_PROVIDER_FAILURES = 64;
    String SERVICES_ENTRY = "META-INF/services/" + JsonCodecProvider.class.getName();

    ClassValue<Result<Option<JsonTypeCodec<?>>>> CODECS = new ClassValue<>() {
        @Override
        protected Result<Option<JsonTypeCodec<?>>> computeValue(Class<?> type) {
            return lookup(type, ServiceLoader.load(JsonCodecProvider.class, loaderOf(type)));
        }
    };

    private static ClassLoader loaderOf(Class<?> type) {
        return option(type.getClassLoader()).or(ClassLoader::getSystemClassLoader);
    }

    /// `type`'s codec among `providers`: the codec when exactly one healthy provider lists it,
    /// [JsonCodecs.ConflictingProviders] when several do, empty when none does and every provider was read, and
    /// the providers' failure when none does but one could not be read (it may have been the one listing `type`).
    static Result<Option<JsonTypeCodec<?>>> lookup(Class<?> type, Iterable<JsonCodecProvider> providers) {
        return resolve(type, scan(providers.iterator()));
    }

    private static Result<Option<JsonTypeCodec<?>>> resolve(Class<?> type, Scan scan) {
        var found = scan.listings()
                        .stream()
                        .filter(listing -> listing.type() == type)
                        .toList();

        return switch (found.size()) {
            case 0 -> absent(scan.failures());
            case 1 -> Result.success(Option.some(found.getFirst().codec()));
            default -> JsonCodecs.ConflictingProviders.conflictingProviders(type,
                                                                            found.stream().map(Listing::provider).toList())
                                                      .result();
        };
    }

    private static Result<Option<JsonTypeCodec<?>>> absent(List<Cause> failures) {
        return switch (failures.size()) {
            case 0 -> Result.success(Option.none());
            case 1 -> failures.getFirst().result();
            default -> Causes.composite(failures.stream().map(Cause::result).toArray(Result<?>[]::new)).result();
        };
    }

    /// Reads every provider, isolating each: a services entry that cannot be loaded or instantiated (the iterator's
    /// `ServiceConfigurationError`), a `codecs()` that throws, and a `codecs()` that returns `null` each become one
    /// [JsonCodecs.ProviderFailed], and the providers after it are still read.
    private static Scan scan(Iterator<JsonCodecProvider> providers) {
        var listings = new ArrayList<Listing>();
        var failures = new ArrayList<Cause>();

        while (failures.size() < MAX_PROVIDER_FAILURES && hasNext(providers, failures)) {
            Result.lift(t -> JsonCodecs.ProviderFailed.providerFailed(SERVICES_ENTRY, t), providers::next)
                  .flatMap(JsonCodecRegistry::listings)
                  .onSuccess(listings::addAll)
                  .onFailure(failures::add);
        }

        return new Scan(List.copyOf(listings), List.copyOf(failures));
    }

    // FER: a failing hasNext is recorded as a ProviderFailed (so a later miss reports it) and the scan degrades
    // forward, because the entry it failed on has been consumed and asking again moves on to the next one;
    // MAX_PROVIDER_FAILURES bounds the walk should an iterator fail without advancing.
    private static boolean hasNext(Iterator<JsonCodecProvider> providers, List<Cause> failures) {
        return Result.lift(t -> JsonCodecs.ProviderFailed.providerFailed(SERVICES_ENTRY, t), providers::hasNext)
                     .onFailure(failures::add)
                     .or(true);
    }

    private static Result<List<Listing>> listings(JsonCodecProvider provider) {
        var name = provider.getClass().getName();

        return Result.lift(t -> JsonCodecs.ProviderFailed.providerFailed(name, t), provider::codecs)
                     .flatMap(codecs -> option(codecs).toResult(JsonCodecs.ProviderFailed.returnedNull(name)))
                     .map(codecs -> listings(name, codecs));
    }

    private static List<Listing> listings(String provider, Map<Class<?>, JsonTypeCodec<?>> codecs) {
        return codecs.entrySet()
                     .stream()
                     .map(entry -> new Listing(entry.getKey(),
                                               provider,
                                               entry.getValue()))
                     .toList();
    }

    record Listing(Class<?> type, String provider, JsonTypeCodec<?> codec) {}

    /// What one pass over the providers found: every listing of a healthy provider, and one failure per broken one.
    record Scan(List<Listing> listings, List<Cause> failures) {}

    record unused() implements JsonCodecRegistry {}
}
