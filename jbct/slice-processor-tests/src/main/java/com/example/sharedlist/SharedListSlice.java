// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.sharedlist;

import java.util.List;

import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;


/// Test slice whose routes share container roots: two routes take the same `List<Item>` body, and a third takes it as
/// its body and returns it as its response. The routes holder must list that container once.
@Slice
public interface SharedListSlice {
    Promise<Integer> count(List<Item> request);
    Promise<Integer> total(List<Item> request);
    Promise<List<Item>> echo(List<Item> request);

    static SharedListSlice sharedListSlice() {
        return new SharedListSlice() {
            @Override
            public Promise<Integer> count(List<Item> request) {
                return Promise.success(request.size());
            }

            @Override
            public Promise<Integer> total(List<Item> request) {
                return Promise.success(request.stream().mapToInt(Item::quantity).sum());
            }

            @Override
            public Promise<List<Item>> echo(List<Item> request) {
                return Promise.success(request);
            }
        };
    }
}
