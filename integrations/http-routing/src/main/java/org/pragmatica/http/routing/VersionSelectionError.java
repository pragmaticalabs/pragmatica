package org.pragmatica.http.routing;

import org.pragmatica.lang.Cause;

/// Failure outcomes of header-mode API version selection (#198 §7).
///
/// Distinguishes the two client-error cases the dispatcher maps to distinct HTTP statuses:
///   - [UnknownVersion] — the request named a version that the slice does not declare → `404`.
///   - [MissingVersionHeader] — the slice requires a version header and the request omitted it → `400`.
public sealed interface VersionSelectionError extends Cause {
    /// The request named a version the slice does not declare (#198 §7) → `404`.
    record UnknownVersion(int requested) implements VersionSelectionError {
        @Override
        public String message() {
            return "Unknown API version: " + requested;
        }
    }

    /// The slice requires a version header and the request omitted it (#198 §7) → `400`.
    record MissingVersionHeader(String headerName) implements VersionSelectionError {
        @Override
        public String message() {
            return "Missing required version header: " + headerName;
        }
    }
}
