package org.pragmatica.aether.http.adapter;

import org.pragmatica.http.routing.HttpError;
import org.pragmatica.http.routing.HttpStatus;
import org.pragmatica.lang.Cause;


/// Maps application errors to HTTP errors.
///
/// Allows slices to customize how their domain errors are converted to HTTP status codes.
///
/// Usage:
/// ```{@code
/// ErrorMapper mapper = cause -> switch (cause) {
///     case UserNotFound _ -> HttpError.httpError(HttpStatus.NOT_FOUND, cause);
///     case InvalidInput _ -> HttpError.httpError(HttpStatus.BAD_REQUEST, cause);
///     case HttpError he -> he;  // Pass through existing HTTP errors
///     default -> HttpError.httpError(HttpStatus.INTERNAL_SERVER_ERROR, cause);
/// };
/// }```
@FunctionalInterface public interface ErrorMapper {
    HttpError map(Cause cause);

    static ErrorMapper defaultMapper() {
        return cause -> cause instanceof HttpError he
                       ? he
                       : HttpError.httpError(HttpStatus.INTERNAL_SERVER_ERROR, cause);
    }

    default ErrorMapper orElse(ErrorMapper other) {
        return cause -> {
            var result = this.map(cause);
            if (result.status() == HttpStatus.INTERNAL_SERVER_ERROR && !(cause instanceof HttpError)) {return other.map(cause);}
            return result;
        };
    }
}
