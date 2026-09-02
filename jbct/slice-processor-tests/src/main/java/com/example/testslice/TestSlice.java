// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package com.example.testslice;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.http.routing.MultipartRequest;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


/// Test slice for verifying HTTP route generation.
@Slice
public interface TestSlice {
    // Body only (POST)
    Promise<CreateResponse> create(CreateRequest request);
    // Path only (GET with single param)
    Promise<GetResponse> getById(GetByIdRequest request);
    // Path only (GET with multiple params)
    Promise<ItemResponse> getItem(GetItemRequest request);
    // Path with trailing static segment after the param (GET /items/{id}/image) -> trailing spacer
    Promise<byte[]> getItemImage(ItemImageRequest request);
    // Query only (GET with query params)
    Promise<List<SearchResult>> search(SearchRequest request);
    // Path + body (PUT)
    Promise<UpdateResponse> update(UpdateRequest request);
    // Path + query (GET)
    Promise<List<OrderResponse>> getOrders(GetOrdersRequest request);
    // No parameters
    Promise<HealthResponse> health(HealthRequest request);
    // produces text/csv (String return, path param)
    Promise<String> exportCsv(ExportRequest request);
    // produces application/octet-stream (byte[] return, path param)
    Promise<byte[]> download(DownloadRequest request);
    // consumes text/plain (String param)
    Promise<UploadResponse> uploadText(String body);
    // consumes multipart/form-data (MultipartRequest param)
    Promise<UploadResponse> uploadForm(MultipartRequest request);

    static TestSlice testSlice() {
        return new TestSlice() {
            @Override
            public Promise<CreateResponse> create(CreateRequest request) {
                return Promise.success(new CreateResponse(1L, request.name()));
            }

            @Override
            public Promise<GetResponse> getById(GetByIdRequest request) {
                return Promise.success(new GetResponse(request.id(), "Test", "test@example.com"));
            }

            @Override
            public Promise<ItemResponse> getItem(GetItemRequest request) {
                return Promise.success(new ItemResponse(request.itemId(), "Item", 10));
            }

            @Override
            public Promise<byte[]> getItemImage(ItemImageRequest request) {
                return Promise.success(("image-" + request.id()).getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public Promise<List<SearchResult>> search(SearchRequest request) {
                return Promise.success(List.of(new SearchResult(1L, "Result", 0.95)));
            }

            @Override
            public Promise<UpdateResponse> update(UpdateRequest request) {
                return Promise.success(new UpdateResponse(request.id(), request.name(), true));
            }

            @Override
            public Promise<List<OrderResponse>> getOrders(GetOrdersRequest request) {
                return Promise.success(List.of(new OrderResponse(1L, "completed", 99.99)));
            }

            @Override
            public Promise<HealthResponse> health(HealthRequest request) {
                return Promise.success(new HealthResponse("healthy", System.currentTimeMillis()));
            }

            @Override
            public Promise<String> exportCsv(ExportRequest request) {
                return Promise.success("id,name\n" + request.id() + ",test");
            }

            @Override
            public Promise<byte[]> download(DownloadRequest request) {
                return Promise.success(("blob-" + request.id()).getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public Promise<UploadResponse> uploadText(String body) {
                return Promise.success(new UploadResponse("text-1", body.length()));
            }

            @Override
            public Promise<UploadResponse> uploadForm(MultipartRequest request) {
                return Promise.success(new UploadResponse("form-1",
                                                          request.fields().size()));
            }
        };
    }
}
