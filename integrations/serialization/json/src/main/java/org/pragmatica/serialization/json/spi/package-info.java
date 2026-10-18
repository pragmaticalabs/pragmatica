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
/// Runtime machinery for generated JSON codecs: the pull reader, the writer, field-name tables, the
/// double-parsing tables, the per-type provider contract and the generated-class marker.
///
/// **For generated code; not a stable API.** Signatures here change whenever the generator changes, and the
/// generator and this package are released together. Application code uses `org.pragmatica.serialization.json`
/// (`JsonTypeCodec`, `JsonCodecs`, `JsonError`, `@Json`) and the generated `<Type>Json` entry points; the
/// only member a caller may reasonably hold is a reusable [JsonReader] or [JsonWriter] instance, passed to the
/// reuse overloads of `JsonTypeCodec`.
package org.pragmatica.serialization.json.spi;
