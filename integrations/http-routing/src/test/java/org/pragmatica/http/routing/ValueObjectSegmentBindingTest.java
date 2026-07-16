package org.pragmatica.http.routing;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// Value-object segment binding (#397 §4.2): the `mapped(...)` combinator composes a framework-owned
/// `String -> P` parser (here [PathParameter#aUuid] / [QueryParameter#aUuid]) with a value object's
/// fallible `lift` (`P -> Result<T>`), so a path/query segment is parsed to its primitive then lifted
/// into the value object. The value object contributes only its `P`-level `lift` — a plain
/// `Fn1<Result<T>, P>` — so this transport binding needs no knowledge of the `ValueMapping` type.
///
/// Contract proven here: a valid segment yields the lifted value object; a malformed primitive OR a
/// rejected `lift` both surface as a typed [HttpError] 400 (never a 500, never a silent raw string);
/// and on a bad segment the route handler is never invoked.
class ValueObjectSegmentBindingTest {

    private static final UUID VALID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID NIL = new UUID(0L, 0L);

    /// A value object parsed from a `UUID`, rejecting the nil UUID to exercise the lift-failure path.
    record SeatId(UUID value) {
        static final Cause NIL_SEAT = Causes.cause("seat id must not be the nil UUID");

        static Result<SeatId> seatId(UUID raw) {
            return raw.equals(NIL)
                   ? NIL_SEAT.result()
                   : Result.success(new SeatId(raw));
        }
    }

    record SeatResponse(String id) {}

    private static final PathParameter<SeatId> SEAT_PATH = PathParameter.aUuid().mapped(SeatId::seatId);
    private static final QueryParameter<SeatId> SEAT_QUERY = QueryParameter.aUuid("seat").mapped(SeatId::seatId);

    /// Assert the failure carries a typed [HttpError] 400. A direct parse failure IS the `HttpError`;
    /// a route-level failure wraps it in a `Causes.CompositeCause` (via `matchPath`'s `Result.all`),
    /// so resolve it through [Cause#stream] — exactly how `SliceRouter` maps it to an HTTP 400.
    private static void assertBadRequest(Cause cause) {
        var httpError = cause.stream()
                             .filter(HttpError.class::isInstance)
                             .map(HttpError.class::cast)
                             .findFirst();
        assertThat(httpError).as("a typed HttpError must be present in: " + cause.message()).isPresent();
        assertThat(httpError.get().status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Nested
    class PathCombinator {
        @Test
        void parse_liftsValueObject_forValidPrimitive() {
            SEAT_PATH.parse(VALID.toString())
                     .onFailure(cause -> Assertions.fail(cause.message()))
                     .onSuccess(seatId -> assertThat(seatId.value()).isEqualTo(VALID));
        }

        @Test
        void parse_yieldsTyped400_forMalformedPrimitive() {
            var captured = new AtomicReference<Cause>();
            SEAT_PATH.parse("not-a-uuid")
                     .onSuccess(seatId -> Assertions.fail("expected failure but lifted " + seatId))
                     .onFailure(captured::set);
            assertBadRequest(captured.get());
        }

        @Test
        void parse_yieldsTyped400_whenLiftRejects() {
            var captured = new AtomicReference<Cause>();
            SEAT_PATH.parse(NIL.toString())
                     .onSuccess(seatId -> Assertions.fail("expected lift rejection but got " + seatId))
                     .onFailure(captured::set);
            assertBadRequest(captured.get());
        }
    }

    @Nested
    class QueryCombinator {
        @Test
        void parse_none_whenMissing() {
            SEAT_QUERY.parse(List.of())
                      .onFailure(cause -> Assertions.fail(cause.message()))
                      .onSuccess(opt -> assertThat(opt.isEmpty()).isTrue());
        }

        @Test
        void parse_liftsValueObject_forValidPrimitive() {
            SEAT_QUERY.parse(List.of(VALID.toString()))
                      .onFailure(cause -> Assertions.fail(cause.message()))
                      .onSuccess(opt -> opt.onEmpty(() -> Assertions.fail("expected present value"))
                                           .onPresent(seatId -> assertThat(seatId.value()).isEqualTo(VALID)));
        }

        @Test
        void parse_yieldsTyped400_forMalformedPrimitive() {
            var captured = new AtomicReference<Cause>();
            SEAT_QUERY.parse(List.of("not-a-uuid"))
                      .onSuccess(opt -> Assertions.fail("expected failure but got " + opt))
                      .onFailure(captured::set);
            assertBadRequest(captured.get());
        }
    }

    @Nested
    class RouteDispatch {
        @Test
        void handler_receivesLiftedValueObject_forValidSegment() {
            var invoked = new AtomicBoolean(false);
            var captured = new AtomicReference<SeatId>();
            var route = Route.<SeatResponse>get("/seats/")
                             .withPath(SEAT_PATH)
                             .to(seatId -> {
                                 invoked.set(true);
                                 captured.set(seatId);
                                 return Promise.success(new SeatResponse(seatId.value().toString()));
                             })
                             .asJson();

            var ctx = TestRequestContext.of(route, "/seats/" + VALID);

            route.handler()
                 .handle(ctx)
                 .await()
                 .onFailure(cause -> Assertions.fail(cause.message()));

            assertThat(invoked.get()).isTrue();
            assertThat(captured.get()).isNotNull();
            assertThat(captured.get().value()).isEqualTo(VALID);
        }

        @Test
        void handler_notInvoked_andYieldsTyped400_forInvalidSegment() {
            var invoked = new AtomicBoolean(false);
            var route = Route.<SeatResponse>get("/seats/")
                             .withPath(SEAT_PATH)
                             .to(seatId -> {
                                 invoked.set(true);
                                 return Promise.success(new SeatResponse(seatId.value().toString()));
                             })
                             .asJson();

            var ctx = TestRequestContext.of(route, "/seats/not-a-uuid");
            var failure = new AtomicReference<Cause>();

            route.handler()
                 .handle(ctx)
                 .await()
                 .onSuccess(response -> Assertions.fail("expected 400 but handler produced " + response))
                 .onFailure(failure::set);

            assertThat(invoked.get()).as("handler must not run when the value object cannot be lifted").isFalse();
            assertBadRequest(failure.get());
        }
    }
}
