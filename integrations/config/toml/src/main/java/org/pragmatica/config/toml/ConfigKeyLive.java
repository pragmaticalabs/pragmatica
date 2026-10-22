// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.config.toml;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/// Suppresses the #519 dead-config-accessor gate (`aether/dead-surface-gate`'s `ConfigKeyLivenessTest`)
/// for one record component, with a mandatory justification — a ticket reference, e.g.
/// `"#675: parsed but never applied by Main.resolveAutoHeal"`.
///
/// Placed in the `toml` module's main sources, not test-scoped: it annotates production record
/// components, so it must be on the compile classpath of whatever module declares them. The gate walks
/// records declared in `toml` (`TomlDocument`, reached through `SourceProfile.nodeConfig`) as well as in
/// `aether-config`, and `aether-config` depends on `toml`, so this is the lowest module both can see. It moved
/// here from `aether-config` for #1455 (`TomlDocument`'s per-document read ledger is not a config key).
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
