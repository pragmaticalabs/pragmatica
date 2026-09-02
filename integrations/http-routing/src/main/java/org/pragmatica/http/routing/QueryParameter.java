package org.pragmatica.http.routing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.http.routing.ParameterError.InvalidParameter;


/// Type-safe query parameter parser.
///
/// Query parameters are optional by nature - missing parameters return `Option.none()`.
/// Invalid values return `Result.failure()`.
///
/// @param <T> the type of the parsed parameter value
@SuppressWarnings("unused")
public interface QueryParameter<T> {
    /// Get the parameter name.
    String name();

    /// Parse query parameter values.
    /// Returns `Option.none()` if parameter is missing.
    ///
    /// @param values the list of values for this parameter (null or empty if missing)
    /// @return success with optional value, or failure if parsing fails
    Result<Option<T>> parse(List<String> values);

    /// String query parameter - accepts any string value.
    ///
    /// @param name the parameter name
    static QueryParameter<String> aString(String name) {
        return new QueryParameter<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Result<Option<String>> parse(List<String> values) {
                return Result.success(firstValue(values));
            }
        };
    }

    /// UUID query parameter - parses a canonical RFC-4122 textual UUID.
    ///
    /// @param name the parameter name
    static QueryParameter<UUID> aUuid(String name) {
        return new QueryParameter<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Result<Option<UUID>> parse(List<String> values) {
                return firstValue(values).map(value -> Result.lift(_ -> new InvalidParameter("Invalid UUID query param '" + name
                                                                                            + "': " + value),
                                                                   () -> UUID.fromString(value)))
                                 .fold(() -> Result.success(Option.none()),
                                       result -> result.map(Option::some));
            }
        };
    }

    /// Integer query parameter - parses signed 32-bit integer.
    ///
    /// @param name the parameter name
    static QueryParameter<Integer> aInteger(String name) {
        return new QueryParameter<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Result<Option<Integer>> parse(List<String> values) {
                return firstValue(values).map(value -> Result.lift(_ -> new InvalidParameter("Invalid integer query param '" + name
                                                                                            + "': " + value),
                                                                   () -> Integer.parseInt(value)))
                                 .fold(() -> Result.success(Option.none()),
                                       result -> result.map(Option::some));
            }
        };
    }

    /// Long query parameter - parses signed 64-bit integer.
    ///
    /// @param name the parameter name
    static QueryParameter<Long> aLong(String name) {
        return new QueryParameter<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Result<Option<Long>> parse(List<String> values) {
                return firstValue(values).map(value -> Result.lift(_ -> new InvalidParameter("Invalid long query param '" + name
                                                                                            + "': " + value),
                                                                   () -> Long.parseLong(value)))
                                 .fold(() -> Result.success(Option.none()),
                                       result -> result.map(Option::some));
            }
        };
    }

    /// Boolean query parameter - parses boolean value.
    /// Accepts "true"/"false" and "yes"/"no" (case-insensitive).
    ///
    /// @param name the parameter name
    static QueryParameter<Boolean> aBoolean(String name) {
        return new QueryParameter<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Result<Option<Boolean>> parse(List<String> values) {
                return firstValue(values).map(value -> parseBooleanValue(name, value))
                                 .fold(() -> Result.success(Option.none()),
                                       result -> result.map(Option::some));
            }
        };
    }

    private static Result<Boolean> parseBooleanValue(String name, String value) {
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes")) {
            return Result.success(true);
        }

        if (value.equalsIgnoreCase("false") || value.equalsIgnoreCase("no")) {
            return Result.success(false);
        }

        return new InvalidParameter("Invalid boolean query param '" + name
                                   + "': " + value
                                   + " (expected true/false or yes/no)").result();
    }

    /// Double query parameter - parses 64-bit floating point number.
    ///
    /// @param name the parameter name
    static QueryParameter<Double> aDouble(String name) {
        return new QueryParameter<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Result<Option<Double>> parse(List<String> values) {
                return firstValue(values).map(value -> Result.lift(_ -> new InvalidParameter("Invalid double query param '" + name
                                                                                            + "': " + value),
                                                                   () -> Double.parseDouble(value)))
                                 .fold(() -> Result.success(Option.none()),
                                       result -> result.map(Option::some));
            }
        };
    }

    /// BigDecimal query parameter - parses arbitrary precision decimal.
    ///
    /// @param name the parameter name
    static QueryParameter<BigDecimal> aDecimal(String name) {
        return new QueryParameter<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Result<Option<BigDecimal>> parse(List<String> values) {
                return firstValue(values).map(value -> Result.lift(_ -> new InvalidParameter("Invalid decimal query param '" + name
                                                                                            + "': " + value),
                                                                   () -> new BigDecimal(value)))
                                 .fold(() -> Result.success(Option.none()),
                                       result -> result.map(Option::some));
            }
        };
    }

    /// LocalDate query parameter - parses ISO-8601 date.
    /// Example: "2023-12-15"
    ///
    /// @param name the parameter name
    static QueryParameter<LocalDate> aLocalDate(String name) {
        return new QueryParameter<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Result<Option<LocalDate>> parse(List<String> values) {
                return firstValue(values).map(value -> Result.lift(_ -> new InvalidParameter("Invalid local date query param '" + name
                                                                                            + "': " + value),
                                                                   () -> LocalDate.parse(value)))
                                 .fold(() -> Result.success(Option.none()),
                                       result -> result.map(Option::some));
            }
        };
    }

    /// LocalDateTime query parameter - parses ISO-8601 date-time without offset.
    /// Example: "2023-12-15T10:30:00"
    ///
    /// @param name the parameter name
    static QueryParameter<LocalDateTime> aLocalDateTime(String name) {
        return new QueryParameter<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Result<Option<LocalDateTime>> parse(List<String> values) {
                return firstValue(values).map(value -> Result.lift(_ -> new InvalidParameter("Invalid local date-time query param '" + name
                                                                                            + "': " + value),
                                                                   () -> LocalDateTime.parse(value)))
                                 .fold(() -> Result.success(Option.none()),
                                       result -> result.map(Option::some));
            }
        };
    }

    /// Compose this framework-owned `String -> P` parser with a value object's fallible `lift`
    /// (`P -> Result<T>`) so a query segment is parsed to its primitive representation, then lifted
    /// into the value object. A missing optional parameter stays `Option.none()`; a present value
    /// that fails to parse or fails the `lift` surfaces as a typed [org.pragmatica.http.HttpError]
    /// 400, never a 500 and never a silent raw value. The value object declares only its `P`
    /// mapping; it never mentions `String`, `QueryParameter`, or HTTP status.
    default <R> QueryParameter<R> mapped(Fn1<Result<R>, T> lift) {
        return new QueryParameter<>() {
            @Override
            public String name() {
                return QueryParameter.this.name();
            }

            @Override
            public Result<Option<R>> parse(List<String> values) {
                return QueryParameter.this.parse(values)
                                     .flatMap(opt -> liftOption(opt, lift))
                                     .mapError(cause -> HttpStatus.BAD_REQUEST.with(cause));
            }
        };
    }

    /// Lift a present primitive through the value object's `lift`, preserving optionality: a missing
    /// value stays `Option.none()`; a present value is lifted and re-wrapped as `Option.some(...)`.
    private static <R, T> Result<Option<R>> liftOption(Option<T> primitive, Fn1<Result<R>, T> lift) {
        return primitive.map(lift)
                        .fold(() -> Result.success(Option.none()),
                              result -> result.map(Option::some));
    }

    /// Extract first value from parameter list, if present.
    private static Option<String> firstValue(List<String> values) {
        return values == null || values.isEmpty()
               ? Option.none()
               : Option.option(values.getFirst());
    }
}
