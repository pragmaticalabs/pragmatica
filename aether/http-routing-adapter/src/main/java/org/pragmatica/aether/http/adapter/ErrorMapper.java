package org.pragmatica.aether.http.adapter;

import org.pragmatica.http.routing.HttpError;
import org.pragmatica.http.routing.HttpStatus;
import org.pragmatica.lang.Cause;

/**
 * Maps application errors to HTTP errors.
 * <p>
 * Allows slices to customize how their domain errors are converted to HTTP status codes.
 * <p>
 * Usage:
 * <pre>{@code
 * ErrorMapper mapper = cause -> switch (cause) {
 *     case UserNotFound _ -> HttpError.httpError(HttpStatus.NOT_FOUND, cause);
 *     case InvalidInput _ -> HttpError.httpError(HttpStatus.BAD_REQUEST, cause);
 *     case HttpError he -> he;  // Pass through existing HTTP errors
 *     default -> HttpError.httpError(HttpStatus.INTERNAL_SERVER_ERROR, cause);
 * };
 * }</pre>
 */
@FunctionalInterface
public interface ErrorMapper {
    /**
     * Map a cause to an HTTP error.
     *
     * @param cause the error cause
     * @return HTTP error with appropriate status code
     */
    HttpError map(Cause cause);

    /**
     * Default mapper that passes through HttpError and maps others to 500.
     *
     * @return default error mapper
     */
    static ErrorMapper defaultMapper() {
        return cause -> cause instanceof HttpError he
                        ? he
                        : HttpError.httpError(HttpStatus.INTERNAL_SERVER_ERROR, cause);
    }

    /**
     * Combine this mapper with another, trying this one first.
     * If this mapper returns a 500 error for a non-HttpError cause,
     * the other mapper is tried instead.
     *
     * @param other fallback mapper
     * @return combined mapper
     */
    default ErrorMapper orElse(ErrorMapper other) {
        return cause -> {
            var result = this.map(cause);
            if (result.status() == HttpStatus.INTERNAL_SERVER_ERROR && !(cause instanceof HttpError)) {
                return other.map(cause);
            }
            return result;
        };
    }
}
