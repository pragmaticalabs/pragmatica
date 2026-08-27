// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package com.example.factoryslice;

import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;


/// Test slice covering the request-record construction rule (#605): where the request record
/// declares a `static Result<Self> factory(components...)`, every generated route constructs
/// through it and maps a validation failure to a typed 400; where it does not, the
/// canonical-constructor path stands unchanged.
///
/// The four methods cover the three construction sites the rule reaches -- pure body (Jackson
/// already built the record, so the route decomposes it through its accessors), merged path + body,
/// and path-only -- plus the no-factory control.
@Slice
public interface FactorySlice {
    // Pure body, request record declares a validating factory
    Promise<ShortResponse> shorten(ShortenRequest request);
    // Pure body, request record declares no factory -- byte-identical canonical-constructor path
    Promise<ShortResponse> plain(PlainRequest request);
    // Path + body merged args, request record declares a validating factory
    Promise<ShortResponse> updateItem(UpdateItemRequest request);
    // Path only, request record declares a validating factory
    Promise<ShortResponse> lookup(LookupRequest request);

    static FactorySlice factorySlice() {
        return new FactorySlice() {
            @Override
            public Promise<ShortResponse> shorten(ShortenRequest request) {
                return Promise.success(new ShortResponse(request.url()));
            }

            @Override
            public Promise<ShortResponse> plain(PlainRequest request) {
                return Promise.success(new ShortResponse(request.note()));
            }

            @Override
            public Promise<ShortResponse> updateItem(UpdateItemRequest request) {
                return Promise.success(new ShortResponse(request.name()));
            }

            @Override
            public Promise<ShortResponse> lookup(LookupRequest request) {
                return Promise.success(new ShortResponse(request.code()));
            }
        };
    }
}
