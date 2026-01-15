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
        return new TestSliceImpl();
    }
}
