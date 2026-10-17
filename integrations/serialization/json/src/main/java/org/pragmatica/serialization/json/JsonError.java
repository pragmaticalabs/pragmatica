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

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.utils.Causes;

/// Failures produced while decoding JSON. The reader records at most one of these in its error slot;
/// generated codecs lift it into a `Result` once, at the codec boundary.
public sealed interface JsonError extends Cause {
    record Malformed(int position, String expected, String message) implements JsonError {
        static final Fn2<Malformed, Integer, String> FACTORY =
            Causes.forTwoValues("Malformed JSON at offset %d: expected %s", Malformed::new);
    }

    record MissingField(String field, String message) implements JsonError {
        static final Fn1<MissingField, String> FACTORY =
            Causes.forOneValue("Missing required field: %s", MissingField::new);
    }

    /// A value object's factory refused the field's value; the factory's cause travels as `origin`.
    record InvalidField(Cause origin, String field, String message) implements JsonError, Cause.Wrapped {
        static final Fn2<InvalidField, Cause, String> FACTORY =
            Causes.forTwoValues("Invalid value for field %2$s", InvalidField::new);
    }

    record OutOfRange(int position, String type, String message) implements JsonError {
        static final Fn2<OutOfRange, Integer, String> FACTORY =
            Causes.forTwoValues("Number at offset %d does not fit %s", OutOfRange::new);
    }
}
