// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.successstatus;

import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// Routes declaring a success status and Location in routes.toml (#2805), plus the undeclared defaults.
@Slice
public interface SuccessStatusSlice {
    Promise<Article> create(ArticleRequest request);
    Promise<Article> register(ArticleRequest request);
    Promise<Draft> draft(ArticleRequest request);
    Promise<Unit> enqueue(ArticleRequest request);
    Promise<Unit> remove(SlugRequest request);
    Promise<Option<Article>> find(SlugRequest request);
    Promise<Article> broken();
    Promise<Article> locate(ArticleRequest request);
    Promise<Option<Article>> lookup(SlugRequest request);

    static SuccessStatusSlice successStatusSlice() {
        return new SuccessStatusSlice() {
            @Override
            public Promise<Article> create(ArticleRequest request) {
                return Promise.success(new Article(request.slug(), request.title()));
            }

            @Override
            public Promise<Article> register(ArticleRequest request) {
                return Promise.success(new Article(request.slug(), request.title()));
            }

            @Override
            public Promise<Draft> draft(ArticleRequest request) {
                return Promise.success(new Draft(new DraftId(request.slug()), request.title()));
            }

            @Override
            public Promise<Unit> enqueue(ArticleRequest request) {
                return Promise.unitPromise();
            }

            @Override
            public Promise<Unit> remove(SlugRequest request) {
                return Promise.unitPromise();
            }

            @Override
            public Promise<Option<Article>> find(SlugRequest request) {
                return Promise.success(Option.option(request.slug())
                                             .filter("known"::equals)
                                             .map(slug -> new Article(slug, "Known")));
            }

            // A JBCT violation on purpose: a null success value.
            @Override
            public Promise<Article> broken() {
                return Promise.success(null);
            }

            @Override
            public Promise<Article> locate(ArticleRequest request) {
                return Promise.success(new Article(request.slug(), request.title()));
            }

            @Override
            public Promise<Option<Article>> lookup(SlugRequest request) {
                return find(request);
            }
        };
    }
}
