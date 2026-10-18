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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/// The JSON name of a record component or an enum constant, overriding the build's [NamingStrategy]:
///
/// ```java
/// @Json
/// public record Account(@JsonName("user_name") String userName, Option<String> note) {}
/// ```
///
/// The generator compiles the name into the codec's name tables; `JsonMapper.valueObjectJsonMapper()` honours it
/// on the Jackson side, so both write one wire form. It is not a Jackson annotation, so a type using it keeps its
/// generated codec. The name must be non-empty and hold no `"`, `\` or control character (a compile error
/// otherwise), and no two components (or constants) of one type may end up with the same name.
///
/// The `FIELD`, `METHOD` and `PARAMETER` targets let the compiler carry the annotation from a record component to
/// its field, accessor and canonical constructor parameter, where Jackson reads it; `FIELD` also covers enum
/// constants. Placed anywhere else it has no effect.
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
public @interface JsonName {
    String value();
}
