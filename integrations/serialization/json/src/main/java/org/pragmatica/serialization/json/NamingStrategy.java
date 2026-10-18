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

import java.util.Arrays;
import java.util.Locale;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// How a record component's name becomes its JSON name. A BUILD-time choice: the generator compiles the translated
/// names into the codec's name tables (`-Ajson.naming=snake_case` for `@Json` types), so a running codec has no
/// naming to configure. A component annotated with [JsonName] keeps that name whatever the strategy.
///
/// The constant names follow `org.pragmatica.aether.resource.http.JsonConfig.NamingStrategy` where a strategy exists
/// there (`SNAKE_CASE`, `KEBAB_CASE`). Its `CAMEL_CASE` is applied there as "leave the name alone", which is
/// [#AS_DECLARED] here: a component name is not necessarily camel case, and the name says what is done.
///
/// Every translation is the one Jackson's `PropertyNamingStrategies` constant of the same meaning performs, so a
/// Jackson mapper given the same strategy (`JsonMapper.jsonMapper().withNaming(...)`) writes the same names;
/// json-tests pins each against Jackson's.
public enum NamingStrategy {
    /// The component name as declared (the default).
    AS_DECLARED,
    /// `userId` -> `user_id`, as Jackson's `SNAKE_CASE`.
    SNAKE_CASE,
    /// `userId` -> `user-id`, as Jackson's `KEBAB_CASE`.
    KEBAB_CASE,
    /// `userId` -> `UserId`, as Jackson's `UPPER_CAMEL_CASE`.
    PASCAL_CASE;
    /// A name no strategy answers to.
    public record UnknownStrategy(String name, String message) implements Cause {
        public static final Fn1<UnknownStrategy, String> FACTORY = Causes.forOneValue("Unknown JSON naming strategy '%s'; expected one of as_declared, snake_case, kebab_case, pascal_case",
                                                                                      UnknownStrategy::new);
    }
    /// The strategy `raw` names: a constant name in any case, with `_`, `-` or no separator (`snake_case`,
    /// `SNAKE_CASE`, `kebab-case`, `PascalCase`).
    public static Result<NamingStrategy> namingStrategy(String raw) {
        return Option.from(Arrays.stream(values()).filter(strategy -> strategy.matches(raw)).findFirst()).toResult(UnknownStrategy.FACTORY.apply(raw));
    }
    /// The JSON name of a component declared as `name`.
    public String apply(String name) {
        return switch (this) {
            case AS_DECLARED -> name;
            case SNAKE_CASE -> snakeCase(name);
            case KEBAB_CASE -> lowerCaseWithSeparator(name, '-');
            case PASCAL_CASE -> pascalCase(name);
        };
    }
    private boolean matches(String raw) {
        return squeezed(name()).equals(squeezed(raw));
    }
    private static String squeezed(String text) {
        return text.replace("_", "")
                   .replace("-", "")
                   .toUpperCase(Locale.ROOT);
    }
    // Jackson's SnakeCaseStrategy#translate: an upper-case letter starts a word unless the previous character was
    // translated too (an acronym stays one word) or a separator precedes it; one leading underscore is dropped.
    private static String snakeCase(String input) {
        var result = new StringBuilder(input.length() * 2);
        var wasPrevTranslated = false;

        for (int i = 0; i < input.length(); i++) {
            var c = input.charAt(i);

            if (i == 0 && c == '_') {
                continue;
            }

            var upper = Character.isUpperCase(c);

            if (upper && !wasPrevTranslated && !result.isEmpty() && result.charAt(result.length() - 1) != '_') {
                result.append('_');
            }

            result.append(upper
                          ? Character.toLowerCase(c)
                          : c);
            wasPrevTranslated = upper;
        }

        return result.isEmpty()
               ? input
               : result.toString();
    }
    // Jackson's NamingBase#translateLowerCaseWithSeparator: a run of upper-case letters is one word, except that its
    // last letter starts the next word when a lower-case letter follows (`URLValue` -> `url-value`).
    private static String lowerCaseWithSeparator(String input, char separator) {
        var result = new StringBuilder(input.length() + (input.length() >> 1));
        var upperCount = 0;

        for (int i = 0; i < input.length(); i++) {
            var ch = input.charAt(i);
            var lc = Character.toLowerCase(ch);

            if (lc == ch) {
                if (upperCount > 1) {
                    result.insert(result.length() - 1, separator);
                }

                upperCount = 0;
            } else {
                if (upperCount == 0 && i > 0) {
                    result.append(separator);
                }

                upperCount++;
            }

            result.append(lc);
        }

        return result.toString();
    }
    // Jackson's UpperCamelCaseStrategy#translate: the first character upper-cased, nothing else touched.
    private static String pascalCase(String input) {
        return input.isEmpty()
               ? input
               : Character.toUpperCase(input.charAt(0)) + input.substring(1);
    }
}
