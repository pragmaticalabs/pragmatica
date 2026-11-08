// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.upload;

import java.util.function.Supplier;

import org.pragmatica.aether.http.handler.UploadHandle;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.QueryParams;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// A request whose body the ingress already stored (U2): the head and trailers the server delivered, with an empty body,
/// and the [UploadHandle] of what was stored. It travels the normal dispatch path, where the conversion to the slice's
/// request attaches the handle ([#uploadOf]).
///
/// `discard` deletes what was stored; the dispatch path runs it when the request is answered without reaching any slice
/// (no route serves it any more, or it is no longer authorized), since no slice will ever claim those objects. Its promise
/// resolves once the deletes have finished, so a caller that must not answer before the objects are gone ([#discardedUpload])
/// can wait for it.
///
/// @param head    the request as the server delivered it to the sink, its body empty
/// @param upload  what the ingress stored for it
/// @param discard deletes the stored objects, resolving when done; safe to run more than once
public record IngestedRequest(HttpRequest head, UploadHandle upload, Supplier<Promise<Unit>> discard) implements HttpRequest {
    public static IngestedRequest ingestedRequest(HttpRequest head,
                                                  UploadHandle upload,
                                                  Supplier<Promise<Unit>> discard) {
        return new IngestedRequest(head, upload, discard);
    }

    /// The stored upload `request` carries, if the ingress stored its body.
    public static Option<UploadHandle> uploadOf(HttpRequest request) {
        return request instanceof IngestedRequest ingested
               ? Option.some(ingested.upload())
               : Option.none();
    }

    /// Deletes what the ingress stored for `request`, if it stored anything: the request will reach no slice.
    /// Fire and forget: the caller does not wait for the deletes.
    @Contract
    public static void discardUpload(HttpRequest request) {
        discardedUpload(request).onResultRun(() -> {});
    }

    /// Deletes what the ingress stored for `request`, if it stored anything; resolves once the deletes have finished.
    public static Promise<Unit> discardedUpload(HttpRequest request) {
        return request instanceof IngestedRequest ingested
               ? ingested.discard()
                         .get()
               : Promise.unitPromise();
    }

    @Override
    public String requestId() {
        return head.requestId();
    }

    @Override
    public HttpMethod method() {
        return head.method();
    }

    @Override
    public String path() {
        return head.path();
    }

    @Override
    public Headers headers() {
        return head.headers();
    }

    @Override
    public QueryParams queryParams() {
        return head.queryParams();
    }

    @Override
    public byte[] body() {
        return head.body();
    }

    @Override
    public Headers trailers() {
        return head.trailers();
    }
}
