package org.pragmatica.aether.slice.routing;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BindingTest {

    @Test
    void binding_succeeds_withValidParam() {
        Binding.binding("userId", new BindingSource.PathVar("userId"))
               .onFailureRun(Assertions::fail)
               .onSuccess(binding -> {
                   assertThat(binding.param()).isEqualTo("userId");
                   assertThat(binding.source()).isInstanceOf(BindingSource.PathVar.class);
               });
    }

    @Test
    void binding_succeeds_withUnderscoreParam() {
        Binding.binding("user_id", new BindingSource.PathVar("userId"))
               .onFailureRun(Assertions::fail)
               .onSuccess(binding -> assertThat(binding.param()).isEqualTo("user_id"));
    }

    @Test
    void binding_succeeds_withNumbersInParam() {
        Binding.binding("user123", new BindingSource.PathVar("userId"))
               .onFailureRun(Assertions::fail)
               .onSuccess(binding -> assertThat(binding.param()).isEqualTo("user123"));
    }

    @Test
    void binding_fails_withInvalidParam() {
        Binding.binding("user-id", new BindingSource.PathVar("userId"))
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause.message()).contains("Invalid parameter name"));
    }

    @Test
    void binding_fails_withParamStartingWithNumber() {
        Binding.binding("123user", new BindingSource.PathVar("userId"))
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause.message()).contains("Invalid parameter name"));
    }
}
