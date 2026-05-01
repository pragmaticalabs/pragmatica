package org.pragmatica.lang;

import java.lang.annotation.*;

/// Declares that a method intentionally returns `null` as part of its contract.
///
/// JBCT requires methods to use `Option<T>` for absent values rather than `null`.
/// However, certain utility functions in functional contexts intentionally produce
/// `null` — side-effect-only fold branches, JDK callback contracts (`Map.compute`,
/// `Map.merge`, `computeIfPresent`) where `null` signals removal, and similar.
///
/// Use `@NullReturn` instead of `@SuppressWarnings("JBCT-RET-03")` to express this intent.
/// Unlike `@SuppressWarnings`, which says "I know this is wrong, ignore it,"
/// `@NullReturn` says "null is the correct return value for this method's contract."
///
/// Can be applied at method level or class level (covers all methods in the class).
///
/// Example:
/// ```java
/// // Side-effect fold branch — value is discarded
/// @NullReturn
/// static <R, T> R toNull(T value) { return null; }
///
/// // Map.computeIfPresent callback — null removes the entry
/// @NullReturn
/// private static StreamEntry removeIfStillIdle(StreamEntry current, ...) { ... }
/// ```
///
/// @see org.pragmatica.lang.Option
/// @see org.pragmatica.lang.Contract
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface NullReturn {}
