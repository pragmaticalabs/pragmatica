package format.examples;

import java.util.List;
import java.util.UUID;


public class StatementChains {
    void longChainBreaks() {
        slice.execute(new Request(UUID.randomUUID().toString(),
                                  "STANDARD"))
             .await()
             .onSuccess(r -> fail("Expected PriceNotFound"))
             .onFailure(cause -> check(cause.message()));
    }

    void shortChainStaysFlat(List<String> list) {
        list.stream().map(String::trim).toList();
    }

    Slice slice;

    void fail(String s) {}

    void check(String s) {}

    interface Slice {
        Slice execute(Request r);
        Slice await();
        Slice onSuccess(Object o);
        Slice onFailure(Object o);
    }

    record Request(String id, String type) {}
}
