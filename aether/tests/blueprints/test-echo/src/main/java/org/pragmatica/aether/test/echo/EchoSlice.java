// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.test.echo;

import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


@Slice
public interface EchoSlice {
    record EchoRequest(String body) {
        public static Result<EchoRequest> echoRequest(String body) {
            return success(new EchoRequest(Option.option(body).or("")));
        }
    }

    record EchoResponse(String body) {
        public static EchoResponse echoResponse(String body) {
            return new EchoResponse(body);
        }
    }

    record HealthResponse(String status) {
        public static HealthResponse healthResponse() {
            return new HealthResponse("healthy");
        }
    }

    Promise<EchoResponse> echo(EchoRequest request);
    Promise<HealthResponse> health();

    static EchoSlice echoSlice() {
        return new echoSlice();
    }

    record echoSlice() implements EchoSlice {
        @Override
        public Promise<EchoResponse> echo(EchoRequest request) {
            return Promise.success(EchoResponse.echoResponse(request.body()));
        }

        @Override
        public Promise<HealthResponse> health() {
            return Promise.success(HealthResponse.healthResponse());
        }
    }
}
