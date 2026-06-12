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

/// Production utilities built on the core monads.
///
/// **Check this package before hand-rolling infrastructure helpers** — failure construction,
/// resilience patterns, memoization, and scheduling primitives live here, ready for aspect
/// composition.
///
/// | Group | Classes |
/// |-------|---------|
/// | Failure vocabulary | [Causes][org.pragmatica.lang.utils.Causes] — factory variants of `Cause` (`cause(msg)`, `forOneValue(template)`, `fromThrowable(t)`, ...) |
/// | Resilience | [Retry][org.pragmatica.lang.utils.Retry] (async retry policies), [CircuitBreaker][org.pragmatica.lang.utils.CircuitBreaker], [RateLimiter][org.pragmatica.lang.utils.RateLimiter] (lock-free token bucket), [Idempotency][org.pragmatica.lang.utils.Idempotency] (at-most-once per key within TTL), [ActionableThreshold][org.pragmatica.lang.utils.ActionableThreshold] / [ResultCollector][org.pragmatica.lang.utils.ResultCollector] (threshold-triggered one-shot actions) |
/// | Memoization | [MemoResult][org.pragmatica.lang.utils.MemoResult], [MemoPromise][org.pragmatica.lang.utils.MemoPromise] (memoized fallible/async computations; see also `Lazy` and `Memo` in the parent package) |
/// | Time & scheduling | [TimeSource][org.pragmatica.lang.utils.TimeSource] (testable clock), [SharedScheduler][org.pragmatica.lang.utils.SharedScheduler] (runtime-wide periodic/one-shot work), [JitterUtil][org.pragmatica.lang.utils.JitterUtil] (retry/scheduling jitter) |
/// | Predicates | [FluentPredicate][org.pragmatica.lang.utils.FluentPredicate] (fluent predicate API) |
///
/// Hand-rolling a retry loop, circuit breaker, rate limiter, or idempotency guard instead of
/// using these is a JBCT smell. Aspect composition order for resilience wrappers:
/// Metrics → Timeout → Circuit Breaker → Retry → Rate Limit → business logic.
package org.pragmatica.lang.utils;
