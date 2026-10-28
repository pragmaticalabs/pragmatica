package org.pragmatica.storage;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

/// `gather` must hand back every result, in order, however the completions of its inputs interleave.
///
/// A real [Promise] runs the handlers registered on it in registration order, so the interleaving that broke the two-handler
/// version (the count handler of one input running before its store handler, which happens when a handler is registered
/// while the input is being resolved) cannot be forced with real promises. `gather` takes its subscription as a seam; the
/// test records the handlers and runs them in the order it chooses: the last registered handler of every input first, the
/// others after, so the last input's count is reached before an earlier input's result was stored.
@Timeout(value = 15, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ContentStoreV2GatherTest {
    private final Map<Promise<String>, List<Consumer<Result<String>>>> handlers = new IdentityHashMap<>();

    @Test
    void aHandlerThatRunsLateForOneInput_doesNotLoseItsResult() {
        var inputs = inputs(3);
        var gathered = LocalContentStore.gather(inputs, this::record);

        runLastRegisteredThenTheRest(inputs, List.of(Result.success("v0"), Result.success("v1"), Result.success("v2")));

        assertThat(gathered.isResolved()).isTrue();
        assertThat(gathered.await().unwrap()).containsExactly("v0", "v1", "v2");
    }

    @Test
    void aFailure_failsTheGather_whateverTheOrder() {
        var inputs = inputs(2);
        var gathered = LocalContentStore.gather(inputs, this::record);

        runLastRegisteredThenTheRest(inputs, List.of(Result.success("v0"), Result.<String> failure(Causes.cause("input failed"))));

        assertThat(gathered.isResolved()).isTrue();
        assertThat(gathered.await().isFailure()).isTrue();
    }

    private static List<Promise<String>> inputs(int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(_ -> Promise.<String> promise()).toList();
    }

    private void record(Promise<String> input, Consumer<Result<String>> handler) {
        handlers.computeIfAbsent(input, _ -> new ArrayList<>()).add(handler);
    }

    private void runLastRegisteredThenTheRest(List<Promise<String>> inputs, List<Result<String>> results) {
        for (var i = 0; i < inputs.size(); i++) {
            handlers.get(inputs.get(i)).getLast().accept(results.get(i));
        }

        for (var i = 0; i < inputs.size(); i++) {
            var registered = handlers.get(inputs.get(i));

            for (var h = 0; h < registered.size() - 1; h++) {
                registered.get(h).accept(results.get(i));
            }
        }
    }
}
