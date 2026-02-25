package org.pragmatica.aether.e2e.slice;

import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

import static org.pragmatica.lang.Result.success;

/// Pure function test slice for E2E testing - VERSION 2.
/// Responses include version field to distinguish from v1.
@Slice
public interface EchoService {
    String VERSION = "2.0";

    // === Requests ===
    record EchoRequest(String message) {
        private static final Fn1<Cause, String> MESSAGE_REQUIRED = Causes.forOneValue("Message is required, got: '%s'");
        private static final Fn1<Cause, Integer> MESSAGE_TOO_LONG = Causes.forOneValue("Message exceeds max length 10000, got: %d");
        private static final int MAX_LENGTH = 10000;

        public static Result<EchoRequest> echoRequest(String message) {
            if (message == null || Verify.Is.blank(message)) {
                return MESSAGE_REQUIRED.apply(message)
                                       .result();
            }
            if (message.length() > MAX_LENGTH) {
                return MESSAGE_TOO_LONG.apply(message.length())
                                       .result();
            }
            return success(new EchoRequest(message));
        }
    }

    record PingRequest() {
        public static PingRequest pingRequest() {
            return new PingRequest();
        }
    }

    record TransformRequest(String operation, String value) {
        private static final Set<String> VALID_OPERATIONS = Set.of("reverse", "upper", "lower", "hash");
        private static final Fn1<Cause, String> INVALID_OPERATION = Causes.forOneValue("Invalid operation: '%s'. Valid: reverse, upper, lower, hash");
        private static final Fn1<Cause, String> VALUE_REQUIRED = Causes.forOneValue("Value is required, got: '%s'");
        private static final Fn1<Cause, Integer> VALUE_TOO_LONG = Causes.forOneValue("Value exceeds max length 10000, got: %d");
        private static final int MAX_LENGTH = 10000;

        public static Result<TransformRequest> transformRequest(String operation, String value) {
            var validOp = parseOperation(operation);
            var validValue = parseValue(value);
            return Result.all(validOp, validValue)
                         .map(TransformRequest::new);
        }

        @SuppressWarnings("JBCT-UTIL-02")
        private static Result<String> parseOperation(String operation) {
            if (operation == null || !VALID_OPERATIONS.contains(operation.toLowerCase())) {
                return INVALID_OPERATION.apply(operation)
                                        .result();
            }
            return success(operation.toLowerCase());
        }

        private static Result<String> parseValue(String value) {
            if (value == null || Verify.Is.empty(value)) {
                return VALUE_REQUIRED.apply(value)
                                     .result();
            }
            if (value.length() > MAX_LENGTH) {
                return VALUE_TOO_LONG.apply(value.length())
                                     .result();
            }
            return success(value);
        }
    }

    record FailRequest(int code) {
        private static final Fn1<Cause, Integer> INVALID_CODE = Causes.forOneValue("HTTP code must be 400-599, got: %d");

        public static Result<FailRequest> failRequest(int code) {
            if (code < 400 || code > 599) {
                return INVALID_CODE.apply(code)
                                   .result();
            }
            return success(new FailRequest(code));
        }
    }

    // === Responses (v2: include version field) ===
    record EchoResponse(String message, long timestamp, String version) {
        public static Result<EchoResponse> echoResponse(String message) {
            return success(new EchoResponse(message, System.currentTimeMillis(), VERSION));
        }
    }

    record PingResponse(boolean pong, String nodeId, long timestamp, String version) {
        public static Result<PingResponse> pingResponse() {
            var nodeId = readNodeId();
            return success(new PingResponse(true, nodeId, System.currentTimeMillis(), VERSION));
        }

        private static String readNodeId() {
            return System.getProperty("NODE_ID",
                                      System.getenv()
                                            .getOrDefault("NODE_ID", "unknown"));
        }
    }

    record TransformResponse(String operation, String input, String result, String version) {
        public static Result<TransformResponse> transformResponse(String operation,
                                                                  String input,
                                                                  String result) {
            return success(new TransformResponse(operation, input, result, VERSION));
        }
    }

    // === Errors ===
    sealed interface EchoError extends Cause {
        record ValidationFailed(String reason) implements EchoError {
            public static Result<ValidationFailed> validationFailed(String reason) {
                return success(new ValidationFailed(reason));
            }

            @Override
            public String message() {
                return reason;
            }
        }

        record ControlledFailure(int code, String text) implements EchoError {
            public static Result<ControlledFailure> controlledFailure(int code, String text) {
                return success(new ControlledFailure(code, text));
            }

            @Override
            public String message() {
                return "Controlled failure: " + code + " - " + text;
            }
        }

        static ControlledFailure toControlledFailure(int code) {
            var text = formatErrorText(code);
            return ControlledFailure.controlledFailure(code, text)
                                    .unwrap();
        }

        private static String formatErrorText(int code) {
            return switch (code) {
                case 400 -> "Bad Request";
                case 401 -> "Unauthorized";
                case 403 -> "Forbidden";
                case 404 -> "Not Found";
                case 500 -> "Internal Server Error";
                case 502 -> "Bad Gateway";
                case 503 -> "Service Unavailable";
                default -> "Error " + code;
            };
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
            return EchoResponse.echoResponse(request.message())
                               .async();
        }

        @Override
        public Promise<PingResponse> ping(PingRequest request) {
            return PingResponse.pingResponse()
                               .async();
        }

        @Override
        public Promise<TransformResponse> transform(TransformRequest request) {
            var transformed = convertValue(request);
            return TransformResponse.transformResponse(request.operation(),
                                                       request.value(),
                                                       transformed)
                                    .async();
        }

        @Override
        public Promise<EchoError.ControlledFailure> fail(FailRequest request) {
            return EchoError.toControlledFailure(request.code())
                            .promise();
        }

        @SuppressWarnings("JBCT-SEQ-01")
        private String convertValue(TransformRequest request) {
            return switch (request.operation()) {
                case "reverse" -> reverse(request.value());
                case "upper" -> request.value()
                                       .toUpperCase();
                case "lower" -> request.value()
                                       .toLowerCase();
                case "hash" -> computeHash(request.value());
                default -> request.value();
            };
        }

        private String reverse(String input) {
            return new StringBuilder(input).reverse()
                                           .toString();
        }

        private String computeHash(String input) {
            return Result.lift1(Causes::fromThrowable, EchoServiceImpl::digestSha256, input)
                         .or("hash-error");
        }

        @SuppressWarnings("JBCT-EX-01")
        private static String digestSha256(String input) throws Exception {
            var digest = MessageDigest.getInstance("SHA-256");
            var hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of()
                            .formatHex(hashBytes);
        }
    }
}
