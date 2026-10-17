// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.wireform;

import java.util.List;

import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


/// Test slice for the one-wire-form rule: generated codecs and the Jackson fallback write and read the same
/// bytes, value objects unwrapped, and a type Jackson shapes through its annotations never gets a generated codec.
@Slice
public interface WireFormSlice {
    Promise<Accounts> signup(Signup request);

    // A List body is decoded by the routes holder's generated container codec (CONTAINERS), not the fallback.
    Promise<Accounts> signupAll(List<Signup> request);

    Promise<Shape> shape(ShapeRequest request);

    Promise<Drawing> drawing(ShapeRequest request);

    Promise<Renamed> renamed(ShapeRequest request);

    Promise<Note> note(ShapeRequest request);

    static WireFormSlice wireFormSlice() {
        return new WireFormSlice() {
            @Override
            public Promise<Accounts> signup(Signup request) {
                return Promise.success(new Accounts(List.of(request.email())));
            }

            @Override
            public Promise<Accounts> signupAll(List<Signup> request) {
                return Promise.success(new Accounts(request.stream().map(Signup::email).toList()));
            }

            @Override
            public Promise<Shape> shape(ShapeRequest request) {
                return Promise.success(new Circle(request.r()));
            }

            @Override
            public Promise<Drawing> drawing(ShapeRequest request) {
                return Promise.success(new Drawing(new Circle(request.r())));
            }

            @Override
            public Promise<Renamed> renamed(ShapeRequest request) {
                return Promise.success(new Renamed("bob"));
            }

            @Override
            public Promise<Note> note(ShapeRequest request) {
                return Promise.success(new Note("1", Option.none()));
            }
        };
    }
}
