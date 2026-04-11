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
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface TerminalOperation {}
