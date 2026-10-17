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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/// Opts a record or enum into a generated, reflection-free JSON codec: the processor emits the entry point
/// `<Type>Json` next to it (`<Outer>_<Type>Json` for a nested type, an `_` or `$` inside a simple name escaped as `_0`
/// or `_1`, so every generated name belongs to exactly one type), with `CODEC` and static `decode` / `encode`,
/// and lists the codec in its package's registry for [JsonCodecs#codecFor]. Separate from `@Codec` on purpose, so
/// adopting JSON never changes a type's binary wire contract. HTTP route request and response types need no
/// annotation: the slice processor generates their codecs.
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface Json {}
