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

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.json.spi.JsonCodecProvider;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// The registry behind [JsonCodecs#codecFor]: one answer per class, computed on the first lookup of that class and
/// kept in a `ClassValue`, so the answer lives and dies with the class itself and a discarded class loader is not
/// kept reachable by this cache.
///
/// Each class is resolved against the providers its own class loader sees (`type.getClassLoader()`, the system
/// class loader for a bootstrap class), so a type loaded by a child loader finds the registry shipped beside it.
sealed interface JsonCodecRegistry {
    /// A services iterator consumes the entry it fails on, so a scan always advances; this bound only guarantees that
    /// an iterator which failed without advancing could not hang a lookup. No class path holds this many providers.
    int MAX_PROVIDER_STEPS = 1<< 16;
    String SERVICES_ENTRY = "META-INF/services/" + JsonCodecProvider.class.getName();

    ClassValue<Result<Option<JsonTypeCodec<?>>>> CODECS = new ClassValue<>() {
        @Override
        protected Result<Option<JsonTypeCodec<?>>> computeValue(Class<?> type) {
            return lookup(type,
                          () -> ServiceLoader.load(JsonCodecProvider.class, loaderOf(type)).iterator());
        }
    };

    private static ClassLoader loaderOf(Class<?> type) {
        return option(type.getClassLoader()).or(ClassLoader::getSystemClassLoader);
    }

    /// `type`'s codec among `providers`: the codec when exactly one healthy provider lists it,
    /// [JsonCodecs.ConflictingProviders] when several do, empty when none does and every provider was read, and
    /// the providers' failure when none does but one could not be read (it may have been the one listing `type`).
    /// Obtaining the iterator (for the registry: loading the services) is guarded too; its failure is the answer.
    static Result<Option<JsonTypeCodec<?>>> lookup(Class<?> type, Iterable<JsonCodecProvider> providers) {
        return Result.lift(t -> JsonCodecs.ProviderFailed.providerFailed(SERVICES_ENTRY, t),
                           providers::iterator)
                     .flatMap(iterator -> resolve(type,
                                                  scan(iterator)));
    }

    private static Result<Option<JsonTypeCodec<?>>> resolve(Class<?> type, List<Result<List<Listing>>> scan) {
        var found = scan.stream()
                        .flatMap(outcome -> outcome.option()
                                                   .stream())
                        .flatMap(List::stream)
                        .filter(listing -> listing.type() == type)
                        .toList();

        return switch (found.size()) {
            case 0 -> absent(scan.stream().flatMap(outcome -> failureOf(outcome).stream()).toList());
            case 1 -> success(some(found.getFirst().codec()));
            default -> JsonCodecs.ConflictingProviders.conflictingProviders(type,
                                                                            found.stream().map(Listing::provider).toList()).result();
        };
    }

    private static Option<Cause> failureOf(Result<?> outcome) {
        return outcome.fold(Option::some, _ -> none());
    }

    private static Result<Option<JsonTypeCodec<?>>> absent(List<Cause> failures) {
        return switch (failures.size()) {
            case 0 -> success(none());
            case 1 -> failures.getFirst().result();
            default -> Causes.composite(failures.stream().map(Cause::result).toArray(Result<?>[]::new)).result();
        };
    }

    /// Reads every provider, isolating each: a services entry that cannot be loaded or instantiated (the iterator's
    /// `ServiceConfigurationError`), a `codecs()` that throws or returns `null`, a map that throws while it is read,
    /// and a map listing a `null` class or codec each become one failed outcome carrying a
    /// [JsonCodecs.ProviderFailed], and the providers after it are still read. A `VirtualMachineError` (an exhausted
    /// stack or heap) is not isolated: it propagates, as `Result.lift` rethrows it.
    private static List<Result<List<Listing>>> scan(Iterator<JsonCodecProvider> providers) {
        return Stream.generate(() -> step(providers))
                     .limit(MAX_PROVIDER_STEPS)
                     .takeWhile(Option::isPresent)
                     .flatMap(Option::stream)
                     .toList();
    }

    // FER: a failing hasNext becomes a failed outcome (so a later miss reports it) and the scan degrades forward:
    // the entry it failed on has been consumed, so asking again moves on to the next one. Empty ends the scan.
    private static Option<Result<List<Listing>>> step(Iterator<JsonCodecProvider> providers) {
        return Result.lift(t -> JsonCodecs.ProviderFailed.providerFailed(SERVICES_ENTRY, t),
                           providers::hasNext)
                     .fold(cause -> some(cause.result()),
                           more -> nextIf(more, providers));
    }

    private static Option<Result<List<Listing>>> nextIf(boolean more, Iterator<JsonCodecProvider> providers) {
        return more
               ? some(next(providers))
               : none();
    }

    private static Result<List<Listing>> next(Iterator<JsonCodecProvider> providers) {
        return Result.lift(t -> JsonCodecs.ProviderFailed.providerFailed(SERVICES_ENTRY, t),
                           providers::next)
                     .flatMap(JsonCodecRegistry::listings);
    }

    // Every interaction with the provider's map (entrySet, its iterator, each key and value) is inside a guarded call,
    // so a map that throws while it is read fails this provider only.
    private static Result<List<Listing>> listings(JsonCodecProvider provider) {
        var name = provider.getClass().getName();

        return Result.lift(t -> JsonCodecs.ProviderFailed.providerFailed(name, t),
                           provider::codecs)
                     .flatMap(codecs -> option(codecs).toResult(JsonCodecs.ProviderFailed.providerFailed(name,
                                                                                                         "codecs() returned null")))
                     .flatMap(codecs -> Result.lift(t -> JsonCodecs.ProviderFailed.providerFailed(name, t),
                                                    () -> listings(name, codecs)))
                     .flatMap(listings -> nullFree(name, listings));
    }

    // A null class or codec makes the provider broken rather than a source of listings: a null codec must never reach
    // a caller as a found one.
    private static Result<List<Listing>> nullFree(String provider, List<Listing> listings) {
        return listings.stream()
                       .allMatch(Listing::isComplete)
               ? success(listings)
               : JsonCodecs.ProviderFailed.providerFailed(provider, "codecs() lists a null class or codec").result();
    }

    private static List<Listing> listings(String provider, Map<Class<?>, JsonTypeCodec<?>> codecs) {
        return codecs.entrySet()
                     .stream()
                     .map(entry -> Listing.listing(entry.getKey(),
                                                   provider,
                                                   entry.getValue()))
                     .toList();
    }

    record Listing(Class<?> type, String provider, JsonTypeCodec<?> codec) {
        static Listing listing(Class<?> type, String provider, JsonTypeCodec<?> codec) {
            return new Listing(type, provider, codec);
        }

        // The class and codec come from a provider's map, outside this code's control, so either may be null.
        boolean isComplete() {
            return option(type).isPresent() && option(codec).isPresent();
        }
    }

    record unused() implements JsonCodecRegistry {}
}
