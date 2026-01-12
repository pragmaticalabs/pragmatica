package org.pragmatica.aether.infra.statemachine;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.infra.statemachine.Transition.transition;

class StateMachineTest {

    enum OrderState { CREATED, PAID, SHIPPED, DELIVERED, CANCELLED }
    enum OrderEvent { PAY, SHIP, DELIVER, CANCEL }

    private StateMachine<OrderState, OrderEvent, String> machine;

    @BeforeEach
    void setUp() {
        var definition = StateMachineDefinition.<OrderState, OrderEvent, String>builder("order")
            .initialState(OrderState.CREATED)
            .finalStates(Set.of(OrderState.DELIVERED, OrderState.CANCELLED))
            .transition(OrderState.CREATED, OrderEvent.PAY, OrderState.PAID)
            .transition(OrderState.CREATED, OrderEvent.CANCEL, OrderState.CANCELLED)
            .transition(OrderState.PAID, OrderEvent.SHIP, OrderState.SHIPPED)
            .transition(OrderState.PAID, OrderEvent.CANCEL, OrderState.CANCELLED)
            .transition(OrderState.SHIPPED, OrderEvent.DELIVER, OrderState.DELIVERED)
            .build()
            .unwrap();

        machine = StateMachine.stateMachine(definition);
    }

    // ========== Create Tests ==========

    @Test
    void create_startsInInitialState() {
        machine.create("order-1", "user-123")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(info -> {
                   assertThat(info.currentState()).isEqualTo(OrderState.CREATED);
                   assertThat(info.machineId()).isEqualTo("order-1");
                   assertThat(info.transitionCount()).isZero();
               });
    }

