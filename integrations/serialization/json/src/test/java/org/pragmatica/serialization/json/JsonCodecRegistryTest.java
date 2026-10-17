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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.json.spi.JsonCodecProvider;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// A class listed by two providers is a class-path conflict: its lookup fails naming both, whatever the load order,
/// while classes listed once keep resolving. A provider that cannot be read is isolated: the others still resolve,
/// and a class nobody healthy lists fails with the broken provider's cause instead of an empty success.
public class JsonCodecRegistryTest {
    record Shared() {}

    record OnlyFirst() {}

    record OnlySecond() {}

    record Unlisted() {}

    static final JsonTypeCodec<Object> FIRST_CODEC = JsonTypeCodec.jsonTypeCodec((out, value) -> out.writeNull(), in -> null);
    static final JsonTypeCodec<Object> SECOND_CODEC = JsonTypeCodec.jsonTypeCodec((out, value) -> out.writeNull(), in -> null);

    public static final class FirstProvider implements JsonCodecProvider {
        @Override
        public Map<Class<?>, JsonTypeCodec<?>> codecs() {
            return Map.of(Shared.class, FIRST_CODEC, OnlyFirst.class, FIRST_CODEC);
        }
    }

    public static final class SecondProvider implements JsonCodecProvider {
        @Override
        public Map<Class<?>, JsonTypeCodec<?>> codecs() {
            return Map.of(Shared.class, SECOND_CODEC, OnlySecond.class, SECOND_CODEC);
        }
    }

    public static final class ThrowingProvider implements JsonCodecProvider {
        @Override
        public Map<Class<?>, JsonTypeCodec<?>> codecs() {
            throw new IllegalStateException("registry initialisation failed");
        }
    }

    public static final class NullProvider implements JsonCodecProvider {
        @Override
        public Map<Class<?>, JsonTypeCodec<?>> codecs() {
            return null;
        }
    }

    @Test
    void classListedByTwoProviders_failsNamingBoth_inEitherLoadOrder() {
        for (var order : List.of(List.<JsonCodecProvider>of(new FirstProvider(), new SecondProvider()),
                                 List.<JsonCodecProvider>of(new SecondProvider(), new FirstProvider()))) {
            var conflict = failureOf(JsonCodecRegistry.lookup(Shared.class, order));
            var providers = assertInstanceOf(JsonCodecs.ConflictingProviders.class, conflict).providers();

            assertEquals(2, providers.size(), providers.toString());
            assertEquals(Set.of(FirstProvider.class.getName(), SecondProvider.class.getName()),
                         Set.copyOf(providers));
        }
    }

    @Test
    void classesListedOnce_stillResolve_besideAConflict() {
        var providers = List.<JsonCodecProvider>of(new FirstProvider(), new SecondProvider());

        assertSame(FIRST_CODEC, JsonCodecRegistry.lookup(OnlyFirst.class, providers).unwrap().unwrap());
        assertSame(SECOND_CODEC, JsonCodecRegistry.lookup(OnlySecond.class, providers).unwrap().unwrap());
    }

    @Test
    void lookup_isEmpty_forAnUnlistedClass_whenEveryProviderWasRead() {
        assertEquals(Result.success(Option.none()),
                     JsonCodecRegistry.lookup(Unlisted.class, List.of(new FirstProvider(), new SecondProvider())));
    }

    // A services entry naming a class that does not exist: the real ServiceLoader iterator throws
    // ServiceConfigurationError for it, and the provider listed after it must still load.
    @Test
    void servicesEntryThatCannotBeLoaded_isIsolated_andAMissReportsIt(@TempDir Path dir) throws IOException {
        var services = dir.resolve(JsonCodecRegistry.SERVICES_ENTRY);

        Files.createDirectories(services.getParent());
        Files.writeString(services, "com.example.missing.NoSuchProvider\n" + FirstProvider.class.getName() + "\n");

        try (var loader = new URLClassLoader(new URL[] {dir.toUri().toURL()}, JsonCodecRegistryTest.class.getClassLoader())) {
            // A fresh ServiceLoader per lookup, as the registry does per class: a reused one replays only the
            // providers it instantiated and would never show the broken entry again.
            assertSame(FIRST_CODEC,
                       JsonCodecRegistry.lookup(OnlyFirst.class, ServiceLoader.load(JsonCodecProvider.class, loader))
                                        .unwrap()
                                        .unwrap());
            var failed = assertInstanceOf(JsonCodecs.ProviderFailed.class,
                                          failureOf(JsonCodecRegistry.lookup(Unlisted.class,
                                                                             ServiceLoader.load(JsonCodecProvider.class, loader))));

            assertEquals(JsonCodecRegistry.SERVICES_ENTRY, failed.provider());
            assertTrue(failed.message().contains("com.example.missing.NoSuchProvider"), failed.message());
            assertTrue(failed.source().isPresent());
        }
    }

