package conduit.domain;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Cause;


/// Every failure a Conduit operation can end in.
///
/// Each variant carries the docs/behavior.md B-50 error key(s) and message(s), so the body the suite pins can be
/// rendered from the cause alone. The HTTP status is chosen from the variant's simple name (routes.toml `[errors]`),
/// which is why the names are exact and distinct: `Unauthorized` 401, `RequestInvalid` 422, `NotFound` 404,
/// `Forbidden` 403, `Conflict` 409. Anything else is a 500.
public sealed interface ConduitError extends Cause {
    /// One `field: message` pair of the `{"errors":{...}}` body.
    record FieldError(String field, String text) {
        public static FieldError fieldError(String field, String text) {
            return new FieldError(field, text);
        }

        public String render() {
            return field + ": " + text;
        }
    }

    /// 401: no usable token, a bad token, or wrong credentials.
    record Unauthorized(FieldError error, String message) implements ConduitError {
        public static final Unauthorized TOKEN_MISSING = unauthorized(FieldError.fieldError("token", "is missing"));
        public static final Unauthorized TOKEN_INVALID = unauthorized(FieldError.fieldError("token", "is invalid"));

        public static final Unauthorized CREDENTIALS_INVALID = unauthorized(FieldError.fieldError("credentials",
                                                                                                  "invalid"));

        static Unauthorized unauthorized(FieldError error) {
            return new Unauthorized(error, error.render());
        }
    }

    /// 422: request validation, one entry per invalid field (B-20).
    record RequestInvalid(List<FieldError> errors, String message) implements ConduitError {
        public static RequestInvalid requestInvalid(String field, String text) {
            return requestInvalid(FieldError.fieldError(field, text), List.of());
        }

        public static RequestInvalid blank(String field) {
            return requestInvalid(field, "can't be blank");
        }

        /// `can't be blank` for each field: what a body without its wrapper object (`{"user": ...}`) means. At least one
        /// field: an empty error list has no meaning, so the signature cannot express it.
        public static RequestInvalid blanks(String first, String... rest) {
            return requestInvalid(blankError(first),
                                  Arrays.stream(rest).map(RequestInvalid::blankError).toList());
        }

        private static FieldError blankError(String field) {
            return FieldError.fieldError(field, "can't be blank");
        }

        static RequestInvalid requestInvalid(FieldError first, List<FieldError> rest) {
            var errors = Stream.concat(Stream.of(first), rest.stream()).toList();

            return new RequestInvalid(errors, ConduitError.render(errors));
        }

        /// Flattens the composite cause `Result.all` produces into one 422 carrying every field error. A cause that is
        /// not a validation failure is kept as it is: merging it would turn a 500 into a 422.
        public static Cause merged(Cause cause) {
            var causes = cause.stream().toList();
            var allInvalid = !causes.isEmpty() && causes.stream().allMatch(RequestInvalid.class::isInstance);

            return allInvalid
                   ? flattened(causes)
                   : cause;
        }

        private static RequestInvalid flattened(List<Cause> causes) {
            var errors = causes.stream()
                               .map(RequestInvalid.class::cast)
                               .flatMap(invalid -> invalid.errors()
                                                          .stream())
                               .toList();

            return requestInvalid(errors.getFirst(),
                                  errors.subList(1, errors.size()));
        }
    }

    /// 404: the addressed article, profile or comment does not exist.
    record NotFound(String key, String message) implements ConduitError {
        public static final NotFound ARTICLE = notFound("article");
        public static final NotFound PROFILE = notFound("profile");
        public static final NotFound COMMENT = notFound("comment");

        static NotFound notFound(String key) {
            return new NotFound(key, key + ": not found");
        }
    }

    /// 403: the caller is not the owner.
    record Forbidden(String key, String message) implements ConduitError {
        public static final Forbidden ARTICLE = forbidden("article");
        public static final Forbidden COMMENT = forbidden("comment");

        static Forbidden forbidden(String key) {
            return new Forbidden(key, key + ": forbidden");
        }
    }

    /// 409: username and/or email already taken, every conflicting field reported (B-19).
    record Conflict(List<FieldError> errors, String message) implements ConduitError {
        public static final FieldError USERNAME_TAKEN = FieldError.fieldError("username", "has already been taken");
        public static final FieldError EMAIL_TAKEN = FieldError.fieldError("email", "has already been taken");

        /// At least one field: an empty error list has no meaning, so the signature cannot express it.
        public static Conflict conflict(FieldError first, List<FieldError> rest) {
            var errors = Stream.concat(Stream.of(first), rest.stream()).toList();

            return new Conflict(errors, ConduitError.render(errors));
        }
    }

    private static String render(List<FieldError> errors) {
        return errors.stream()
                     .map(FieldError::render)
                     .collect(Collectors.joining(", "));
    }
}
