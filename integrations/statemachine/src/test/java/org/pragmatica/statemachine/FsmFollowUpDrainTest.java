/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.statemachine;

import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/// The follow-up drain of [Fsm] (#3605 ruling): a dispatch returns only after every follow-up queued by its completed
/// transition is dispatched, FIFO; a throwing follow-up stops neither its later siblings nor the rest of an enclosing
/// follow-up frame, the first throwable is rethrown after the queue empties, later ones suppressed; a handler that throws
/// delivers none of its own follow-ups; a dispatch nested directly in a handler rethrows to that handler; a
/// VirtualMachineError is never deferred (#3605 round-4 rulings 1-2). Each test names the property it pins; all of it runs on the test thread.
class FsmFollowUpDrainTest {
    enum Ev { GO, A, B, BOOM, NEST, FAN, SPLIT, KABOOM, Y, KABOOM2, X, TWICE, SAME, Z, CHECKED, SNEAKY, W,
              NEST_CATCH, NEST_PROPAGATE, NEST_BOOM, OOPS, Q, NEXT, DONE, GO_VM, OOPS2, DEEP, VM, LATE, DEEP_LATE,
              GO_AE, AE, AFTER_AE }

    /// The states and the FSM of one test. `seen` is what a handler's code after a nested dispatch observed;
    /// `caught` is what a handler caught from a nested dispatch; `shared` is the one exception instance two follow-ups
    /// throw.
    static final class Ctx {
        Fsm<St, Ev> fsm;
        St seen;
        Throwable caught;
        final IllegalStateException shared = new IllegalStateException("shared instance");
        final St s0 = new St(this, "S0");
        final St s1 = new St(this, "S1");
        final St sa = new St(this, "SA");
        final St sb = new St(this, "SB");
        final St thrower = new St(this, "THROWER");
        final St fanned = new St(this, "FANNED");
        final St split = new St(this, "SPLIT");
        final St sy = new St(this, "SY");
        final St sx = new St(this, "SX");
        final St twiced = new St(this, "TWICED");
        final St sz = new St(this, "SZ");
        final St checked = new St(this, "CHECKED");
        final St sw = new St(this, "SW");
        final St pq = new St(this, "PQ");
        final St rb = new St(this, "RB");
        final St qd = new St(this, "QD");
        final St after = new St(this, "AFTER");
        final St done = new St(this, "DONE");
        final St v = new St(this, "V");
        final St deep = new St(this, "DEEP");
        final St late = new St(this, "LATE");
        final St errs = new St(this, "ERRS");
        final St errsAfter = new St(this, "ERRS_AFTER");
    }

    record St(Ctx ctx, String name) implements FsmState<St, Ev> {
        @Override
        public void handle(Ev event, TransitionRequest<St, Ev> tx) {
            switch (name + ":" + event) {
                case "S0:GO" -> tx.transitionTo(ctx.s1);
                case "S0:BOOM" -> tx.transitionTo(ctx.thrower);
                case "S0:FAN" -> tx.transitionTo(ctx.fanned);
                case "S0:TWICE" -> tx.transitionTo(ctx.twiced);
                case "S0:CHECKED" -> tx.transitionTo(ctx.checked);
                case "S0:NEST" -> nest(tx);
                case "S1:A", "THROWER:A" -> tx.transitionTo(ctx.sa);
                case "SA:B" -> tx.transitionTo(ctx.sb);
                case "FANNED:SPLIT" -> tx.transitionTo(ctx.split);
                case "SPLIT:KABOOM" -> throw new IllegalStateException("first: follow-up handler throws");
                case "SPLIT:Y" -> tx.transitionTo(ctx.sy);
                case "SY:KABOOM2" -> throw new IllegalStateException("second: nested follow-up handler throws");
                case "SY:X" -> tx.transitionTo(ctx.sx);
                case "TWICED:SAME" -> throw ctx.shared;
                case "TWICED:Z" -> tx.transitionTo(ctx.sz);
                case "CHECKED:SNEAKY" -> sneakyThrow(new IOException("checked, thrown sneakily"));
                case "CHECKED:W" -> tx.transitionTo(ctx.sw);
                case "S0:NEST_CATCH" -> nestCatching(tx);
                case "S0:NEST_PROPAGATE" -> nestPropagating(tx);
                case "PQ:NEST_BOOM" -> tx.transitionTo(ctx.rb);
                case "RB:OOPS" -> throw new IllegalStateException("nested follow-up throws");
                case "RB:Q" -> tx.transitionTo(ctx.qd);
                case "QD:NEXT" -> tx.transitionTo(ctx.after);
                case "AFTER:DONE" -> tx.transitionTo(ctx.done);
                case "S0:GO_VM" -> tx.transitionTo(ctx.v);
                case "V:OOPS2" -> throw new IllegalStateException("earlier non-VM follow-up failure");
                case "V:DEEP" -> tx.transitionTo(ctx.deep);
                case "DEEP:VM" -> throw new InternalError("vm error from a nested follow-up");
                case "DEEP:DEEP_LATE", "DEEP:LATE" -> tx.transitionTo(ctx.late);
                case "S0:GO_AE" -> tx.transitionTo(ctx.errs);
                case "ERRS:AE" -> throw new AssertionError("an Error that is not a VirtualMachineError");
                case "ERRS:AFTER_AE" -> tx.transitionTo(ctx.errsAfter);
                default -> tx.ignore();
            }
        }

