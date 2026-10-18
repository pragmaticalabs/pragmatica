package org.pragmatica.http.routing;

import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.utils.Causes;


/// Errors that can occur during multipart request parsing.
///
/// Each declares its status ([HttpStatusAware]): 415 when the request is not multipart at all, 400 when it claims
/// to be but its body is malformed, and 500 only when a well-formed body could not be read on this side. Each
/// message is fixed text: it never carries a parser exception's class or message, which stay in the origin for the
/// server log. [#asHttpError(Cause)] is the boundary wrapping: an [HttpError] of the declared status whose client
/// text is that fixed message (an [HttpError] renders its origin's client text, so the origin of the wrapper must be
/// this error, never the decoder exception below it).
public sealed interface MultipartError extends HttpStatusAware {
    MultipartError NOT_MULTIPART = General.NOT_MULTIPART;
    MultipartError MISSING_CONTENT_TYPE = General.MISSING_CONTENT_TYPE;

    /// The client-facing form of a multipart failure: a typed [HttpError] of the declared status. Any other cause
    /// passes through unchanged.
    static Cause asHttpError(Cause cause) {
        return cause instanceof MultipartError error
               ? error.httpStatus()
                      .with(error)
               : cause;
    }

    /// Fixed-text failures: the request's Content-Type or its body framing is wrong.
    enum General implements MultipartError {
        NOT_MULTIPART("Request is not multipart/form-data", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
        MISSING_CONTENT_TYPE("Missing Content-Type header", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
        MISSING_BOUNDARY("Multipart Content-Type has no boundary parameter", HttpStatus.BAD_REQUEST),
        MISSING_CLOSE_DELIMITER("Multipart body is incomplete or malformed: no closing boundary delimiter",
                                HttpStatus.BAD_REQUEST);

        private final String message;
        private final HttpStatus status;

        General(String message, HttpStatus status) {
            this.message = message;
            this.status = status;
        }

        @Override
        public String message() {
            return message;
        }

        @Override
        public HttpStatus httpStatus() {
            return status;
        }
    }

    /// The body could not be decoded as multipart/form-data (a part without Content-Disposition, an unsupported
    /// transfer encoding, broken part framing). The decoder's exception is the origin, kept for the server log only.
    @SuppressWarnings("JBCT-CAUSE-04")
    record ParseFailed(Cause origin, String message) implements MultipartError, Cause.Wrapped {
        static final Fn1<ParseFailed, Cause> FACTORY = Causes.forOneValue("Multipart body could not be parsed",
                                                                          ParseFailed::new);

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.BAD_REQUEST;
        }
    }

    /// A well-formed body could not be read on this side (for example a part spilled to a full temporary disk).
    /// Not the client's fault, hence 500.
    @SuppressWarnings("JBCT-CAUSE-04")
    record ReadFailed(Cause origin, String message) implements MultipartError, Cause.Wrapped {
        static final Fn1<ReadFailed, Cause> FACTORY = Causes.forOneValue("Multipart body could not be read",
                                                                         ReadFailed::new);

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
    }

    /// The body has more parts than the parser's limit (see [MultipartParser#DEFAULT_MAX_PARTS]).
    record TooManyParts(int maxParts, String message) implements MultipartError {
        static final Fn1<TooManyParts, Integer> FACTORY = Causes.forOneValue("Multipart body has more than %d parts",
                                                                            TooManyParts::new);

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.BAD_REQUEST;
        }
    }
}
