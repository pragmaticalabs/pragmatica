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
package org.pragmatica.serialization.json.tests;

import org.pragmatica.lang.Result;
import org.pragmatica.serialization.json.Json;

import java.math.BigDecimal;
import java.util.List;

/// One fixture set for both value-object rules: the generator's (`JsonType.RecordDefinition#isValueObject`) and
/// Jackson's (`ValueObjectModule#valueObjectFactory`). Each record is a root of its own `@Json` holder, so the
/// generator's verdict is read off the generated code and Jackson's off the module, for the same class.
public interface ValueObjectFixtures {
    @Json
    record Email(String value) {
        public static Result<Email> email(String value) {
            return Result.success(new Email(value));
        }
    }

    @Json
    record Count(Integer value) {
        public static Result<Count> count(Integer value) {
            return Result.success(new Count(value));
        }
    }

    @Json
    record Flag(boolean value) {
        public static Result<Flag> flag(boolean value) {
            return Result.success(new Flag(value));
        }
    }

    @Json
    record Amount(BigDecimal value) {
        public static Result<Amount> amount(BigDecimal value) {
            return Result.success(new Amount(value));
        }
    }

    /// Package-private factory: a value object on both sides (the holder lives in this package).
    @Json
    record Local(String value) {
        static Result<Local> local(String value) {
            return Result.success(new Local(value));
        }
    }

    @Json
    record TwoParts(String first, String second) {
        public static Result<TwoParts> twoParts(String first, String second) {
            return Result.success(new TwoParts(first, second));
        }
    }

    @Json
    record Tags(List<String> value) {
        public static Result<Tags> tags(List<String> value) {
            return Result.success(new Tags(value));
        }
    }

    @Json
    record WrongName(String value) {
        public static Result<WrongName> of(String value) {
            return Result.success(new WrongName(value));
        }
    }

    @Json
    record WrongReturn(String value) {
        public static Result<String> wrongReturn(String value) {
            return Result.success(value);
        }
    }

    @Json
    record WrongParams(String value) {
        public static Result<WrongParams> wrongParams(int value) {
            return Result.success(new WrongParams(String.valueOf(value)));
        }
    }

    @Json
    record Hidden(String value) {
        private static Result<Hidden> hidden(String value) {
            return Result.success(new Hidden(value));
        }
    }

    /// Generic: the generator refuses it outright (no codec at all), and Jackson must not take it for a value
    /// object either. Not `@Json`, because the refusal is a compile error.
    record Tagged<T>(String value) {
        public static <T> Result<Tagged<T>> tagged(String value) {
            return Result.success(new Tagged<>(value));
        }
    }
}
