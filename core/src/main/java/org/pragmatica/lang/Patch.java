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
package org.pragmatica.lang;

import java.util.function.Supplier;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Patch.Absent;
import org.pragmatica.lang.Patch.Null;
import org.pragmatica.lang.Patch.Value;


/// One field of a partial update, as a request body states it: left out ([Absent]), set to an explicit `null`
/// ([Null]), or set to a value ([Value]).
///
/// [Option] cannot carry this: a missing member and `"field":null` both decode to an empty `Option`, so a
/// PUT-style update cannot tell "leave it" from "clear it" (#2884). `Option<Option<T>>` is not the answer either: JBCT
/// forbids nested wrappers, and the JSON codecs read both cases as empty anyway. `Option` keeps its meaning; a field
/// that must tell the three apart is declared `Patch<T>`.
///
/// ## JSON
///
/// Both JSON paths (the generated codecs and the Jackson mapper) read and write it the same way, as a record
/// component:
///
/// | State | Read from | Written as |
/// |-------|-----------|------------|
/// | [Absent] | the member is missing | the member is left out, whatever the null-inclusion option |
/// | [Null] | `"field":null` | `"field":null`, whatever the null-inclusion option |
/// | [Value] | `"field":<value>` | `"field":<value>` |
///
/// A record component is the only position a `Patch` may take. A list or set element, a map value, the whole body,
/// or the element of an `Option` or another `Patch` has no "missing" state, so both paths refuse such a type with a
/// message naming the position, instead of silently reading `Absent` as `null`: the generated codecs at build time,
/// Jackson when it first reads the type (on write, a `Patch` nested inside a container value is refused when one is
/// written).
///
/// ## Applying
///
/// [#applyTo] is the partial update of a nullable field held as an `Option`: `Absent` keeps the current value,
/// `Null` clears it, `Value` replaces it. A field that must never be cleared validates the `Null` state through
/// [#fold].
///
/// @param <T> Type of the value
public sealed interface Patch<T> permits Absent, Null, Value {
    /// **[Pure Transform]**
    /// Reduce the patch to one value, choosing the function for its state.
    ///
    /// @param onAbsent the result when the field was left out
    /// @param onNull   the result when the field was set to `null`
    /// @param onValue  the result for the value the field was set to
    /// @param <R>      Type of the result
    ///
    /// @return the chosen function's result
    <R> R fold(Supplier<? extends R> onAbsent, Supplier<? extends R> onNull, Fn1<? extends R, ? super T> onValue);

    /// **[Pure Transform]**
    /// Transform the value of a [Value] patch; [Absent] and [Null] stay as they are.
    ///
    /// @param mapper Mapping function
    /// @param <U>    Type of new value
    ///
    /// @return transformed instance
    default <U> Patch<U> map(Fn1<U, ? super T> mapper) {
        return fold(Patch::absent, Patch::nullValue, present -> value(mapper.apply(present)));
    }

    /// **[Pure Transform]**
    /// Apply this patch to the current value of a nullable field: [Absent] keeps `current`, [Null] gives an empty
    /// [Option], [Value] gives its value.
    ///
    /// @param current the field's value before the update
    ///
    /// @return the field's value after the update
    default Option<T> applyTo(Option<T> current) {
        return fold(() -> current, Option::none, Option::some);
    }

    /// **[Factory]**
    /// Create the patch of a field that was left out.
    ///
    /// @return Created instance
    @SuppressWarnings("unchecked")
    static <U> Patch<U> absent() {
        return (Patch<U>) ABSENT;
    }

    /// **[Factory]**
    /// Create the patch of a field that was set to an explicit `null`.
    ///
    /// @return Created instance
    @SuppressWarnings("unchecked")
    static <U> Patch<U> nullValue() {
        return (Patch<U>) NULL;
    }

    /// **[Factory]**
    /// Create the patch of a field that was set to a value.
    ///
    /// @param value Value to be stored in the created instance.
    ///
    /// @return Created instance
    static <U> Patch<U> value(U value) {
        return new Value<>(value);
    }

    record Absent<T>() implements Patch<T> {
        @Override
        public <R> R fold(Supplier<? extends R> onAbsent,
                          Supplier<? extends R> onNull,
                          Fn1<? extends R, ? super T> onValue) {
            return onAbsent.get();
        }

        @Override
        public String toString() {
            return "Absent()";
        }
    }

    record Null<T>() implements Patch<T> {
        @Override
        public <R> R fold(Supplier<? extends R> onAbsent,
                          Supplier<? extends R> onNull,
                          Fn1<? extends R, ? super T> onValue) {
            return onNull.get();
        }

        @Override
        public String toString() {
            return "Null()";
        }
    }

    record Value<T>(T value) implements Patch<T> {
        @Override
        public <R> R fold(Supplier<? extends R> onAbsent,
                          Supplier<? extends R> onNull,
                          Fn1<? extends R, ? super T> onValue) {
            return onValue.apply(value);
        }

        @Override
        public String toString() {
            return "Value(" + value + ")";
        }
    }

    @SuppressWarnings({"rawtypes"})
    Absent ABSENT = new Absent();

    @SuppressWarnings({"rawtypes"})
    Null NULL = new Null();
}
