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

package org.pragmatica.consensus.rabia;

import org.pragmatica.consensus.Command;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.serialization.SliceCodec.TypeCodec;

import java.util.List;
import java.util.function.Function;

import static org.pragmatica.serialization.FrameworkCodecs.frameworkCodecs;

/// Shared helper that builds deterministic test serializers for single-field `TestCommand`
/// records used across the Rabia consensus tests. The resulting `SliceCodec` (framework
/// codecs + the test command codec) lets `StateMachine.Batch.create(serializer, commands)`
/// compute a stable SHA-256 batch id in tests, mirroring production wire encoding.
public final class TestSerializers {
    private TestSerializers() {}

    /// Serializer for a `TestCommand(String value)`-shaped record.
    public static <C extends Command> SliceCodec stringCommandSerializer(Class<C> type,
                                                                         Function<C, String> getter,
                                                                         Function<String, C> factory) {
        var codec = new TypeCodec<>(type,
                                    SliceCodec.deterministicTag(type.getName()),
                                    (sc, buf, value) -> sc.write(buf, getter.apply(value)),
                                    (sc, buf) -> factory.apply(sc.read(buf)));
        return SliceCodec.sliceCodec(frameworkCodecs(), List.of(codec));
    }

    /// Serializer for a `TestCommand(int id)`-shaped record.
    public static <C extends Command> SliceCodec intCommandSerializer(Class<C> type,
                                                                      Function<C, Integer> getter,
                                                                      Function<Integer, C> factory) {
        var codec = new TypeCodec<>(type,
                                    SliceCodec.deterministicTag(type.getName()),
                                    (sc, buf, value) -> sc.write(buf, getter.apply(value)),
                                    (sc, buf) -> factory.apply(sc.read(buf)));
        return SliceCodec.sliceCodec(frameworkCodecs(), List.of(codec));
    }
}
