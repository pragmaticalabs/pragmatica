/*
 *  Copyright (c) 2023-2025 Sergiy Yevtushenko.
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
 *
 */
package org.pragmatica.lang.utils;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.Functions.Fn4;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;


/// Frequently used variants of [Cause].
@SuppressWarnings("unused")
public sealed interface Causes {
    record unused() implements Causes {}

    /// Simplest possible variant of the [Cause] which contains only the message describing the cause
    interface SimpleCause extends Cause {
        default String completeMessage() {
            var builder = new StringBuilder("Cause: ");

            iterate(issue -> builder.append("\n  ")
                                    .append(issue.message()));

            return builder.toString();
        }
    }

    /// Construct a simple cause with a given message.
    ///
    /// @param message message describing the cause
    /// @return created instance
    static Cause cause(String message) {
        return cause(message, none());
    }

    /// A cause marked TERMINAL: retry facilities stop on it immediately (see [Cause#isTerminal]).
    /// Use for conditions a retry cannot change; prefer it over spelling "(terminal)" into message text
    /// that no policy reads.
    static Cause terminal(String message) {
        record terminalCause(String message) implements SimpleCause {
            @Override
            public boolean isTerminal() {
                return true;
            }
        }

        return new terminalCause(message);
    }

    static Cause cause(String message, Option<Cause> source) {
        record simpleCause(String message, Option<Cause> source) implements SimpleCause {
            @Override
            public String toString() {
                return completeMessage();
            }
        }

        return new simpleCause(message, source);
    }

    /// Construct a simple cause from provided [Throwable].
    ///
    /// @param throwable the instance of [Throwable] to extract stack trace and message from
    /// @return created instance
    static Cause fromThrowable(Throwable throwable) {
        var sw = new StringWriter();

        throwable.printStackTrace(new PrintWriter(sw));

        return cause(sw.toString());
    }

    /// Create a mapper which will map a single value into a formatted message.
    ///
    /// @param template the message template prepared for [String.format]
    ///
    /// @return created mapping function
    static <T> Fn1<Cause, T> forOneValue(String template) {
        return (T input) -> cause(String.format(Locale.ROOT, template, input));
    }

    /// Create a mapper which will map two values into a formatted message.
    ///
    /// @param template the message template prepared for [String.format]
    ///
    /// @return created mapping function
    static <T1, T2> Fn2<Cause, T1, T2> forTwoValues(String template) {
        return (T1 input1, T2 input2) -> cause(String.format(Locale.ROOT, template, input1, input2));
    }

    /// Create a mapper which will map three values into a formatted message.
    ///
    /// @param template the message template prepared for [String.format]
    ///
    /// @return created mapping function
    static <T1, T2, T3> Fn3<Cause, T1, T2, T3> forThreeValues(String template) {
        return (T1 input1, T2 input2, T3 input3) -> cause(String.format(Locale.ROOT, template, input1, input2, input3));
    }

    /// Typed variant of [#forOneValue(String)]: the mapper builds a CONCRETE cause from the
    /// formatted message, keeping the cause type end to end. The canonical target is a
    /// message-only record's constructor reference.
    ///
    /// Formatting pins [Locale#ROOT] (as do all `forXValues` rungs) so numeric conversions render
    /// identically across JVMs.
    static <T, C extends Cause> Fn1<C, T> forOneValue(String template, Fn1<C, String> causeFactory) {
        return input -> causeFactory.apply(String.format(Locale.ROOT, template, input));
    }

    /// Typed variant of [#forTwoValues(String)]; see [#forOneValue(String, Fn1)].
    static <T1, T2, C extends Cause> Fn2<C, T1, T2> forTwoValues(String template, Fn1<C, String> causeFactory) {
        return (input1, input2) -> causeFactory.apply(String.format(Locale.ROOT, template, input1, input2));
    }

    /// Typed variant of [#forThreeValues(String)]; see [#forOneValue(String, Fn1)].
    static <T1, T2, T3, C extends Cause> Fn3<C, T1, T2, T3> forThreeValues(String template, Fn1<C, String> causeFactory) {
        return (input1, input2, input3) -> causeFactory.apply(String.format(Locale.ROOT, template, input1, input2, input3));
    }

    /// Data-retaining rung of [#forOneValue(String, Fn1)]: the mapper receives the VALUE and the
    /// formatted message, in constructor order, so the canonical constructor reference of a
    /// data-carrying cause record (`record InvalidEmail(String raw, String message)`) is the
    /// factory — the error's data stays available as typed components instead of being baked into
    /// prose (R1/R2 of `core/docs/typed-error-construction.md`).
    ///
    /// Three is the ceiling by decision, not omission: an error carrying more values hand-rolls
    /// its factory in one line.
    static <T, C extends Cause> Fn1<C, T> forOneValue(String template, Fn2<C, T, String> causeFactory) {
        return input -> causeFactory.apply(input, String.format(Locale.ROOT, template, input));
    }

    /// Two-value data-retaining rung; see [#forOneValue(String, Fn2)].
    static <T1, T2, C extends Cause> Fn2<C, T1, T2> forTwoValues(String template, Fn3<C, T1, T2, String> causeFactory) {
        return (input1, input2) -> causeFactory.apply(input1, input2, String.format(Locale.ROOT, template, input1, input2));
    }

    /// Three-value data-retaining rung; see [#forOneValue(String, Fn2)].
    static <T1, T2, T3, C extends Cause> Fn3<C, T1, T2, T3> forThreeValues(String template,
                                                                           Fn4<C, T1, T2, T3, String> causeFactory) {
        return (input1, input2, input3) -> causeFactory.apply(input1,
                                                              input2,
                                                              input3,
                                                              String.format(Locale.ROOT, template, input1, input2, input3));
    }

    interface CompositeCause extends Cause {
        static Cause toComposite(String text, Cause cause) {
            if (cause instanceof CompositeCause composite) {
                return composite.append(cause(text));
            }

            return composite().append(cause(text, option(cause)));
        }

        CompositeCause append(Cause cause);
        boolean isEmpty();
        Cause replace(Cause input);
    }

    static CompositeCause composite(Result<?>... results) {
        record compositeCause(Option<Cause> source, List<Cause> causes) implements CompositeCause {
            @Override
            public CompositeCause append(Cause cause) {
                causes().add(cause);

                return this;
            }

            @Override
            public Stream<Cause> stream() {
                return causes().stream();
            }

            @Override
            public boolean isEmpty() {
                return causes().isEmpty();
            }

            @Override
            public String message() {
                var builder = new StringBuilder("Composite:");

                stream().forEach(issue -> builder.append("\n  ")
                                                 .append(issue.message()));

                return builder.toString();
            }

            @Override
            public String toString() {
                return message();
            }

            @Override
            public Cause replace(Cause input) {
                return isEmpty()
                       ? input
                       : this;
            }
        }
        var inner = new ArrayList<Cause>();

        Stream.of(results).forEach(result -> result.fold(inner::add, Functions::toNull));

        return new compositeCause(none(), inner);
    }
}
