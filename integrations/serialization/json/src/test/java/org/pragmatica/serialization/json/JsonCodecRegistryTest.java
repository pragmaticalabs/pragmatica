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
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
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
