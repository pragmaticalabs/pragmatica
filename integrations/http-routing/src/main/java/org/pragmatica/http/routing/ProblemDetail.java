package org.pragmatica.http.routing;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

/// RFC 9457 Problem Details for HTTP APIs.
///
/// Provides a standardized format for returning error information in HTTP responses.
/// See [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) (supersedes RFC 7807, fully backward-compatible).
///
/// Serialized as `application/problem+json`. All five standard members are supported.
/// Extension member `requestId` is mandatory for request tracing per Aether conventions.
///
/// @param type      URI reference identifying the problem type (default: "about:blank")
/// @param title     Short human-readable summary of the problem type
/// @param status    HTTP status code
/// @param detail    Human-readable explanation specific to this occurrence (Section 3.1.4)
/// @param instance  URI reference identifying the specific occurrence (Section 3.1.5)
/// @param requestId Unique identifier for the request (extension member per Section 3.2)
public record ProblemDetail(String type,
                            String title,
                            int status,
                            Option<String> detail,
                            Option<String> instance,
                            String requestId) {
    private static final String DEFAULT_TYPE = "about:blank";

    /// Create a ProblemDetail from an HttpError.
    ///
    /// @param error     The HTTP error
    /// @param instance  Request path/URI
    /// @param requestId Request identifier
    /// @return ProblemDetail instance
    public static ProblemDetail fromHttpError(HttpError error, String instance, String requestId) {
        return new ProblemDetail(DEFAULT_TYPE,
                                 error.status()
                                      .message(),
                                 error.status()
                                      .code(),
                                 Option.option(extractDetail(error)),
                                 Option.option(instance),
                                 requestId);
    }

    /// Create a ProblemDetail from a generic Cause (defaults to 500 Internal Server Error).
    ///
    /// @param cause     The error cause
    /// @param instance  Request path/URI
    /// @param requestId Request identifier
    /// @return ProblemDetail instance
    public static ProblemDetail fromCause(Cause cause, String instance, String requestId) {
        return new ProblemDetail(DEFAULT_TYPE,
                                 HttpStatus.INTERNAL_SERVER_ERROR.message(),
                                 HttpStatus.INTERNAL_SERVER_ERROR.code(),
                                 Option.option(cause.message()),
                                 Option.option(instance),
                                 requestId);
    }

    /// Create a ProblemDetail with custom type URI.
    ///
    /// @param type      Problem type URI
    /// @param status    HTTP status
    /// @param detail    Error detail message
    /// @param instance  Request path/URI
    /// @param requestId Request identifier
    /// @return ProblemDetail instance
    public static ProblemDetail problemDetail(String type,
                                              HttpStatus status,
                                              String detail,
                                              String instance,
                                              String requestId) {
        return new ProblemDetail(type,
                                 status.message(),
                                 status.code(),
                                 Option.option(detail),
                                 Option.option(instance),
                                 requestId);
    }

    /// Create a ProblemDetail with default type.
    ///
    /// @param status    HTTP status
    /// @param detail    Error detail message
    /// @param instance  Request path/URI
    /// @param requestId Request identifier
    /// @return ProblemDetail instance
    public static ProblemDetail problemDetail(HttpStatus status,
                                              String detail,
                                              String instance,
                                              String requestId) {
        return new ProblemDetail(DEFAULT_TYPE,
                                 status.message(),
                                 status.code(),
                                 Option.option(detail),
                                 Option.option(instance),
                                 requestId);
    }

    private static String extractDetail(HttpError error) {
        // Get the origin cause message, not the full chain
        return error.source()
                    .map(Cause::message)
                    .or(error.status()
                             .message());
    }
}
