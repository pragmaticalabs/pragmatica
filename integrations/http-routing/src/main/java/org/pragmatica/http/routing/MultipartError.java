package org.pragmatica.http.routing;

import org.pragmatica.lang.Cause;

/// Errors that can occur during multipart request parsing.
public sealed interface MultipartError extends Cause {
    MultipartError NOT_MULTIPART = General.NOT_MULTIPART;
    MultipartError MISSING_CONTENT_TYPE = General.MISSING_CONTENT_TYPE;

    /// The request is not a multipart request.
    enum General implements MultipartError {
        NOT_MULTIPART("Request is not multipart/form-data"),
        MISSING_CONTENT_TYPE("Missing Content-Type header");

        private final String message;

        General(String message) {
            this.message = message;
        }

        @Override
        public String message() {
            return message;
        }
    }

    /// Failed to parse the multipart body.
    record ParseFailed(String detail) implements MultipartError {
        @Override
        public String message() {
            return "Multipart parse failed: " + detail;
        }
    }
}
