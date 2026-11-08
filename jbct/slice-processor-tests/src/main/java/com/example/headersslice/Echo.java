// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.headersslice;

import java.util.List;

import org.pragmatica.aether.http.handler.RequestHeaders;


/// What a [HeadersSlice] method saw: the first and all values of the `x-probe` header, and which route-bound value
/// (path, query or body text) reached it beside the headers.
public record Echo(String first, List<String> all, String detail) {
    public static Echo echo(RequestHeaders headers, String detail) {
        return new Echo(headers.first("x-probe").or("none"),
                        headers.all("x-probe"),
                        detail);
    }
}
