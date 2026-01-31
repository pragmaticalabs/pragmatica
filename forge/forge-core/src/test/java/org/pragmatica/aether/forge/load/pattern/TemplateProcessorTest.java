package org.pragmatica.aether.forge.load.pattern;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateProcessorTest {

    @Test
    void compile_succeeds_forValidTemplate() {
        TemplateProcessor.compile("SKU-${random:#####}")
            .onFailureRun(Assertions::fail)
            .onSuccess(proc -> {
                var result = proc.process();
                assertThat(result).matches("SKU-\\d{5}");
            });
    }

    @Test
    void compile_succeeds_forMultiplePatterns() {
        TemplateProcessor.compile("${uuid}-${range:1-100}")
            .onFailureRun(Assertions::fail)
            .onSuccess(proc -> {
                var result = proc.process();
                assertThat(result).matches("[0-9a-f-]+-\\d+");
            });
    }

    @Test
    void compile_succeeds_forPlainText() {
        TemplateProcessor.compile("plain text without patterns")
            .onFailureRun(Assertions::fail)
            .onSuccess(proc -> {
                assertThat(proc.process()).isEqualTo("plain text without patterns");
            });
    }

    @Test
    void compile_fails_forInvalidPattern() {
        TemplateProcessor.compile("Hello ${invalid:pattern}")
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message()).contains("Unknown pattern type"));
    }

    @Test
    void process_generatesNewValues_onEachCall() {
        TemplateProcessor.compile("${uuid}")
            .onFailureRun(Assertions::fail)
            .onSuccess(proc -> {
                var first = proc.process();
                var second = proc.process();
                assertThat(first).isNotEqualTo(second);
            });
    }

    @Test
    void process_incrementsSequence_onEachCall() {
        TemplateProcessor.compile("ID-${seq:100}")
            .onFailureRun(Assertions::fail)
            .onSuccess(proc -> {
                assertThat(proc.process()).isEqualTo("ID-100");
                assertThat(proc.process()).isEqualTo("ID-101");
                assertThat(proc.process()).isEqualTo("ID-102");
            });
    }

    @Test
    void resetSequences_resetsCounter() {
        TemplateProcessor.compile("ID-${seq:1}")
            .onFailureRun(Assertions::fail)
            .onSuccess(proc -> {
                assertThat(proc.process()).isEqualTo("ID-1");
                assertThat(proc.process()).isEqualTo("ID-2");
                proc.resetSequences();
                assertThat(proc.process()).isEqualTo("ID-1");
            });
    }

    @Test
    void compile_handlesJsonBody() {
        var json = "{\"id\": \"${uuid}\", \"quantity\": ${range:1-100}}";
        TemplateProcessor.compile(json)
            .onFailureRun(Assertions::fail)
            .onSuccess(proc -> {
                var result = proc.process();
                assertThat(result).startsWith("{\"id\": \"");
                assertThat(result).contains("\", \"quantity\": ");
                assertThat(result).endsWith("}");
            });
    }
}