    // The JDK's iterator defers a broken entry's error to next(), but the contract allows hasNext() to throw too
    // (a services file it cannot parse): that failure is recorded and the providers after it are still read.
    @Test
    void iteratorWhoseHasNextThrows_isIsolated_andAMissReportsIt() {
        Iterable<JsonCodecProvider> providers = () -> new HasNextFailsOnce(new FirstProvider());

        assertSame(FIRST_CODEC, JsonCodecRegistry.lookup(OnlyFirst.class, providers).unwrap().unwrap());
        var failed = assertInstanceOf(JsonCodecs.ProviderFailed.class,
                                      failureOf(JsonCodecRegistry.lookup(Unlisted.class, providers)));

        assertEquals(JsonCodecRegistry.SERVICES_ENTRY, failed.provider());
        assertTrue(failed.message().contains("unparseable services file"), failed.message());
    }

    /// Throws from the first hasNext(), as an iterator does for a services file it cannot parse, then yields `last`.
    static final class HasNextFailsOnce implements Iterator<JsonCodecProvider> {
        private final JsonCodecProvider last;
        private int calls;
        private boolean served;

        HasNextFailsOnce(JsonCodecProvider last) {
            this.last = last;
        }

        @Override
        public boolean hasNext() {
            if (calls++ == 0) {
                throw new ServiceConfigurationError("unparseable services file");
            }

            return !served;
        }

        @Override
        public JsonCodecProvider next() {
            served = true;

            return last;
        }
    }

    @Test
    void providerWhoseCodecsThrows_isIsolated_andAMissReportsIt() {
        var providers = List.<JsonCodecProvider>of(new ThrowingProvider(), new FirstProvider());

        assertSame(FIRST_CODEC, JsonCodecRegistry.lookup(OnlyFirst.class, providers).unwrap().unwrap());
        var failed = assertInstanceOf(JsonCodecs.ProviderFailed.class,
                                      failureOf(JsonCodecRegistry.lookup(Unlisted.class, providers)));

        assertEquals(ThrowingProvider.class.getName(), failed.provider());
        assertTrue(failed.message().contains("registry initialisation failed"), failed.message());
    }

    @Test
    void providerWhoseCodecsReturnsNull_isIsolated_andAMissReportsIt() {
        var providers = List.<JsonCodecProvider>of(new NullProvider(), new FirstProvider());

        assertSame(FIRST_CODEC, JsonCodecRegistry.lookup(OnlyFirst.class, providers).unwrap().unwrap());
        var failed = assertInstanceOf(JsonCodecs.ProviderFailed.class,
                                      failureOf(JsonCodecRegistry.lookup(Unlisted.class, providers)));

        assertEquals(NullProvider.class.getName(), failed.provider());
        assertTrue(failed.message().contains("returned null"), failed.message());
    }

    public static final class NullCodecProvider implements JsonCodecProvider {
        @Override
        public Map<Class<?>, JsonTypeCodec<?>> codecs() {
            var codecs = new HashMap<Class<?>, JsonTypeCodec<?>>();

            codecs.put(Unlisted.class, null);

            return codecs;
        }
    }

    public static final class NullClassProvider implements JsonCodecProvider {
        @Override
        public Map<Class<?>, JsonTypeCodec<?>> codecs() {
            var codecs = new HashMap<Class<?>, JsonTypeCodec<?>>();

            codecs.put(null, FIRST_CODEC);

            return codecs;
        }
    }

    // A map that fails while it is read: entrySet() itself, or a key read during iteration.
    public static final class BrokenMapProvider implements JsonCodecProvider {
        private final boolean failOnKey;

        BrokenMapProvider(boolean failOnKey) {
            this.failOnKey = failOnKey;
        }

        @Override
        public Map<Class<?>, JsonTypeCodec<?>> codecs() {
            return new AbstractMap<>() {
                @Override
                public Set<Entry<Class<?>, JsonTypeCodec<?>>> entrySet() {
                    if (!failOnKey) {
                        throw new IllegalStateException("entrySet failed");
                    }

                    return Set.of(new BrokenEntry());
                }
            };
        }
    }

    static final class BrokenEntry implements Map.Entry<Class<?>, JsonTypeCodec<?>> {
        @Override
        public Class<?> getKey() {
            throw new IllegalStateException("key read failed");
        }

        @Override
        public JsonTypeCodec<?> getValue() {
            return FIRST_CODEC;
        }

        @Override
        public JsonTypeCodec<?> setValue(JsonTypeCodec<?> value) {
            throw new UnsupportedOperationException();
        }
    }

    // An exception that cannot describe itself: its getMessage throws, so does every toString or stack trace print.
    static final class UnprintableException extends RuntimeException {
        @Override
        public String getMessage() {
            throw new IllegalStateException("getMessage failed");
        }
    }

    public static final class UnprintableProvider implements JsonCodecProvider {
        @Override
        public Map<Class<?>, JsonTypeCodec<?>> codecs() {
            throw new UnprintableException();
        }
    }

