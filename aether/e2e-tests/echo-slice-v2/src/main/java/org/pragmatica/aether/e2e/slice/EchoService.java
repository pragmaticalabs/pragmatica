package org.pragmatica.aether.e2e.slice;

import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/**
 * Pure function test slice for E2E testing - VERSION 2.
 * Responses include version field to distinguish from v1.
 */
@Slice
public interface EchoService {
    String VERSION = "2.0";

    // === Requests ===
    record EchoRequest(String message) {
        private static final Fn1<Cause, String> MESSAGE_REQUIRED = Causes.forOneValue("Message is required, got: '%s'");
        private static final Fn1<Cause, Integer> MESSAGE_TOO_LONG = Causes.forOneValue("Message exceeds max length 10000, got: %d");
        private static final int MAX_LENGTH = 10000;

        public static Result<EchoRequest> echoRequest(String message) {
            if (message == null || message.isBlank()) {
                return MESSAGE_REQUIRED.apply(message)
                                       .result();
            }
            if (message.length() > MAX_LENGTH) {
                return MESSAGE_TOO_LONG.apply(message.length())
                                       .result();
            }
            return Result.success(new EchoRequest(message));
        }
    }

    record PingRequest() {
        public static Result<PingRequest> pingRequest() {
            return Result.success(new PingRequest());
        }
    }

    record TransformRequest(String operation, String value) {
        private static final Set<String> VALID_OPERATIONS = Set.of("reverse", "upper", "lower", "hash");
        private static final Fn1<Cause, String> INVALID_OPERATION = Causes.forOneValue("Invalid operation: '%s'. Valid: reverse, upper, lower, hash");
        private static final Fn1<Cause, String> VALUE_REQUIRED = Causes.forOneValue("Value is required, got: '%s'");
        private static final Fn1<Cause, Integer> VALUE_TOO_LONG = Causes.forOneValue("Value exceeds max length 10000, got: %d");
        private static final int MAX_LENGTH = 10000;

        public static Result<TransformRequest> transformRequest(String operation, String value) {
            if (operation == null || !VALID_OPERATIONS.contains(operation.toLowerCase())) {
                return INVALID_OPERATION.apply(operation)
                                        .result();
            }
            if (value == null || value.isEmpty()) {
                return VALUE_REQUIRED.apply(value)
                                     .result();
            }
            if (value.length() > MAX_LENGTH) {
                return VALUE_TOO_LONG.apply(value.length())
                                     .result();
            }
            return Result.success(new TransformRequest(operation.toLowerCase(), value));
        }
    }

    record FailRequest(int code) {
        private static final Fn1<Cause, Integer> INVALID_CODE = Causes.forOneValue("HTTP code must be 400-599, got: %d");

        public static Result<FailRequest> failRequest(int code) {
            if (code < 400 || code > 599) {
                return INVALID_CODE.apply(code)
                                   .result();
            }
            return Result.success(new FailRequest(code));
        }
    }

    // === Responses (v2: include version field) ===
    record EchoResponse(String message, long timestamp, String version) {
        public static EchoResponse echoResponse(String message) {
            return new EchoResponse(message, System.currentTimeMillis(), VERSION);
        }
    }

    record PingResponse(boolean pong, String nodeId, long timestamp, String version) {
        public static PingResponse pingResponse() {
            var nodeId = System.getProperty("NODE_ID",
                                            System.getenv()
                                                  .getOrDefault("NODE_ID", "unknown"));
            return new PingResponse(true, nodeId, System.currentTimeMillis(), VERSION);
        }
    }

    record TransformResponse(String operation, String input, String result, String version) {
        public static TransformResponse transformResponse(String operation, String input, String result) {
            return new TransformResponse(operation, input, result, VERSION);
        }
    }

    // === Errors ===
    sealed interface EchoError extends Cause {
        record ValidationFailed(String reason) implements EchoError {
            @Override
            public String message() {
                return reason;
            }
        }

        record ControlledFailure(int code, String text) implements EchoError {
            @Override
            public String message() {
                return "Controlled failure: " + code + " - " + text;
            }
        }

        static ControlledFailure controlledFailure(int code) {
            var text = switch (code) {
                case 400 -> "Bad Request";
                case 401 -> "Unauthorized";
                case 403 -> "Forbidden";
                case 404 -> "Not Found";
                case 500 -> "Internal Server Error";
                case 502 -> "Bad Gateway";
                case 503 -> "Service Unavailable";
                default -> "Error " + code;
            };
            return new ControlledFailure(code, text);
        }
    }

    // === Operations ===
    Promise<EchoResponse> echo(EchoRequest request);

    Promise<PingResponse> ping(PingRequest request);

    Promise<TransformResponse> transform(TransformRequest request);

    Promise<EchoError.ControlledFailure> fail(FailRequest request);

    // === Factory ===
    static EchoService echoService() {
        return new EchoServiceImpl();
    }

    final class EchoServiceImpl implements EchoService {
        @Override
        public Promise<EchoResponse> echo(EchoRequest request) {
            return Promise.success(EchoResponse.echoResponse(request.message()));
        }

        @Override
        public Promise<PingResponse> ping(PingRequest request) {
            return Promise.success(PingResponse.pingResponse());
        }

        @Override
        public Promise<TransformResponse> transform(TransformRequest request) {
            var result = switch (request.operation()) {
                case "reverse" -> reverse(request.value());
                case "upper" -> request.value()
                                       .toUpperCase();
                case "lower" -> request.value()
                                       .toLowerCase();
                case "hash" -> hash(request.value());
                default -> request.value();
            };
            return Promise.success(TransformResponse.transformResponse(request.operation(), request.value(), result));
        }

        @Override
        public Promise<EchoError.ControlledFailure> fail(FailRequest request) {
            return EchoError.controlledFailure(request.code())
                            .promise();
        }

        private String reverse(String input) {
            return new StringBuilder(input).reverse()
                                           .toString();
        }

        private String hash(String input) {
            try{
                var digest = MessageDigest.getInstance("SHA-256");
                var hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
                return HexFormat.of()
                                .formatHex(hashBytes);
            } catch (NoSuchAlgorithmException e) {
                return "hash-error";
            }
        }
    }
}
