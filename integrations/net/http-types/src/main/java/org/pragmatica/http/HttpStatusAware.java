/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
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
 */

package org.pragmatica.http;

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
