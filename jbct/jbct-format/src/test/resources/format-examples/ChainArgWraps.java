package format.examples;

import org.pragmatica.lang.Result;

public class ChainArgWraps {
    // Single method-ref args in each chain step.
    Result<String> shortRefs(Result<String> input) {
        return input.flatMap(this::validate)
                    .flatMap(this::normalize);
    }

    // Short single-lambda args in each chain step.
    Result<String> shortLambdas(Result<String> input) {
        return input.map(s -> s.trim())
                    .filter(s -> !s.isEmpty());
    }

    // A chain step whose args span multiple lines — args wrap aligned to first
    // arg, chain continuation aligns to the call's `.`.
    Result<String> multiArgStep(Result<String> input) {
        return Result.all(input.map(String::trim),
                          input.map(String::toLowerCase))
                     .flatMap(t -> validate("first"));
    }

    Result<String> validate(String s) {
        return Result.success(s);
    }

    Result<String> normalize(String s) {
        return Result.success(s);
    }
}
