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
package org.pragmatica.serialization.json.spi;

import java.util.Map;

import org.pragmatica.serialization.json.JsonTypeCodec;


/// One package's generated JSON codecs, found through `java.util.ServiceLoader`. The codec processor writes one
/// implementation per package holding `@Json` types and lists it in
/// `META-INF/services/org.pragmatica.serialization.json.spi.JsonCodecProvider`; `JsonCodecs.codecFor` reads
/// them all. Implemented by generated code only.
public interface JsonCodecProvider {
    /// The `@Json` codecs of one package, by exact class (records and enums are final).
    Map<Class<?>, JsonTypeCodec<?>> codecs();
}
