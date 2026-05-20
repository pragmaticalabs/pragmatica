package org.pragmatica.http.routing;

import org.pragmatica.lang.Cause;

/// Mixin interface for `Cause` types that can declare their HTTP status mapping.
///
/// Allows sealed `*Error` hierarchies to project semantic codes (400/404/409/422/500/504/...)
/// onto the wire without having to wrap themselves in `HttpError.httpError(status, cause)`
/// at every emission site. The management-plane error funnel
/// (`ProblemResponses.writeProblem(Cause, ...)`) consults `httpStatus()` if the cause
/// implements this interface; otherwise it defaults to `INTERNAL_SERVER_ERROR`.
///
/// `HttpError` extends this interface so its `status()` method satisfies the contract
/// transparently — existing call sites continue to work, and new sealed error types
/// just add `implements HttpStatusAware` with a `default httpStatus()` at the interface
/// level plus per-variant overrides where semantics differ.
public interface HttpStatusAware extends Cause {
    HttpStatus httpStatus();
}
