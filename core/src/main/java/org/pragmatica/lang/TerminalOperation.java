package org.pragmatica.lang;

import java.lang.annotation.*;

/// Declares that a method intentionally performs a blocking terminal operation.
///
/// JBCT prefers non-blocking composition via `.map()`/`.flatMap()` over blocking
/// `.await()` calls. However, certain methods are inherently terminal — CLI entry
/// points, server lifecycle (startup/shutdown), dedicated background threads.
///
/// Use `@TerminalOperation` instead of `@SuppressWarnings("JBCT-PAT-03")` to express
/// this intent. Unlike `@SuppressWarnings`, which says "I know this is wrong, ignore it,"
/// `@TerminalOperation` says "blocking is the correct behavior for this method."
///
/// Can be applied at method level or class level (covers all methods in the class).
///
/// Legitimate terminal contexts: CLI entry points and command executors, server/runtime
/// lifecycle (startup/shutdown), dedicated background threads. Test code never needs this
/// annotation — `await()` is always allowed in tests.
///
/// Apply at WRITE time: writing `.await()` outside tests means either restructuring to stay
/// in the monadic chain or annotating the method immediately — not waiting for lint.
///
/// Intent-annotation family: `@Contract` (signature dictated externally),
/// `@TerminalOperation` (blocking is correct), `@NullReturn` (null return is the contract).
///
/// Example:
/// ```java
/// // CLI entry point — blocking is correct
/// @TerminalOperation
/// Result<String> executeCommand(String[] args) {
///     return httpClient.send(request).await()
///                      .map(Response::body);
/// }
///
/// // Server shutdown — must block until complete
/// @TerminalOperation
/// void shutdown() {
///     server.stop().await();
/// }
/// ```
///
/// @see org.pragmatica.lang.Promise
/// @see org.pragmatica.lang.Result
/// @see org.pragmatica.lang.Contract
/// @see org.pragmatica.lang.NullReturn
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface TerminalOperation {}
