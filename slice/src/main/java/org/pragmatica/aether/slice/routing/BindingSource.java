package org.pragmatica.aether.slice.routing;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

public sealed interface BindingSource {
    String asString();

    record PathVar(String name) implements BindingSource {
        @Override
        public String asString() {
            return "path." + name;
        }
    }

    record QueryVar(String name) implements BindingSource {
        @Override
        public String asString() {
            return "query." + name;
        }
    }

    record Header(String name) implements BindingSource {
        @Override
        public String asString() {
            return "header." + name;
        }
    }

    record Cookie(String name) implements BindingSource {
        @Override
        public String asString() {
            return "cookie." + name;
        }
    }

    record Body() implements BindingSource {
        @Override
        public String asString() {
            return "body";
        }
    }

    record Value() implements BindingSource {
        @Override
        public String asString() {
            return "value";
        }
    }

    record Key() implements BindingSource {
        @Override
        public String asString() {
            return "key";
        }
    }

    record RequestField(String field) implements BindingSource {
        @Override
        public String asString() {
            return "request." + field;
        }
    }

    record Metadata(String name) implements BindingSource {
        @Override
        public String asString() {
            return "metadata." + name;
        }
    }

    Fn1<Cause, String>INVALID_BINDING_SOURCE = Causes.forOneValue("Invalid binding source format: %s");

    static Result<BindingSource> parse(String input) {
        if (input.equals("body")) {
            return Result.success(new Body());
        }
        if (input.equals("value")) {
            return Result.success(new Value());
        }
        if (input.equals("key")) {
            return Result.success(new Key());
        }
        var dotIndex = input.indexOf('.');
        if (dotIndex == - 1) {
            return INVALID_BINDING_SOURCE.apply(input)
                                         .result();
        }
        var prefix = input.substring(0, dotIndex);
        var suffix = input.substring(dotIndex + 1);
        if (suffix.isEmpty()) {
            return INVALID_BINDING_SOURCE.apply(input)
                                         .result();
        }
        return switch (prefix) {
            case"path" -> Result.success(new PathVar(suffix));
            case"query" -> Result.success(new QueryVar(suffix));
            case"header" -> Result.success(new Header(suffix));
            case"cookie" -> Result.success(new Cookie(suffix));
            case"request" -> Result.success(new RequestField(suffix));
            case"metadata" -> Result.success(new Metadata(suffix));
            default -> INVALID_BINDING_SOURCE.apply(input)
                                             .result();
        };
    }
}
