// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.testkit.fake;

import org.pragmatica.lang.Option;


/// A single outbound HTTP interaction recorded by [FakeHttpClient] for assertions (spec §3.2 `httpCalls`).
public record HttpCall(String method, String path, Option<String> body) {}
