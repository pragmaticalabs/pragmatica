package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AspectTest {

    interface TestService {
        String process(String input);
    }

    @Test
    void identity_returns_same_instance() {
        TestService service = input -> input.toUpperCase();
        var aspect = Aspect.<TestService>identity();

        var wrapped = aspect.apply(service);

        assertThat(wrapped).isSameAs(service);
    }

    @Test
    void compose_applies_before_first() {
        var calls = new java.util.ArrayList<String>();

        Aspect<TestService> first = instance -> {
            calls.add("first");
            return instance;
        };
        Aspect<TestService> second = instance -> {
            calls.add("second");
            return instance;
        };

        TestService service = input -> input;
        second.compose(first).apply(service);

        assertThat(calls).containsExactly("first", "second");
    }

    @Test
    void andThen_applies_this_first() {
        var calls = new java.util.ArrayList<String>();

        Aspect<TestService> first = instance -> {
            calls.add("first");
            return instance;
        };
        Aspect<TestService> second = instance -> {
            calls.add("second");
            return instance;
        };

        TestService service = input -> input;
        first.andThen(second).apply(service);

        assertThat(calls).containsExactly("first", "second");
    }
}
