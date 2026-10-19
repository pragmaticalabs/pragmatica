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

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;
import java.util.stream.Stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.json.spi.GeneratedFor;
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
/// class loader for a bootstrap class), so a type loaded by a child loader finds the provider shipped beside it. Only
/// the provider whose binary name is the class's entry point name ([#providerName]) is instantiated, and the scan of
/// the providers stops at it: a hit loads the provider classes listed up to it, a miss loads them all, once per class.
sealed interface JsonCodecRegistry {
    /// A services iterator consumes the entry it fails on, so a scan always advances; this bound only guarantees that
    /// an iterator which failed without advancing could not hang a lookup. No class path holds this many providers.
    int MAX_PROVIDER_STEPS = 1 << 20;
    String SERVICES_ENTRY = "META-INF/services/" + JsonCodecProvider.class.getName();

    ClassValue<Result<Option<JsonTypeCodec<?>>>> CODECS = new ClassValue<>() {
        @Override
        protected Result<Option<JsonTypeCodec<?>>> computeValue(Class<?> type) {
            return lookup(type, loaderOf(type));
        }
    };

    private static ClassLoader loaderOf(Class<?> type) {
        return option(type.getClassLoader()).or(ClassLoader::getSystemClassLoader);
    }

    /// `type`'s codec among the providers `loader` sees; see [JsonCodecs] for the answers.
    static Result<Option<JsonTypeCodec<?>>> lookup(Class<?> type, ClassLoader loader) {
        return Result.lift(t -> JsonCodecs.ProviderFailed.providerFailed("for " + type.getName(),
                                                                         t),
                           () -> providerName(type))
                     .flatMap(name -> lookup(type,
                                             name,
                                             () -> ServiceLoader.load(JsonCodecProvider.class, loader)
                                                                .stream()
                                                                .iterator(),
                                             () -> isListed(loader, name)));
    }

    /// `type`'s codec among `providers`, the provider of `name` being the one that may serve it; the providers after
    /// the first one of that name are never streamed. `listed` tells whether the services files name `name`; it is
    /// asked only when no provider of that name was streamed but some entry failed to load. Obtaining the iterator is
    /// guarded too; its failure is the answer.
    static Result<Option<JsonTypeCodec<?>>> lookup(Class<?> type,
                                                   String name,
                                                   Iterable<Provider<JsonCodecProvider>> providers,
                                                   Listing listed) {
        return Result.lift(t -> JsonCodecs.ProviderFailed.providerFailed(SERVICES_ENTRY, t),
                           providers::iterator)
                     .flatMap(iterator -> resolve(type,
                                                  name,
                                                  scan(iterator, name),
                                                  listed));
    }

    /// Whether the services files name a provider; may fail reading them.
    @FunctionalInterface
    interface Listing {
        Result<Boolean> isListed();
    }

    // The first provider of the name is the one: one class loader's ServiceLoader yields one class per provider name
    // (it skips a name already streamed), so no later candidate could disagree, and the scan has stopped at it.
    private static Result<Option<JsonTypeCodec<?>>> resolve(Class<?> type, String name, Scan scan, Listing listed) {
        return scan.match()
                   .map(candidate -> instantiate(type, candidate))
                   .or(() -> absent(name,
                                    scan.failures(),
                                    listed));
    }

    // No provider of the name was streamed: absent, unless some entry failed to load and the services files list the
    // name, in which case the failed entry may be that provider.
    private static Result<Option<JsonTypeCodec<?>>> absent(String name, List<Cause> failures, Listing listed) {
        return failures.isEmpty()
               ? success(none())
               : listed.isListed()
                       .flatMap(isListed -> unloadable(name, failures, isListed));
    }

    private static Result<Option<JsonTypeCodec<?>>> unloadable(String name, List<Cause> failures, boolean isListed) {
        return isListed
               ? JsonCodecs.ProviderFailed.providerFailed(name,
                                                          "it is listed in " + SERVICES_ENTRY + " but cannot be loaded",
                                                          originOf(failures)).result()
               : success(none());
    }

    private static Cause originOf(List<Cause> failures) {
        return failures.size() == 1
               ? failures.getFirst()
               : Causes.composite(failures.stream().map(Cause::result).toArray(Result<?>[]::new));
    }

