// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

/// The #519 dead-config-accessor gate: [ConfigKeyLivenessTest] plus the bytecode scanner it stands on
/// ([BytecodeReachability], [ReflectiveConfigExemptions], [ConfigRecordScope], [MethodRef],
/// [ReactorRoots]).
///
/// AUTHORSHIP. The instrument in this package was designed and built by the operator-surface stream
/// (stream C) under #519, and lives here unchanged: same scanner logic, same baseline-and-ratchet
/// assertion, same deliberately loud corpus precondition. Read the class-level notes before touching
/// anything — each one records a failure mode that was hit for real during commissioning (the
/// classpath-corpus false-DEAD, the record `equals/hashCode` false-LIVE, the reflective-binding
/// exemption), and the reasoning is not reconstructible from the code alone.
///
/// WHY IT MOVED. Only the mounting point changed. In `aether/node` the gate could not be satisfied on
/// a clean tree: it scans every module of `aether/pom.xml`'s default reactor by reading each one's
/// `target/classes`, but modules that depend on `node` (`ember` and others) are built strictly after
/// it, so their output does not exist yet when node's tests run. The precondition was right to refuse;
/// the mount was wrong. This module is declared LAST in that `<modules>` list with a test-scoped
/// dependency on every module it scans, which makes Maven itself guarantee the corpus is complete in
/// any build order — see the maintenance contract in this module's `pom.xml`.
///
/// The precondition was NOT softened as part of that move, and must not be. An incomplete corpus is
/// indistinguishable, from inside the sweep, from code with no live callers; a gate that tolerates it
/// reports false DEAD accessors, which is the one failure mode that would disqualify it.
package org.pragmatica.aether.deadsurface;
