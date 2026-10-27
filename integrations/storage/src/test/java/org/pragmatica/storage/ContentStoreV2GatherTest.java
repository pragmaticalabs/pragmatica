package org.pragmatica.storage;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
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
/// while the input is being resolved) cannot be forced with real promises. These inputs record their handlers instead, and
/// the test runs them in the order it chooses: the last registered handler of every input first, the others after, so the last
/// input's count is reached before an earlier input's result was stored.
@Timeout(value = 15, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ContentStoreV2GatherTest {
    @Test
    void aHandlerThatRunsLateForOneInput_doesNotLoseItsResult() {
        var inputs = List.of(controlled(), controlled(), controlled());
        var gathered = LocalContentStore.gather(inputs.stream().<Promise<String>> map(Controlled::promise).toList());

        for (var input : inputs) {
            input.runLastRegistered(Result.success("v" + inputs.indexOf(input)));
        }

        for (var input : inputs) {
            input.runTheRest(Result.success("v" + inputs.indexOf(input)));
        }

        assertThat(gathered.isResolved()).isTrue();
        assertThat(gathered.await().unwrap()).containsExactly("v0", "v1", "v2");
    }

    @Test
    void aFailure_failsTheGather_whateverTheOrder() {
        var inputs = List.of(controlled(), controlled());
        var gathered = LocalContentStore.gather(inputs.stream().<Promise<String>> map(Controlled::promise).toList());

        var results = List.of(Result.success("v0"), Result.<String> failure(Causes.cause("input failed")));

        for (var i = 0; i < inputs.size(); i++) {
            inputs.get(i).runLastRegistered(results.get(i));
        }

        for (var i = 0; i < inputs.size(); i++) {
            inputs.get(i).runTheRest(results.get(i));
        }

        assertThat(gathered.isResolved()).isTrue();
        assertThat(gathered.await().isFailure()).isTrue();
    }

    private static Controlled controlled() {
        return new Controlled();
    }

    @SuppressWarnings("unchecked")
    private static final class Controlled {
        private final List<Consumer<Result<String>>> handlers = new ArrayList<>();
        private final Promise<String> promise = (Promise<String>) Proxy.newProxyInstance(Promise.class.getClassLoader(),
                                                                                           new Class<?>[]{Promise.class},
                                                                                           (self, method, args) -> {
                                                                                               if (method.getName().equals("onResult")) {
                                                                                                   handlers.add((Consumer<Result<String>>) args[0]);
                                                                                                   return self;
                                                                                               }
                                                                                               throw new UnsupportedOperationException(method.getName());
                                                                                           });

        Promise<String> promise() {
            return promise;
        }

        void runLastRegistered(Result<String> result) {
            handlers.getLast().accept(result);
        }

        void runTheRest(Result<String> result) {
            for (var i = 0; i < handlers.size() - 1; i++) {
                handlers.get(i).accept(result);
            }
        }
    }
}
