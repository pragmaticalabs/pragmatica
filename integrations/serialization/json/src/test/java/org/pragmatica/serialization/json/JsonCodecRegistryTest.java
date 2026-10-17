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
import org.pragmatica.serialization.json.spi.JsonCodecProvider;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/// A class listed by two providers is a class-path conflict: its lookup fails naming both, whatever the load order,
/// while classes listed once keep resolving.
class JsonCodecRegistryTest {
    record Shared() {}

    record OnlyFirst() {}

    record OnlySecond() {}

    static final JsonTypeCodec<Object> FIRST_CODEC = JsonTypeCodec.jsonTypeCodec((out, value) -> out.writeNull(), in -> null);
    static final JsonTypeCodec<Object> SECOND_CODEC = JsonTypeCodec.jsonTypeCodec((out, value) -> out.writeNull(), in -> null);

    static final class FirstProvider implements JsonCodecProvider {
        @Override
        public Map<Class<?>, JsonTypeCodec<?>> codecs() {
            return Map.of(Shared.class, FIRST_CODEC, OnlyFirst.class, FIRST_CODEC);
        }
    }

    static final class SecondProvider implements JsonCodecProvider {
        @Override
        public Map<Class<?>, JsonTypeCodec<?>> codecs() {
            return Map.of(Shared.class, SECOND_CODEC, OnlySecond.class, SECOND_CODEC);
        }
    }

    @Test
    void classListedByTwoProviders_failsNamingBoth_inEitherLoadOrder() {
        for (var order : List.of(List.<JsonCodecProvider>of(new FirstProvider(), new SecondProvider()),
                                 List.<JsonCodecProvider>of(new SecondProvider(), new FirstProvider()))) {
            var conflict = JsonCodecRegistry.index(order)
                                            .get(Shared.class)
                                            .fold(cause -> cause, codec -> {
                                                throw new AssertionError("expected a conflict, got " + codec);
                                            });
            var providers = assertInstanceOf(JsonCodecs.ConflictingProviders.class, conflict).providers();

            assertEquals(2, providers.size(), providers.toString());
            assertEquals(Set.of(FirstProvider.class.getName(), SecondProvider.class.getName()),
                         Set.copyOf(providers));
        }
    }

    @Test
    void classesListedOnce_stillResolve_besideAConflict() {
        var index = JsonCodecRegistry.index(List.of(new FirstProvider(), new SecondProvider()));

        assertSame(FIRST_CODEC, index.get(OnlyFirst.class).unwrap());
        assertSame(SECOND_CODEC, index.get(OnlySecond.class).unwrap());
    }
}
