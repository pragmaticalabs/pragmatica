package com.example.testslice;

import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.util.List;

/**
 * Test slice for verifying HTTP route generation.
 */
@Slice
public interface TestSlice {

    // Body only (POST)
    Promise<CreateResponse> create(CreateRequest request);

    // Path only (GET with single param)
    Promise<GetResponse> getById(GetByIdRequest request);

    // Path only (GET with multiple params)
    Promise<ItemResponse> getItem(GetItemRequest request);

    // Query only (GET with query params)
    Promise<List<SearchResult>> search(SearchRequest request);

    // Path + body (PUT)
    Promise<UpdateResponse> update(UpdateRequest request);

    // Path + query (GET)
    Promise<List<OrderResponse>> getOrders(GetOrdersRequest request);

    // No parameters
    Promise<HealthResponse> health(HealthRequest request);

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
        };
    }
}
