// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/// Required on every test class that probes ports through [EmberTestPorts]: it gives each test its own back-off
/// budget ([EmberPortBudgetReset]). A class without it fails loudly on its first probe instead of sharing a stale budget.
/// Registered explicitly, not by extension auto-detection, which would activate every extension on the classpath.
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ExtendWith(EmberPortBudgetReset.class)
public @interface PortBudget {}
