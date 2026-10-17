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
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.utils.Causes;


/// Failures produced while decoding JSON. The reader records at most one of these in its error slot;
/// generated codecs lift it into a `Result` once, at the codec boundary.
public sealed interface JsonError extends Cause {
    record Malformed(int position, String expected, String message) implements JsonError {
        public static final Fn2<Malformed, Integer, String> FACTORY = Causes.forTwoValues("Malformed JSON at offset %d: expected %s",
                                                                                          Malformed::new);
    }

    record MissingField(String field, String message) implements JsonError {
        public static final Fn1<MissingField, String> FACTORY = Causes.forOneValue("Missing required field: %s",
                                                                                   MissingField::new);
    }

    /// A value object's factory refused the field's value; the factory's cause travels as `origin`.
    record InvalidField(Cause origin, String field, String message) implements JsonError, Cause.Wrapped {
        public static InvalidField invalidField(Cause origin, String field) {
            return new InvalidField(origin, field, "Invalid value for field " + field + ": " + origin.message());
        }
    }

    record OutOfRange(int position, String type, String message) implements JsonError {
        public static final Fn2<OutOfRange, Integer, String> FACTORY = Causes.forTwoValues("Number at offset %d does not fit %s",
                                                                                           OutOfRange::new);
    }

    /// A non-optional field, or an element or entry inside it, holds `null`. Only an `Option` field accepts `null`.
    record UnexpectedNull(String field, int position, String message) implements JsonError {
        public static final Fn2<UnexpectedNull, String, Integer> FACTORY = Causes.forTwoValues("Field %s must not be null (offset %d)",
                                                                                               UnexpectedNull::new);
    }

    /// Objects and arrays nest deeper than the reader's limit. A typed failure rather than a stack overflow: generated
    /// readers recurse once per level, so the limit is what keeps hostile input from exhausting the stack.
    record NestingTooDeep(int limit, int position, String message) implements JsonError {
        public static final Fn2<NestingTooDeep, Integer, Integer> FACTORY = Causes.forTwoValues("Nesting deeper than %d levels at offset %d",
                                                                                                NestingTooDeep::new);
    }

    /// `decode(json, offset, length)` named a region that does not lie inside `json`; nothing was read.
    record InvalidRange(int offset, int length, int size, String message) implements JsonError {
        public static final Fn3<InvalidRange, Integer, Integer, Integer> FACTORY = Causes.forThreeValues("Region at offset %d, length %d lies outside an input of %d bytes",
                                                                                                         InvalidRange::new);
    }
}
