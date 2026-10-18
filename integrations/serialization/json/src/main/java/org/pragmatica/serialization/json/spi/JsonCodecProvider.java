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

import org.pragmatica.serialization.json.JsonTypeCodec;


/// The generated JSON codec of one `@Json` type, found through `java.util.ServiceLoader`. The codec processor makes
/// each type's entry point `<Flat>Json` implement it (a public no-argument constructor, as the `ServiceLoader`
/// requires of a class-path provider; the codec itself stays the entry point's `CODEC` constant) and lists every entry
/// point in `META-INF/services/org.pragmatica.serialization.json.spi.JsonCodecProvider`. `JsonCodecs.codecFor`
/// derives the entry point's name from the class it is asked for and instantiates only the provider of that name.
/// Implemented by generated code only.
public interface JsonCodecProvider {
    /// The `@Json` type this provider serves.
    Class<?> type();
    /// The codec of [#type()].
    JsonTypeCodec<?> codec();
}