    // The class is checked before the constructor runs: a provider of the name that was not generated for `type` is
    // never instantiated.
    private static Result<Option<JsonTypeCodec<?>>> instantiate(Class<?> type, Candidate candidate) {
        return generatedFor(type, candidate).flatMap(_ -> create(candidate))
                           .flatMap(provider -> served(type,
                                                       candidate.name(),
                                                       provider));
    }

    private static Result<GeneratedFor> generatedFor(Class<?> type, Candidate candidate) {
        return Result.lift(t -> JsonCodecs.ProviderFailed.providerFailed(candidate.name(),
                                                                         t),
                           () -> option(candidate.type().getAnnotation(GeneratedFor.class)))
                     .flatMap(marker -> marker.filter(found -> isFor(found, type))
                                              .toResult(JsonCodecs.ProviderFailed.providerFailed(candidate.name(),
                                                                                                 "its class is not marked as generated for " + type.getName())));
    }

    // The marker names the type as the processor saw it: its canonical name.
    private static boolean isFor(GeneratedFor marker, Class<?> type) {
        return option(type.getCanonicalName()).filter(marker.value()::equals)
                     .isPresent();
    }

    private static Result<JsonCodecProvider> create(Candidate candidate) {
        return Result.lift(t -> JsonCodecs.ProviderFailed.providerFailed(candidate.name(), t),
                           () -> candidate.provider()
                                          .get());
    }

    // Every call into the provider instance is guarded, and a null or foreign answer makes the provider broken: a
    // codec is returned only when the provider serves exactly `type`.
    private static Result<Option<JsonTypeCodec<?>>> served(Class<?> type, String name, JsonCodecProvider provider) {
        return Result.lift(t -> JsonCodecs.ProviderFailed.providerFailed(name, t),
                           provider::type)
                     .filter(JsonCodecs.ProviderFailed.providerFailed(name,
                                                                      "type() does not return " + type.getName()),
                             served -> served == type)
                     .flatMap(_ -> Result.lift(t -> JsonCodecs.ProviderFailed.providerFailed(name, t),
                                               provider::codec))
                     .flatMap(codec -> option(codec).toResult(JsonCodecs.ProviderFailed.providerFailed(name,
                                                                                                       "codec() returned null")))
                     .map(Option::some);
    }

    /// Streams the providers up to the first one named `name`, isolating each step: an entry the `ServiceLoader`
    /// cannot load (its `ServiceConfigurationError`), and a provider whose class cannot be named, each become one
    /// recorded failure, and the entries after it are still streamed. The stream is sequential and lazy and
    /// `findFirst` ends it, so no provider after the match is asked for, loaded or named. Nothing is instantiated here.
    /// A `VirtualMachineError` (an exhausted stack or heap) raised while the `ServiceLoader` loads a provider class is
    /// not isolated: it propagates, as `Result.lift` rethrows it.
    private static Scan scan(Iterator<Provider<JsonCodecProvider>> providers, String name) {
        var failures = new ArrayList<Cause>();
        var match = Stream.generate(() -> step(providers))
                          .limit(MAX_PROVIDER_STEPS)
                          .takeWhile(Option::isPresent)
                          .flatMap(Option::stream)
                          .flatMap(step -> candidateOf(step, failures).stream())
                          .filter(candidate -> candidate.name()
                                                        .equals(name))
                          .findFirst();

        return new Scan(Option.from(match), List.copyOf(failures));
    }

    // FER: a failed step does not end the scan; it is recorded, and the recorded failures are consulted only when no
    // provider of the requested name turns up (absent), so a broken entry of another name cannot change a hit.
    private static Option<Candidate> candidateOf(Result<Candidate> step, List<Cause> failures) {
        return step.onFailure(failures::add)
                   .option();
    }

    // FER: a failing hasNext becomes a failed step (consulted if the lookup finds no provider of its name) and the scan
    // degrades forward: the entry it failed on has been consumed, so asking again moves on to the next one. Empty ends
    // the scan.
    private static Option<Result<Candidate>> step(Iterator<Provider<JsonCodecProvider>> providers) {
        return Result.lift(t -> JsonCodecs.ProviderFailed.providerFailed(SERVICES_ENTRY, t),
                           providers::hasNext)
                     .fold(cause -> some(cause.result()),
                           more -> nextIf(more, providers));
    }