        /// A handler that dispatches nested, then records the state its own code sees after that dispatch returned.
        private void nest(TransitionRequest<St, Ev> tx) {
            ctx.fsm.dispatch(Ev.GO);
            ctx.seen = ctx.fsm.current();
            tx.ignore();
        }

        /// A handler that transitions to PQ (whose entry queues Q in this handler's frame), dispatches nested, and
        /// catches what that dispatch rethrows.
        private void nestCatching(TransitionRequest<St, Ev> tx) {
            tx.transitionTo(ctx.pq);
            try {
                ctx.fsm.dispatch(Ev.NEST_BOOM);
            } catch (IllegalStateException e) {
                ctx.caught = e;
            }
        }

        /// The same handler, letting the nested dispatch's rethrow propagate.
        private void nestPropagating(TransitionRequest<St, Ev> tx) {
            tx.transitionTo(ctx.pq);
            ctx.fsm.dispatch(Ev.NEST_BOOM);
        }

        @Override
        public void onExit() {
            if (name.equals("S0") && ctx.fsm.current() == ctx.s1) {
                ctx.fsm.followUp(Ev.A);
            }
        }

        @Override
        public void onEntry() {
            switch (name) {
                case "S1" -> ctx.fsm.followUp(Ev.B);
                case "THROWER" -> followUpThenThrow();
                case "FANNED" -> followUpAll(Ev.SPLIT, Ev.X);
                case "SPLIT" -> followUpAll(Ev.KABOOM, Ev.Y);
                case "SY" -> followUpAll(Ev.KABOOM2);
                case "TWICED" -> followUpAll(Ev.SAME, Ev.SAME, Ev.Z);
                case "CHECKED" -> followUpAll(Ev.SNEAKY, Ev.W);
                case "PQ" -> followUpAll(Ev.Q);
                case "RB" -> followUpAll(Ev.OOPS);
                case "AFTER" -> followUpAll(Ev.DONE);
                case "V" -> followUpAll(Ev.OOPS2, Ev.DEEP, Ev.LATE);
                case "DEEP" -> followUpAll(Ev.VM, Ev.DEEP_LATE);
                case "ERRS" -> followUpAll(Ev.AE, Ev.AFTER_AE);
                default -> {}
            }
        }

        private void followUpThenThrow() {
            ctx.fsm.followUp(Ev.A);
            throw new IllegalStateException("hook throws after following up");
        }

