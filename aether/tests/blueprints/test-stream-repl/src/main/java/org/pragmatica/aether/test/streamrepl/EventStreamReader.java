// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.test.streamrepl;

import org.pragmatica.aether.slice.StreamAccess;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


@ResourceQualifier(type = StreamAccess.class, config = "streams.repl-failover-events") @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.PARAMETER) public@interface EventStreamReader {}
