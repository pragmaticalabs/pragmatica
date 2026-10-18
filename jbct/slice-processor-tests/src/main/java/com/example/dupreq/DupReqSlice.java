// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.dupreq;

import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;

import com.example.dupreq.y.Req;


/// Test slice whose routes take request types of two packages with one simple name, `y.Req` (imported) and `z.Req`
/// (qualified): the routes holder must give their nested codecs distinct names.
@Slice
public interface DupReqSlice {
    Promise<String> named(Req request);
    Promise<Long> counted(com.example.dupreq.z.Req request);

    static DupReqSlice dupReqSlice() {
        return new DupReqSlice() {
            @Override
            public Promise<String> named(Req request) {
                return Promise.success(request.name());
            }

            @Override
            public Promise<Long> counted(com.example.dupreq.z.Req request) {
                return Promise.success(request.count());
            }
        };
    }
}
