// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.Locale;

import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;


/// The global deployment-engine selector of the harness (FSM replacement plan F1 item 7): the system property
/// [#PROPERTY] (`-Daether.deployment.engine=legacy|fsm2`) picks the engine every [EmberCluster] constructs its nodes
/// with, so one switch runs the Ember corpus, the forge tests and Forge itself on FSM2. Absent means LEGACY, the
/// production default. Harness-scoped: a node read from TOML has no engine key until the flip (plan D.3), so nothing
/// here reaches a production node.
///
/// A value that names no engine is a failure, never LEGACY: a misspelt `fsm2` that ran LEGACY would make an FSM2 run
/// report green while exercising the old engine. [EmberCluster] refuses to start on it.
public sealed interface EngineSelector {
    String PROPERTY = "aether.deployment.engine";

    Fn1<Cause, String> UNKNOWN_ENGINE = Causes.forOneValue("-D" + PROPERTY
                                                          + "=%s names no deployment engine; use legacy or fsm2");

    /// The engine the property selects: absent or blank -> LEGACY; `legacy` / `fsm2` in any case; anything else fails.
    static Result<DeploymentEngine> engine(Option<String> raw) {
        return raw.filter(Verify.Is::notBlank)
                  .map(String::trim)
                  .map(EngineSelector::named)
                  .or(Result.success(DeploymentEngine.LEGACY));
    }

    /// [#engine] over this JVM's system property.
    static Result<DeploymentEngine> fromSystemProperty() {
        return engine(Option.option(System.getProperty(PROPERTY)));
    }

    private static Result<DeploymentEngine> named(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "legacy" -> Result.success(DeploymentEngine.LEGACY);
            case "fsm2" -> Result.success(DeploymentEngine.FSM2);
            default -> UNKNOWN_ENGINE.apply(value).result();
        };
    }

    record unused() implements EngineSelector {}
}
