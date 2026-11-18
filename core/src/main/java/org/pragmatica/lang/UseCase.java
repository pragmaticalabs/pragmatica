package org.pragmatica.lang;

import java.lang.annotation.*;


/// Marks the interface of a JBCT use case.
///
/// `@UseCase` is the anchor the structure rules use to tell a use case node from the grouping
/// levels above it and the step packages below it: a package tree looks the same in both
/// directions, so the annotation, not a naming convention or a marker interface, identifies
/// the use case.
///
/// What it anchors: one use case, the type that declares its `execute` operation.
///
/// What it does not mean:
/// - It is not a deployment unit. Nothing is packaged, registered or exposed because a type
///   carries it.
/// - It is independent of Aether's `@Slice`. Neither implies the other: a slice may expose
///   several operations, and a use case need not be deployed as a slice.
///
/// Retention is `SOURCE`, like the other core annotations: JBCT lint works on syntax and
/// needs no bytecode or reflection access.
///
/// Example:
/// ```java
/// @UseCase
/// public interface RegisterUser {
///     Promise<User> execute(Request request);
/// }
/// ```
///
/// @see org.pragmatica.lang.Contract
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface UseCase {}
