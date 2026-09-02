package org.pragmatica.lang;

import java.lang.annotation.*;


/// Declares that a method intentionally uses a void return type as part of its contract.
///
/// JBCT requires all business methods to return one of four types: `T`, `Option<T>`,
/// `Result<T>`, or `Promise<T>`. However, certain methods are inherently side-effecting
/// and void by contract — framework callbacks, fire-and-forget mutations, event handlers.
///
/// Use `@Contract` instead of `@SuppressWarnings("JBCT-RET-01")` to express this intent.
/// Unlike `@SuppressWarnings`, which says "I know this is wrong, ignore it," `@Contract`
/// says "void is the correct return type for this method's contract."
///
/// **Lint semantics: `@Contract` suppresses ALL JBCT rules for the annotated method or class** —
/// it declares the entire signature dictated by an external API contract, not just the void
/// return. Because it is a blanket exemption, apply it deliberately, never for convenience.
///
/// Decision procedure — apply at WRITE time, not after lint complains:
/// 1. Can the method return `Unit` (`Result<Unit>` / `Promise<Unit>`) instead of `void`?
///    Do that; no annotation needed.
/// 2. Is the signature externally dictated (framework callback, JDK functional contract,
///    Maven Mojo, annotation processor)? Annotate `@Contract` immediately.
/// 3. Neither? The signature is yours to fix — do not annotate.
///
/// Intent-annotation family: `@Contract` (signature dictated externally),
/// `@TerminalOperation` (blocking is correct), `@NullReturn` (null return is the contract).
///
/// Can be applied at method level or class level (covers all methods in the class).
///
/// Example:
/// ```java
/// // Framework callback — void required by messaging contract
/// @Contract
/// void onEventReceived(Event event) { ... }
///
/// // Fire-and-forget mutation — no meaningful return value
/// @Contract
/// void recordMetric(String name, double value) { ... }
/// ```
///
/// @see org.pragmatica.lang.Result
/// @see org.pragmatica.lang.Unit
/// @see org.pragmatica.lang.TerminalOperation
/// @see org.pragmatica.lang.NullReturn
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Contract {}
