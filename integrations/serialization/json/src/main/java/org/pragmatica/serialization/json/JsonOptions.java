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
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.json.spi.JsonReader;


/// DEPLOY-time JSON options, applied alike to the generated codecs ([JsonTypeCodec#withOptions], `<Type>Json.codec`)
/// and to the Jackson mapper (`JsonMapper.jsonMapper().withOptions(...)`), so the two keep writing one wire form and
/// accepting the same documents under any configuration. Names are not here: they are compiled in ([NamingStrategy],
/// [JsonName]).
///
/// The field names (and so the configuration keys `null_inclusion`, `fail_on_unknown`, ...) and the [NullInclusion]
/// constants follow `org.pragmatica.aether.resource.http.JsonConfig` where the meaning is the same.
///
/// [#DEFAULTS] is exactly the behaviour of a codec without options, which is also what `JsonTypeCodec`'s codecs and
/// `<Type>Json.CODEC` keep using.
///
/// @param nullInclusion   what an empty `Option` component becomes on write
/// @param failOnUnknown   refuse an object member no component declares ([JsonError.UnknownField]) instead of
///                        skipping it
/// @param failOnDuplicate refuse a key repeated within one object ([JsonError.DuplicateKey]) instead of keeping its
///                        last value
/// @param maxDepth        deepest nesting of objects and arrays accepted, counted per container
///                        ([JsonError.NestingTooDeep])
/// @param maxStringLength longest string accepted, in UTF-16 characters as Jackson counts them; applies where Jackson
///                        applies it: to string values that are read, to every object key (Jackson's name limit is
///                        set to the same value), and to the whole text of an integer or of a skipped number (not of a
///                        floating-point number that is read: Jackson does not measure that one). A skipped string
///                        value is not measured: Jackson measures one only when its text buffer grows, so it refuses
///                        some long skipped strings (from 200 characters) that the generated codecs accept
///                        ([JsonError.StringTooLong])
/// @param maxNumberLength most digits a number may have, sign, point and exponent marker not counted
///                        ([JsonError.NumberTooLong])
/// @param maxDocumentSize largest document accepted, in bytes ([JsonError.DocumentTooLarge])
public record JsonOptions(NullInclusion nullInclusion,
                          boolean failOnUnknown,
                          boolean failOnDuplicate,
                          int maxDepth,
                          int maxStringLength,
                          int maxNumberLength,
                          int maxDocumentSize) {
    /// The shortest `maxStringLength` accepted. Below it the generated codecs' fixed-format fast paths (a UUID is 36
    /// characters, an Instant at most 30) and ordinary field names, which are matched without a length check, could
    /// pass a string Jackson would refuse.
    public static final int MIN_STRING_LENGTH = 64;

    /// Today's behaviour, every limit at what an unconfigured codec accepts.
    ///
    /// - `maxDepth` 1000: [JsonReader#MAX_DEPTH], the generated readers' limit. Jackson 3's
    ///   `StreamReadConstraints` default is 500, which would refuse the 400-level recursive records (two containers
    ///   per level) that decode today.
    /// - `maxStringLength`, `maxNumberLength` and `maxDocumentSize` `Integer.MAX_VALUE`, which no `byte[]` document
    ///   can exceed: the generated readers have no such limits, so every input accepted today stays accepted. Jackson
    ///   3's defaults (strings 100,000,000 characters, names 50,000, numbers 1,000 digits, documents unlimited) would
    ///   refuse inputs accepted today (a 1,001-digit `BigDecimal`, a 50,001-character key). A deployment that accepts
    ///   untrusted input should lower them: a long number costs quadratic time in `BigDecimal`.
    public static final JsonOptions DEFAULTS = new JsonOptions(NullInclusion.INCLUDE,
                                                               false,
                                                               false,
                                                               JsonReader.MAX_DEPTH,
                                                               Integer.MAX_VALUE,
                                                               Integer.MAX_VALUE,
                                                               Integer.MAX_VALUE);

    /// A limit below its minimum; `option` is its configuration key.
    public record InvalidLimit(String option, int minimum, int value, String message) implements Cause {
        public static final Fn3<InvalidLimit, String, Integer, Integer> FACTORY = Causes.forThreeValues("%s must be at least %d, got %d",
                                                                                                        InvalidLimit::new);
    }

    /// What an empty `Option` component becomes on write. Named after `JsonConfig.NullInclusion`: an empty `Option` is
    /// this format's `null`, so the constant that excludes nulls there excludes empty `Option`s here. Its third
    /// constant, `NON_EMPTY`, also drops empty strings, collections and maps, which the generated codecs do not, so it
    /// has no counterpart. Only record components are affected: an empty `Option` inside a list or a map value, or at
    /// the top level, is always `null`, as Jackson writes it.
    public enum NullInclusion {
        /// `"note":null` (the default).
        INCLUDE,
        /// The member is left out.
        EXCLUDE
    }

    /// Validated options. Limits must be positive, and `maxStringLength` at least [#MIN_STRING_LENGTH]; every limit
    /// below its minimum is reported, as an [InvalidLimit] each.
    ///
    /// A `maxDepth` above the default trades away part of the guarantee that hostile nesting fails as a typed
    /// [JsonError.NestingTooDeep]: generated readers recurse once per container, so a limit deeper than the thread's
    /// stack allows ends in a `StackOverflowError` instead.
    public static Result<JsonOptions> jsonOptions(NullInclusion nullInclusion,
                                                  boolean failOnUnknown,
                                                  boolean failOnDuplicate,
                                                  int maxDepth,
                                                  int maxStringLength,
                                                  int maxNumberLength,
                                                  int maxDocumentSize) {
        return Result.all(atLeast("max_depth", 1, maxDepth),
                          atLeast("max_string_length", MIN_STRING_LENGTH, maxStringLength),
                          atLeast("max_number_length", 1, maxNumberLength),
                          atLeast("max_document_size", 1, maxDocumentSize)).map((depth, string, number, document) -> new JsonOptions(nullInclusion,
                                                                                                                                     failOnUnknown,
                                                                                                                                     failOnDuplicate,
                                                                                                                                     depth,
                                                                                                                                     string,
                                                                                                                                     number,
                                                                                                                                     document));
    }

    private static Result<Integer> atLeast(String option, int minimum, int value) {
        return Verify.ensure(value,
                             Verify.Is::greaterThanOrEqualTo,
                             minimum,
                             InvalidLimit.FACTORY.apply(option, minimum, value));
    }
}
