// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.pathbody;

import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


/// Test slice for #2957: routes that merge path parameters with a JSON body, in the Conduit shapes, plus records whose
/// path-bound component must stay required because another decode of the same record needs it.
@Slice
public interface PathBodySlice {
    Promise<Echo> addComment(AddComment request);
    Promise<Echo> updateArticle(UpdateArticle request);
    Promise<Echo> renameArticle(RenameArticle request);
    Promise<Echo> mergedFirst(MergedFirst request);
    Promise<Echo> mergedFirstPlain(MergedFirst request);
    Promise<Echo> plainFirstPlain(PlainFirst request);
    Promise<Echo> plainFirst(PlainFirst request);
    Promise<Echo> nested(NestedNote request);
    Promise<Echo> envelope(Envelope request);

    static PathBodySlice pathBodySlice() {
        return new PathBodySlice() {
            @Override
            public Promise<Echo> addComment(AddComment request) {
                return echo("slug=" + request.slug() + ",body=" + request.comment().body().or("-"));
            }

            @Override
            public Promise<Echo> updateArticle(UpdateArticle request) {
                return echo("slug=" + request.slug()
                           + ",title=" + request.article()
                                                .title()
                                                .applyTo(Option.none())
                                                .or("-"));
            }

            @Override
            public Promise<Echo> renameArticle(RenameArticle request) {
                return echo("slug=" + request.slug() + ",title=" + request.title());
            }

            @Override
            public Promise<Echo> mergedFirst(MergedFirst request) {
                return echo("id=" + request.id() + ",text=" + request.text());
            }

            @Override
            public Promise<Echo> mergedFirstPlain(MergedFirst request) {
                return echo("id=" + request.id() + ",text=" + request.text());
            }

            @Override
            public Promise<Echo> plainFirstPlain(PlainFirst request) {
                return echo("id=" + request.id() + ",text=" + request.text());
            }

            @Override
            public Promise<Echo> plainFirst(PlainFirst request) {
                return echo("id=" + request.id() + ",text=" + request.text());
            }

            @Override
            public Promise<Echo> nested(NestedNote request) {
                return echo("id=" + request.id() + ",text=" + request.text());
            }

            @Override
            public Promise<Echo> envelope(Envelope request) {
                return echo("id=" + request.inner().id() + ",text=" + request.inner().text());
            }
        };
    }

    private static Promise<Echo> echo(String detail) {
        return Promise.success(new Echo(detail));
    }
}
