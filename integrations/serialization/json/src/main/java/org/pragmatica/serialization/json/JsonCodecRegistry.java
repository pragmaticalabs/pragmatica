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

import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

import org.pragmatica.lang.Lazy;
import org.pragmatica.serialization.json.spi.JsonCodecProvider;


/// The registry behind [JsonCodecs#codecFor]: every provider's codecs, loaded once, on first use. `Lazy`
/// publishes the map safely and does not cache a failed load.
sealed interface JsonCodecRegistry {
    Lazy<Map<Class<?>, JsonTypeCodec<?>>> CODECS = Lazy.lazy(JsonCodecRegistry::load);

    private static Map<Class<?>, JsonTypeCodec<?>> load() {
        var codecs = new HashMap<Class<?>, JsonTypeCodec<?>>();

        ServiceLoader.load(JsonCodecProvider.class).forEach(provider -> provider.codecs()
                                                                                .forEach(codecs::putIfAbsent));

        return Map.copyOf(codecs);
    }

    record unused() implements JsonCodecRegistry {}
}