        private void followUpAll(Ev... events) {
            for (var event : events) {
                ctx.fsm.followUp(event);
            }
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /// Throw a checked exception from a method that declares none, as a sneaky-throwing handler would.
    @SuppressWarnings("unchecked")
    private static <X extends Throwable> void sneakyThrow(Throwable failure) throws X {
        throw (X) failure;
    }

    private final List<String> transitions = new CopyOnWriteArrayList<>();

    private Ctx fresh() {
        var ctx = new Ctx();

        ctx.fsm = Fsm.<St, Ev> fsm("follow-up-drain", f -> ctx.s0, observer());
        return ctx;
    }

    /// FIFO among one dispatch's follow-ups: onExit(S0) queues A, then onEntry(S1) queues B; A runs before B.
    @Test
    void followUpsOfOneDispatch_runInTheOrderTheHooksQueuedThem() {
        var ctx = fresh();

        ctx.fsm.dispatch(Ev.GO);
        assertThat(transitions).containsExactly("S0->S1", "S1->SA", "SA->SB");
        assertThat(ctx.fsm.current()).isSameAs(ctx.sb);
    }

    /// Every dispatch opens its own frame: a dispatch nested inside a handler delivers its own follow-ups before it
    /// returns, so the handler's code after it sees their outcome.
    @Test
    void nestedDispatchInAHandler_deliversItsOwnFollowUpsBeforeItReturns() {
        var ctx = fresh();

        ctx.fsm.dispatch(Ev.NEST);
        assertThat(ctx.seen).as("the handler's code after the nested dispatch sees its follow-ups' outcome")
                            .isSameAs(ctx.sb);
        assertThat(transitions).containsExactly("S0->S1", "S1->SA", "SA->SB");
    }

    /// FAN queues [SPLIT, X]; SPLIT queues [KABOOM, Y]; KABOOM throws "first"; Y queues [KABOOM2], which throws
    /// "second". Y (a later sibling of the throwing KABOOM) and X (the rest of the enclosing frame) are still
    /// delivered, depth-first; the dispatch then rethrows "first" with "second" suppressed into it.
    @Test
    void throwingFollowUp_laterSiblingsAndEnclosingFrameAreDelivered_firstRethrownLaterSuppressed() {
        var ctx = fresh();

        var thrown = catchThrowable(() -> ctx.fsm.dispatch(Ev.FAN));

        assertThat(transitions).as("Y after the throwing KABOOM, then X after the throwing SPLIT step")
                               .containsExactly("S0->FANNED", "FANNED->SPLIT", "SPLIT->SY", "SY->SX");
        assertThat(ctx.fsm.current()).isSameAs(ctx.sx);
        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                          .hasMessageStartingWith("first");
        assertThat(thrown.getSuppressed()).as("the later throwable is suppressed into the first, nothing else")
                                          .extracting(Throwable::getMessage)
                                          .containsExactly("second: nested follow-up handler throws");
    }

    /// Two follow-ups throw the same instance: it is rethrown once, never suppressed into itself (which
    /// [Throwable#addSuppressed] refuses with an exception that would escape the drain), and Z after them is delivered.
    @Test
    void sameThrowableFromTwoFollowUps_isRethrownOnce_andTheLaterSiblingIsDelivered() {
        var ctx = fresh();

        var thrown = catchThrowable(() -> ctx.fsm.dispatch(Ev.TWICE));

        assertThat(thrown).isSameAs(ctx.shared);
        assertThat(thrown.getSuppressed()).isEmpty();
        assertThat(ctx.fsm.current()).isSameAs(ctx.sz);
    }

    /// A follow-up that throws a checked exception sneakily: the drain still delivers W after it, and the dispatch
    /// rethrows the checked exception wrapped in [UndeclaredThrowableException] (dispatch declares none).
    @Test
    void sneakyCheckedThrowFromAFollowUp_laterSiblingDelivered_rethrownWrappedInUndeclaredThrowable() {
        var ctx = fresh();

        var thrown = catchThrowable(() -> ctx.fsm.dispatch(Ev.CHECKED));

        assertThat(ctx.fsm.current()).isSameAs(ctx.sw);
        assertThat(thrown).isExactlyInstanceOf(UndeclaredThrowableException.class)
                          .hasCauseExactlyInstanceOf(IOException.class);
        assertThat(thrown.getCause()).hasMessage("checked, thrown sneakily");
    }

    /// A handler that throws delivers none of its own follow-ups: THROWER's entry queues A and then throws.
    @Test
    void throwingHook_deliversNoneOfItsFollowUps() {
        var ctx = fresh();

        var thrown = catchThrowable(() -> ctx.fsm.dispatch(Ev.BOOM));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(ctx.fsm.current()).as("the follow-up queued before the throw is not delivered")
                                     .isSameAs(ctx.thrower);
        assertThat(transitions).as("the throw happens before the observer").isEmpty();
    }

    /// The thread's frame is restored when a handler throws: a later followUp outside any dispatch runs at once.
    @Test
    void afterAThrowingDispatch_followUpOutsideADispatch_isDispatchedAtOnce() {
        var ctx = fresh();

        assertThat(catchThrowable(() -> ctx.fsm.dispatch(Ev.BOOM))).isInstanceOf(IllegalStateException.class);
        ctx.fsm.followUp(Ev.A);
        assertThat(ctx.fsm.current()).as("the frame was restored on the throw, so A ran at once")
                                     .isSameAs(ctx.sa);
    }

    /// A dispatch nested directly in a handler is the handler's call (#3605 round-4 ruling 1): its follow-up OOPS
    /// throws, the nested drain rethrows it to the handler, the handler catches it and returns, and Q (queued in the
    /// handler's frame by its completed transition to PQ) is then delivered.
    @Test
    void handlerNestedDispatchWhoseFollowUpThrows_handlerSeesIt_andCatchingIt_keepsItsFrame() {
        var ctx = fresh();

        ctx.fsm.dispatch(Ev.NEST_CATCH);

        assertThat(ctx.caught).as("the nested dispatch rethrew its follow-up's throwable to the handler")
                              .isInstanceOf(IllegalStateException.class)
                              .hasMessage("nested follow-up throws");
        assertThat(transitions).containsExactly("S0->PQ", "PQ->RB", "RB->QD");
        assertThat(ctx.fsm.current()).isSameAs(ctx.qd);
    }

    /// The same handler lets the rethrow propagate, so it is a throwing handler (#3605 round-4 ruling 1): its frame's
    /// follow-up Q is discarded, the thread's frame is restored (a followUp outside a dispatch runs at once), and a
    /// later dispatch delivers its own follow-ups as usual.
    @Test
    void handlerNestedDispatchWhoseFollowUpThrows_propagated_discardsTheFrame_restoresIt_laterDispatchUnaffected() {
        var ctx = fresh();

        var thrown = catchThrowable(() -> ctx.fsm.dispatch(Ev.NEST_PROPAGATE));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                          .hasMessage("nested follow-up throws");
        assertThat(transitions).as("Q, queued in the propagating handler's frame, is not delivered")
                               .containsExactly("S0->PQ", "PQ->RB");
        assertThat(ctx.fsm.current()).isSameAs(ctx.rb);

        ctx.fsm.followUp(Ev.Q);
        assertThat(ctx.fsm.current()).as("the frame was restored, so a followUp outside a dispatch ran at once")
                                     .isSameAs(ctx.qd);

        ctx.fsm.dispatch(Ev.NEXT);
        assertThat(ctx.fsm.current()).as("a later dispatch delivers its own follow-up DONE")
                                     .isSameAs(ctx.done);
        assertThat(transitions).containsExactly("S0->PQ", "PQ->RB", "RB->QD", "QD->AFTER", "AFTER->DONE");
    }

    /// A VirtualMachineError is never deferred (#3605 round-4 ruling 2): V queues [OOPS2, DEEP, LATE]; OOPS2 throws an
    /// ordinary exception, DEEP queues [VM, DEEP_LATE] and VM throws a VM error. It propagates at once: neither its later
    /// sibling DEEP_LATE nor the rest of the enclosing frame (LATE) runs, the earlier ordinary failure is not what
    /// surfaces, and the thread's frame is restored (a followUp outside a dispatch runs at once).
    @Test
    void vmErrorFromAFollowUp_propagatesAtOnce_laterSiblingsAndEnclosingFrameNotRun() {
        var ctx = fresh();

        var thrown = catchThrowable(() -> ctx.fsm.dispatch(Ev.GO_VM));

        assertThat(thrown).isExactlyInstanceOf(InternalError.class)
                          .hasMessage("vm error from a nested follow-up");
        assertThat(thrown.getSuppressed()).as("a follow-up failure recorded before the VM error is not attached to it")
                                          .isEmpty();
        assertThat(transitions).as("nothing after the VM error ran").containsExactly("S0->V", "V->DEEP");
        assertThat(ctx.fsm.current()).isSameAs(ctx.deep);

        ctx.fsm.followUp(Ev.LATE);
        assertThat(ctx.fsm.current()).as("the frames were restored, so a followUp outside a dispatch ran at once")
                                     .isSameAs(ctx.late);
    }

    /// The deferral boundary is exactly [VirtualMachineError] (#3605 v3605i row 30): an [AssertionError], an Error that
    /// is not a VM error, is deferred like any other throwable. ERRS queues [AE, AFTER_AE]; AE throws the AssertionError,
    /// AFTER_AE (its later sibling) still runs, and the AssertionError surfaces after the drain.
    @Test
    void nonVmErrorFromAFollowUp_isDeferredLikeAnyThrowable_laterSiblingStillRuns() {
        var ctx = fresh();

        var thrown = catchThrowable(() -> ctx.fsm.dispatch(Ev.GO_AE));

        assertThat(thrown).isExactlyInstanceOf(AssertionError.class)
                          .hasMessage("an Error that is not a VirtualMachineError");
        assertThat(transitions).as("the later sibling ran: an AssertionError is not a VM error")
                               .containsExactly("S0->ERRS", "ERRS->ERRS_AFTER");
        assertThat(ctx.fsm.current()).isSameAs(ctx.errsAfter);
    }

    private FsmObserver<St, Ev> observer() {
        return new FsmObserver<>() {
            @Override
            public void onTransition(FsmTags tags, St from, St to) {
                transitions.add(from + "->" + to);
            }

            @Override
            public void onCasLost(FsmTags tags, St expected, St actual) {}

            @Override
            public void onEventIgnored(FsmTags tags, St state, Ev event) {}
        };
    }
}