    private static Option<Result<Candidate>> nextIf(boolean more, Iterator<Provider<JsonCodecProvider>> providers) {
        return more
               ? some(next(providers))
               : none();
    }

    private static Result<Candidate> next(Iterator<Provider<JsonCodecProvider>> providers) {
        return Result.lift(t -> JsonCodecs.ProviderFailed.providerFailed(SERVICES_ENTRY, t),
                           () -> Candidate.candidate(providers.next()));
    }

    /// The binary name of `type`'s generated entry point, the provider that serves it: `type`'s package, then its flat
    /// name, then `Json`. The flat name is the codec processor's (`JsonNames`): the simple names of the type and its
    /// declaring classes, outermost first, each with `_` written as `_0` and `$` as `_1`, joined with `_`.
    /// A class in the unnamed package gets the bare name. The processor refuses `@Json` there, so no generated provider
    /// carries such a name and the lookup of that class is a miss; the branch only keeps the name defined for every
    /// class.
    static String providerName(Class<?> type) {
        var flat = String.join("_",
                               Stream.<Class<?>> iterate(type, Objects::nonNull, Class::getDeclaringClass)
                                     .map(level -> escape(level.getSimpleName()))
                                     .toList()
                                     .reversed());
        var packageName = type.getPackageName();

        return packageName.isEmpty()
               ? flat + "Json"
               : packageName + "." + flat + "Json";
    }

    private static String escape(String simpleName) {
        return simpleName.replace("_", "_0")
                         .replace("$", "_1");
    }

    // Whether any services file `loader` sees names `name`, parsed as the ServiceLoader does: a `#` starts a comment,
    // surrounding whitespace is ignored.
    private static Result<Boolean> isListed(ClassLoader loader, String name) {
        return Result.lift(t -> JsonCodecs.ProviderFailed.providerFailed(SERVICES_ENTRY, t),
                           () -> Collections.list(loader.getResources(SERVICES_ENTRY)))
                     .flatMap(JsonCodecRegistry::listedNames)
                     .map(names -> names.contains(name));
    }

    private static Result<List<String>> listedNames(List<URL> files) {
        return Result.allOf(files.stream().map(JsonCodecRegistry::readNames).toList()).map(JsonCodecRegistry::flatten);
    }

    private static List<String> flatten(List<List<String>> files) {
        return files.stream()
                    .flatMap(List::stream)
                    .toList();
    }

    private static Result<List<String>> readNames(URL file) {
        return Result.lift(t -> JsonCodecs.ProviderFailed.providerFailed(SERVICES_ENTRY, t),
                           () -> read(file))
                     .map(JsonCodecRegistry::parseNames);
    }

    private static List<String> parseNames(String text) {
        return text.lines()
                   .map(JsonCodecRegistry::uncommented)
                   .filter(line -> !line.isEmpty())
                   .toList();
    }

    private static String uncommented(String line) {
        var comment = line.indexOf('#');

        return (comment < 0
                ? line
                : line.substring(0, comment)).strip();
    }

    // The JDK boundary: reading a services file throws IOException, which the one caller lifts into a Result.
    // Uncached, as the ServiceLoader reads it: a cached jar connection would keep the jar file open.
    @SuppressWarnings("JBCT-EX-01")
    private static String read(URL file) throws IOException {
        var connection = file.openConnection();

        connection.setUseCaches(false);
        try (var in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /// What a scan found: the first provider of the requested name, if any, and the failures recorded before it.
    record Scan(Option<Candidate> match, List<Cause> failures) {}

    /// One streamed provider: its class and that class's binary name, read without instantiating it.
    record Candidate(String name, Class<? extends JsonCodecProvider> type, Provider<JsonCodecProvider> provider) {
        static Candidate candidate(Provider<JsonCodecProvider> provider) {
            return new Candidate(provider.type().getName(),
                                 provider.type(),
                                 provider);
        }
    }

    record unused() implements JsonCodecRegistry {}
}
