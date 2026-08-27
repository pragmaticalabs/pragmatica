// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/// Suppresses the #519 dead-config-accessor gate (`aether/dead-surface-gate`'s `ConfigKeyLivenessTest`)
/// for one record component, with a mandatory justification — a ticket reference, e.g.
/// `"#675: parsed but never applied by Main.resolveAutoHeal"`.
///
/// Placed here in `aether-config`'s main sources, not test-scoped: it annotates production record
/// components, so it must be on the compile classpath of whatever module declares them. `aether-config`
/// is that module's only consumer today (mirrors the checker's own "widen only when a second consumer
/// arrives" placement rule, #519).
///
/// Deliberately NOT `@SuppressWarnings`, despite mirroring jbct-lint's suppression convention in
/// spirit: `@SuppressWarnings` has `SOURCE` retention (JLS-mandated) and is stripped before class files
/// are generated, so it can't be read reflectively or from bytecode — jbct-lint only sees it because it
/// parses source (CST), which the #519 scanner deliberately does not do. This annotation carries
/// `RUNTIME` retention instead, and targets `RECORD_COMPONENT` + `METHOD` so it can be written once on
/// the record header: an annotation applicable to `METHOD` on a record component is copied by javac
/// onto the generated accessor (JLS record semantics) — verified empirically (compile + reflect) before
/// relying on it, since this is exactly the kind of assumption that reads as correct and silently isn't.
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.RECORD_COMPONENT})
public @interface ConfigKeyLive {
    String value();
}