    @Test
    void create_fails_whenAlreadyExists() {
        machine.create("order-1", "user-123")
               .flatMap(info -> machine.create("order-1", "user-456"))
               .await()
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isInstanceOf(StateMachineError.AlreadyStarted.class));
    }

    // ========== Send Event Tests ==========

    @Test
    void send_transitionsState() {
        machine.create("order-1", "user-123")
               .flatMap(info -> machine.send("order-1", OrderEvent.PAY))
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(info -> {
                   assertThat(info.currentState()).isEqualTo(OrderState.PAID);
                   assertThat(info.transitionCount()).isEqualTo(1);
               });
    }

    @Test
    void send_multipleTransitions() {
        machine.create("order-1", "user-123")
               .flatMap(info -> machine.send("order-1", OrderEvent.PAY))
               .flatMap(info -> machine.send("order-1", OrderEvent.SHIP))
               .flatMap(info -> machine.send("order-1", OrderEvent.DELIVER))
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(info -> {
                   assertThat(info.currentState()).isEqualTo(OrderState.DELIVERED);
                   assertThat(info.transitionCount()).isEqualTo(3);
               });
    }

    @Test
    void send_fails_whenEventNotHandled() {
        machine.create("order-1", "user-123")
               .flatMap(info -> machine.send("order-1", OrderEvent.SHIP))  // Can't ship before paying
               .await()
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isInstanceOf(StateMachineError.EventNotHandled.class));
    }

    @Test
    void send_fails_whenMachineNotExists() {
        machine.send("nonexistent", OrderEvent.PAY)
               .await()
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isInstanceOf(StateMachineError.NotStarted.class));
    }

    // ========== State Query Tests ==========

    @Test
    void getState_returnsCurrentState() {
        machine.create("order-1", "user-123")
               .flatMap(info -> machine.send("order-1", OrderEvent.PAY))
               .flatMap(info -> machine.getState("order-1"))
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(opt -> {
                   assertThat(opt.isPresent()).isTrue();
                   opt.onPresent(info -> assertThat(info.currentState()).isEqualTo(OrderState.PAID));
               });
    }

    @Test
    void getState_returnsEmpty_whenNotExists() {
        machine.getState("nonexistent")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(opt -> assertThat(opt.isPresent()).isFalse());
    }

    @Test
    void exists_returnsTrue_whenCreated() {
        machine.create("order-1", "user-123")
               .flatMap(info -> machine.exists("order-1"))
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(exists -> assertThat(exists).isTrue());
    }

    @Test
    void exists_returnsFalse_whenNotCreated() {
        machine.exists("nonexistent")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(exists -> assertThat(exists).isFalse());
    }

    // ========== Completion Tests ==========

    @Test
    void isComplete_returnsTrue_whenInFinalState() {
        machine.create("order-1", "user-123")
               .flatMap(info -> machine.send("order-1", OrderEvent.CANCEL))
               .flatMap(info -> machine.isComplete("order-1"))
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(complete -> assertThat(complete).isTrue());
    }

    @Test
    void isComplete_returnsFalse_whenNotInFinalState() {
        machine.create("order-1", "user-123")
               .flatMap(info -> machine.isComplete("order-1"))
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(complete -> assertThat(complete).isFalse());
    }

    // ========== Available Events Tests ==========

    @Test
    void getAvailableEvents_returnsValidEvents() {
        machine.create("order-1", "user-123")
               .flatMap(info -> machine.getAvailableEvents("order-1"))
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(events -> assertThat(events).containsExactlyInAnyOrder(OrderEvent.PAY, OrderEvent.CANCEL));
    }

    @Test
    void getAvailableEvents_returnsEmpty_whenNotExists() {
        machine.getAvailableEvents("nonexistent")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(events -> assertThat(events).isEmpty());
    }

    // ========== Reset Tests ==========

    @Test
    void reset_resetsToInitialState() {
        machine.create("order-1", "user-123")
               .flatMap(info -> machine.send("order-1", OrderEvent.PAY))
               .flatMap(info -> machine.reset("order-1"))
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(info -> assertThat(info.currentState()).isEqualTo(OrderState.CREATED));
    }

    @Test
    void reset_fails_whenNotExists() {
        machine.reset("nonexistent")
               .await()
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isInstanceOf(StateMachineError.NotStarted.class));
    }

    // ========== Delete Tests ==========

    @Test
    void delete_returnsTrue_whenExists() {
        machine.create("order-1", "user-123")
               .flatMap(info -> machine.delete("order-1"))
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(deleted -> assertThat(deleted).isTrue());
    }

    @Test
    void delete_returnsFalse_whenNotExists() {
        machine.delete("nonexistent")
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(deleted -> assertThat(deleted).isFalse());
    }

    // ========== List Instances Tests ==========

    @Test
    void listInstances_returnsAllIds() {
        machine.create("order-1", "user-1")
               .flatMap(info -> machine.create("order-2", "user-2"))
               .flatMap(info -> machine.create("order-3", "user-3"))
               .flatMap(info -> machine.listInstances())
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(ids -> assertThat(ids).containsExactlyInAnyOrder("order-1", "order-2", "order-3"));
    }

    // ========== Transition with Action Tests ==========

    @Test
    void transitionWithAction_executesAction() {
        var actionExecuted = new AtomicBoolean(false);

        var definition = StateMachineDefinition.<OrderState, OrderEvent, String>builder("order-with-action")
            .initialState(OrderState.CREATED)
            .transition(transition(OrderState.CREATED, OrderEvent.PAY, OrderState.PAID,
                ctx -> {
                    actionExecuted.set(true);
                    return Promise.success(Unit.unit());
                }))
            .build()
            .unwrap();

        var machineWithAction = StateMachine.stateMachine(definition);

        machineWithAction.create("order-1", "user-123")
                         .flatMap(info -> machineWithAction.send("order-1", OrderEvent.PAY))
                         .await()
                         .onFailureRun(Assertions::fail)
                         .onSuccess(info -> {
                             assertThat(info.currentState()).isEqualTo(OrderState.PAID);
                             assertThat(actionExecuted.get()).isTrue();
                         });
    }

    // ========== Transition with Guard Tests ==========

    @Test
    void transitionWithGuard_blocksWhenGuardFails() {
        var definition = StateMachineDefinition.<OrderState, OrderEvent, String>builder("order-with-guard")
            .initialState(OrderState.CREATED)
            .transition(Transition.transitionGuarded(OrderState.CREATED, OrderEvent.PAY, OrderState.PAID,
                ctx -> ctx.userContext().equals("premium-user")))  // Only premium users can pay
            .build()
            .unwrap();

        var guardedMachine = StateMachine.stateMachine(definition);

        guardedMachine.create("order-1", "regular-user")
                      .flatMap(info -> guardedMachine.send("order-1", OrderEvent.PAY))
                      .await()
                      .onSuccessRun(Assertions::fail)
                      .onFailure(cause -> assertThat(cause).isInstanceOf(StateMachineError.InvalidTransition.class));
    }

    @Test
    void transitionWithGuard_allowsWhenGuardPasses() {
        var definition = StateMachineDefinition.<OrderState, OrderEvent, String>builder("order-with-guard")
            .initialState(OrderState.CREATED)
            .transition(Transition.transitionGuarded(OrderState.CREATED, OrderEvent.PAY, OrderState.PAID,
                ctx -> ctx.userContext().equals("premium-user")))
            .build()
            .unwrap();

        var guardedMachine = StateMachine.stateMachine(definition);

        guardedMachine.create("order-1", "premium-user")
                      .flatMap(info -> guardedMachine.send("order-1", OrderEvent.PAY))
                      .await()
                      .onFailureRun(Assertions::fail)
                      .onSuccess(info -> assertThat(info.currentState()).isEqualTo(OrderState.PAID));
    }

    // ========== Definition Tests ==========

    @Test
    void definition_validatesRequiredFields() {
        StateMachineDefinition.<OrderState, OrderEvent, String>builder("")
            .initialState(OrderState.CREATED)
            .transition(OrderState.CREATED, OrderEvent.PAY, OrderState.PAID)
            .build()
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message()).contains("Name cannot be null or empty"));
    }

    @Test
    void definition_requiresInitialState() {
        StateMachineDefinition.<OrderState, OrderEvent, String>builder("test")
            .transition(OrderState.CREATED, OrderEvent.PAY, OrderState.PAID)
            .build()
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message()).contains("Initial state must be defined"));
    }

    @Test
    void definition_requiresAtLeastOneTransition() {
        StateMachineDefinition.<OrderState, OrderEvent, String>builder("test")
            .initialState(OrderState.CREATED)
            .build()
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message()).contains("At least one transition must be defined"));
    }

    // ========== Lifecycle Tests ==========

    @Test
    void stop_clearsAllInstances() {
        machine.create("order-1", "user-1")
               .flatMap(info -> machine.create("order-2", "user-2"))
               .flatMap(info -> machine.stop())
               .flatMap(unit -> machine.listInstances())
               .await()
               .onFailureRun(Assertions::fail)
               .onSuccess(ids -> assertThat(ids).isEmpty());
    }
}