    public static final class StackOverflowProvider implements JsonCodecProvider {
        @Override
        public Map<Class<?>, JsonTypeCodec<?>> codecs() {
            throw new StackOverflowError("simulated");
        }
    }

    // A null codec is never a found codec: the provider is broken, the healthy one still resolves.
    @Test
    void providerListingANullCodec_isIsolated_andTheClassFailsInsteadOfResolvingToNull() {
        var providers = List.<JsonCodecProvider>of(new NullCodecProvider(), new FirstProvider());

        assertSame(FIRST_CODEC, JsonCodecRegistry.lookup(OnlyFirst.class, providers).unwrap().unwrap());
        var failed = assertInstanceOf(JsonCodecs.ProviderFailed.class,
                                      failureOf(JsonCodecRegistry.lookup(Unlisted.class, providers)));

        assertEquals(NullCodecProvider.class.getName(), failed.provider());
        assertTrue(failed.message().contains("null class or codec"), failed.message());
    }

    @Test
    void providerListingANullClass_isIsolated_andAMissReportsIt() {
        var providers = List.<JsonCodecProvider>of(new NullClassProvider(), new FirstProvider());

        assertSame(FIRST_CODEC, JsonCodecRegistry.lookup(OnlyFirst.class, providers).unwrap().unwrap());
        var failed = assertInstanceOf(JsonCodecs.ProviderFailed.class,
                                      failureOf(JsonCodecRegistry.lookup(Unlisted.class, providers)));

        assertEquals(NullClassProvider.class.getName(), failed.provider());
    }

    // entrySet() throwing, and a key read throwing mid-iteration: both fail that provider only, for every lookup.
    @Test
    void providerWhoseMapThrowsWhileRead_isIsolated_andAMissReportsIt() {
        for (var failOnKey : List.of(false, true)) {
            var providers = List.<JsonCodecProvider>of(new BrokenMapProvider(failOnKey), new FirstProvider());

            assertSame(FIRST_CODEC, JsonCodecRegistry.lookup(OnlyFirst.class, providers).unwrap().unwrap());
            var failed = assertInstanceOf(JsonCodecs.ProviderFailed.class,
                                          failureOf(JsonCodecRegistry.lookup(Unlisted.class, providers)));

            assertEquals(BrokenMapProvider.class.getName(), failed.provider());
            assertTrue(failed.message().contains(failOnKey
                                                 ? "key read failed"
                                                 : "entrySet failed"), failed.message());
        }
    }

    // Building the failure must not throw either: an exception that cannot print itself is named by its class.
    @Test
    void providerThrowingAnUnprintableException_isIsolated_andDescribedByItsClass() {
        var providers = List.<JsonCodecProvider>of(new UnprintableProvider(), new FirstProvider());

        assertSame(FIRST_CODEC, JsonCodecRegistry.lookup(OnlyFirst.class, providers).unwrap().unwrap());
        var failed = assertInstanceOf(JsonCodecs.ProviderFailed.class,
                                      failureOf(JsonCodecRegistry.lookup(Unlisted.class, providers)));

        assertTrue(failed.message().contains(UnprintableException.class.getName()), failed.message());
        assertTrue(failed.origin().message().contains(UnprintableException.class.getName()), failed.origin().message());
    }

    // Documented exception to "never throws": a VirtualMachineError is not a failure a Cause can carry.
    @Test
    void virtualMachineError_propagates() {
        var providers = List.<JsonCodecProvider>of(new StackOverflowProvider(), new FirstProvider());

        assertThrows(StackOverflowError.class, () -> JsonCodecRegistry.lookup(OnlyFirst.class, providers));
    }

    // Obtaining the iterator is guarded too: for the registry that is loading the services of the class's loader.
    @Test
    void providersWhoseIteratorCannotBeObtained_failTheLookup() {
        Iterable<JsonCodecProvider> providers = () -> {
            throw new ServiceConfigurationError("services cannot be loaded");
        };
        var failed = assertInstanceOf(JsonCodecs.ProviderFailed.class,
                                      failureOf(JsonCodecRegistry.lookup(OnlyFirst.class, providers)));

        assertEquals(JsonCodecRegistry.SERVICES_ENTRY, failed.provider());
        assertTrue(failed.message().contains("services cannot be loaded"), failed.message());
    }

    // The answer is computed once per class and kept: a second lookup is the same value, not a fresh scan.
    @Test
    void codecFor_answer_isCachedPerClass() {
        var first = JsonCodecs.codecFor(Unlisted.class);

        assertEquals(Result.success(Option.none()), first);
        assertSame(first, JsonCodecs.codecFor(Unlisted.class));
    }

    private static Cause failureOf(Result<?> result) {
        return result.fold(cause -> cause, value -> {
            throw new AssertionError("expected a failure, got " + value);
        });
    }
}
